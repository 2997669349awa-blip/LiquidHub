// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

import { findProvider } from './config.js'

/**
 * 把用户输入的模型标识解析成调用目标。
 * - "ollama:qwen2.5:1.5b" / "qwen2.5:1.5b" → 本地 Ollama
 * - "openai:gpt-4o-mini"                → 配置里的云 provider
 */
export function resolveTarget(config, spec, knownOllamaModels = []) {
  if (!spec) return null
  const separator = spec.indexOf(':')
  if (separator > 0) {
    const head = spec.slice(0, separator)
    const rest = spec.slice(separator + 1)
    const provider = findProvider(config, head)
    if (provider) return { kind: 'cloud', provider, model: rest }
  }
  // 命中本地已有模型名时按 ollama 处理
  const isOllama = knownOllamaModels.includes(spec) || spec.startsWith('ollama:')
  if (isOllama) {
    const model = spec.startsWith('ollama:') ? spec.slice('ollama:'.length) : spec
    return { kind: 'ollama', model }
  }
  return null
}

export async function cloudChatStream({ provider, model, messages, onToken }) {
  const url = `${provider.baseUrl.replace(/\/$/, '')}/chat/completions`
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(provider.apiKey ? { Authorization: `Bearer ${provider.apiKey}` } : {})
    },
    body: JSON.stringify({ model, messages, stream: true })
  })
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(`${provider.name} HTTP ${response.status} ${text}`)
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
      if (!line.startsWith('data:')) continue
      const payload = line.slice('data:'.length).trim()
      if (payload === '[DONE]') continue
      try {
        const chunk = JSON.parse(payload)
        const piece = chunk.choices?.[0]?.delta?.content
        if (piece) {
          full += piece
          onToken?.(piece)
        }
      } catch {
        // 忽略半行
      }
    }
  }
  return full
}
