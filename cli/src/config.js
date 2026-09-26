// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

export const CONFIG_DIR =
  process.env.LIKKAHUB_HOME || path.join(os.homedir(), '.config', 'likkahub')
export const STATE_DIR =
  process.env.LIKKAHUB_STATE_FILES || path.join(os.homedir(), '.local', 'state', 'likkahub')
export const CONFIG_FILE = path.join(CONFIG_DIR, 'config.json')
export const OLLAMA_LOG = path.join(STATE_DIR, 'ollama.log')
export const OLLAMA_PID = path.join(STATE_DIR, 'ollama.pid')

export const OLLAMA_HOST = process.env.OLLAMA_HOST || '127.0.0.1'
export const OLLAMA_PORT = Number(process.env.OLLAMA_PORT || 11434)
export const OLLAMA_BASE = `http://${OLLAMA_HOST}:${OLLAMA_PORT}`

const DEFAULT_CONFIG = {
  model: null,
  providers: [
    {
      id: 'openai',
      type: 'openai',
      name: 'OpenAI',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: '',
      models: []
    }
  ]
}

export function ensureDirs() {
  fs.mkdirSync(CONFIG_DIR, { recursive: true })
  fs.mkdirSync(STATE_DIR, { recursive: true })
}

export function loadConfig() {
  ensureDirs()
  if (!fs.existsSync(CONFIG_FILE)) {
    return structuredClone(DEFAULT_CONFIG)
  }
  try {
    const parsed = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'))
    return {
      model: parsed.model ?? null,
      providers: Array.isArray(parsed.providers) ? parsed.providers : []
    }
  } catch (error) {
    throw new Error(`配置文件损坏: ${CONFIG_FILE} (${error.message})`)
  }
}

export function saveConfig(config) {
  ensureDirs()
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2) + '\n')
}

export function findProvider(config, id) {
  return config.providers.find((provider) => provider.id === id)
}
