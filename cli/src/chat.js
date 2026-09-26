// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

import readline from 'node:readline'
import { loadConfig, saveConfig, findProvider } from './config.js'
import { chatStream, listModels, startServer } from './ollama.js'
import { cloudChatStream, resolveTarget } from './cloud.js'

const c = {
  dim: (s) => `\x1b[2m${s}\x1b[0m`,
  cyan: (s) => `\x1b[36m${s}\x1b[0m`,
  green: (s) => `\x1b[32m${s}\x1b[0m`,
  yellow: (s) => `\x1b[33m${s}\x1b[0m`,
  red: (s) => `\x1b[31m${s}\x1b[0m`,
  bold: (s) => `\x1b[1m${s}\x1b[0m`
}

export async function goCommand() {
  const config = loadConfig()

  process.stdout.write(c.dim('正在启动本地AI服务...\n'))
  try {
    await startServer()
  } catch (error) {
    process.stdout.write(c.yellow(`本地AI服务未启动: ${error.message}\n`))
  }

  let ollamaModels = []
  try {
    ollamaModels = await listModels()
  } catch {
    ollamaModels = []
  }

  let target = resolveTarget(config, config.model, ollamaModels)

  if (!target) {
    target = await chooseModel(config, ollamaModels)
    if (!target) {
      process.stdout.write(c.red('没有可用模型。请先执行: likkahub model pull <名称>\n'))
      return
    }
    config.model = describeTarget(target)
    saveConfig(config)
  }

  process.stdout.write(
    c.bold('likkahub') +
      c.dim(`  ·  模型 ${describeTarget(target)}  ·  输入 /help 查看命令\n`)
  )

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  const messages = []
  let closed = false
  rl.on('close', () => {
    closed = true
  })

  while (!closed) {
    const line = await question(rl, c.cyan('你 > '))
    if (line === null) break
    const input = line.trim()
    if (!input) continue

    if (input.startsWith('/')) {
      const handled = await handleSlash(input, {
        config,
        ollamaModels,
        getTarget: () => target,
        setTarget: (value) => {
          target = value
        },
        messages
      })
      if (handled === 'exit') break
      continue
    }

    messages.push({ role: 'user', content: input })
    process.stdout.write(c.green('AI > '))
    let answer = ''
    try {
      const onToken = (piece) => {
        answer += piece
        process.stdout.write(piece)
      }
      if (target.kind === 'ollama') {
        await chatStream({ model: target.model, messages, onToken })
      } else {
        await cloudChatStream({
          provider: target.provider,
          model: target.model,
          messages,
          onToken
        })
      }
    } catch (error) {
      process.stdout.write(c.red(`\n[错误] ${error.message}\n`))
    }
    process.stdout.write('\n\n')
    if (answer) messages.push({ role: 'assistant', content: answer })
  }

  rl.close()
  process.stdout.write(c.dim('再见。\n'))
}

async function handleSlash(input, ctx) {
  const [command, ...rest] = input.split(/\s+/)
  const argument = rest.join(' ')
  switch (command) {
    case '/exit':
    case '/quit':
      return 'exit'
    case '/help':
      process.stdout.write(
        [
          c.bold('命令:'),
          '  /model <名称>   切换模型，例如 /model qwen2.5:1.5b 或 /model openai:gpt-4o-mini',
          '  /models         列出本地与云端可用模型',
          '  /clear          清空当前对话上下文',
          '  /exit           退出',
          ''
        ].join('\n')
      )
      return
    case '/clear':
      ctx.messages.length = 0
      process.stdout.write(c.dim('已清空上下文。\n'))
      return
    case '/models': {
      process.stdout.write(c.bold('本地 (Ollama):\n'))
      const local = await listModels().catch(() => [])
      local.forEach((name) => process.stdout.write(`  - ${name}\n`))
      if (local.length === 0) process.stdout.write(c.dim('  （暂无，使用 likkahub model pull 拉取）\n'))
      process.stdout.write(c.bold('云端:\n'))
      ctx.config.providers.forEach((provider) => {
        provider.models.forEach((model) => {
          process.stdout.write(`  - ${provider.id}:${model}\n`)
        })
      })
      return
    }
    case '/model': {
      if (!argument) {
        process.stdout.write(c.dim(`当前模型: ${describeTarget(ctx.getTarget())}\n`))
        return
      }
      const next = resolveTarget(ctx.config, argument, ctx.ollamaModels)
      if (!next) {
        process.stdout.write(c.red(`未知模型: ${argument}\n`))
        return
      }
      ctx.setTarget(next)
      ctx.config.model = describeTarget(next)
      saveConfig(ctx.config)
      process.stdout.write(c.dim(`已切换到 ${describeTarget(next)}\n`))
      return
    }
    default:
      process.stdout.write(c.red(`未知命令: ${command}\n`))
  }
}

async function chooseModel(config, ollamaModels) {
  const options = []
  ollamaModels.forEach((name) => options.push({ kind: 'ollama', model: name }))
  config.providers.forEach((provider) => {
    provider.models.forEach((model) => options.push({ kind: 'cloud', provider, model }))
  })
  if (options.length === 0) return null
  if (options.length === 1) return options[0]

  process.stdout.write(c.bold('选择模型:\n'))
  options.forEach((option, index) => {
    process.stdout.write(`  ${index + 1}. ${describeTarget(option)}\n`)
  })
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  const answer = await question(rl, c.cyan('序号 > '))
  rl.close()
  const index = Number(answer) - 1
  return options[index] || options[0]
}

function describeTarget(target) {
  if (!target) return '未设置'
  return target.kind === 'ollama' ? `ollama:${target.model}` : `${target.provider.id}:${target.model}`
}

function question(rl, prompt) {
  return new Promise((resolve) => {
    const onClose = () => {
      rl.off('close', onClose)
      resolve(null)
    }
    rl.once('close', onClose)
    rl.question(prompt, (answer) => {
      rl.off('close', onClose)
      resolve(answer)
    })
  })
}
