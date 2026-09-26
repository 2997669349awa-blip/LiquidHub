// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

import fs from 'node:fs'
import { spawn, spawnSync } from 'node:child_process'
import {
  OLLAMA_BASE,
  OLLAMA_HOST,
  OLLAMA_LOG,
  OLLAMA_PID,
  OLLAMA_PORT,
  ensureDirs
} from './config.js'

export function hasOllamaBinary() {
  const result = spawnSync('ollama', ['--version'], { stdio: 'ignore' })
  return result.status === 0
}

export async function isServerUp(timeoutMillis = 800) {
  try {
    const response = await fetch(`${OLLAMA_BASE}/api/tags`, {
      signal: AbortSignal.timeout(timeoutMillis)
    })
    return response.ok
  } catch {
    return false
  }
}

export async function listModels() {
  const response = await fetch(`${OLLAMA_BASE}/api/tags`)
  if (!response.ok) throw new Error(`Ollama 返回 HTTP ${response.status}`)
  const data = await response.json()
  return (data.models || []).map((item) => item.name).filter(Boolean)
}

export function readPid() {
  try {
    return Number(fs.readFileSync(OLLAMA_PID, 'utf8').trim()) || null
  } catch {
    return null
  }
}

function isPidAlive(pid) {
  if (!pid) return false
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

export async function startServer({ quiet = true } = {}) {
  ensureDirs()
  if (await isServerUp()) return { started: false, reachable: true }

  if (!hasOllamaBinary()) {
    throw new Error('未找到 ollama 命令，请先执行: pkg install ollama')
  }

  const logFd = fs.openSync(OLLAMA_LOG, 'a')
  const child = spawn('ollama', ['serve'], {
    detached: true,
    stdio: ['ignore', logFd, logFd],
    env: { ...process.env, OLLAMA_HOST: `${OLLAMA_HOST}:${OLLAMA_PORT}` }
  })
  child.unref()
  fs.writeFileSync(OLLAMA_PID, String(child.pid))

  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    if (await isServerUp(500)) return { started: true, reachable: true, pid: child.pid }
    await sleep(300)
  }
  return { started: true, reachable: false, pid: child.pid }
}

export async function stopServer() {
  const pid = readPid()
  if (isPidAlive(pid)) {
    try {
      process.kill(pid, 'SIGTERM')
    } catch {
      // 已经退出
    }
  }
  try {
    fs.rmSync(OLLAMA_PID)
  } catch {
    // ignore
  }
  const deadline = Date.now() + 4000
  while (isPidAlive(pid) && Date.now() < deadline) await sleep(200)
  if (isPidAlive(pid)) {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {
      // ignore
    }
  }
}

export function pullModel(name) {
  return runInherit('ollama', ['pull', name])
}

export function removeModel(name) {
  return runInherit('ollama', ['rm', name])
}

export async function chatStream({ model, messages, onToken }) {
  const response = await fetch(`${OLLAMA_BASE}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, messages, stream: true })
  })
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(`Ollama HTTP ${response.status} ${text}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let full = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let index
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).trim()
      buffer = buffer.slice(index + 1)
      if (!line) continue
      try {
        const chunk = JSON.parse(line)
        const piece = chunk.message?.content
        if (piece) {
          full += piece
          onToken?.(piece)
        }
      } catch {
        // 忽略不完整的行
      }
    }
  }
  return full
}

export function runInherit(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit' })
    child.on('error', reject)
    child.on('close', (code) => {
      if (code === 0) resolve()
      else reject(new Error(`${command} 退出码 ${code}`))
    })
  })
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
