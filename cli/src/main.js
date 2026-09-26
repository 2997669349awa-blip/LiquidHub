// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

import fs from 'node:fs'
import { spawn, spawnSync } from 'node:child_process'
import {
  CONFIG_FILE,
  OLLAMA_LOG,
  ensureDirs,
  findProvider,
  loadConfig,
  saveConfig
} from './config.js'
import {
  hasOllamaBinary,
  isServerUp,
  listModels,
  pullModel,
  removeModel,
  startServer,
  stopServer
} from './ollama.js'
import { goCommand } from './chat.js'

const VERSION = '0.1.0'

export async function main(argv) {
  const [command = 'help', ...rest] = argv
  switch (command) {
    case 'init':
      return initCommand(rest)
    case 'go':
      return goCommand()
    case 'start':
      return startCommand()
    case 'stop':
      return stopCommand()
    case 'status':
      return statusCommand()
    case 'model':
      return modelCommand(rest)
    case 'provider':
    case 'providers':
      return providerCommand(rest)
    case 'log':
      return logCommand()
    case 'help':
    case '--help':
    case '-h':
      return help()
    case 'version':
    case '--version':
    case '-v':
      return process.stdout.write(`likkahub ${VERSION}\n`)
    default:
      process.stdout.write(`未知命令: ${command}\n\n`)
      return help(1)
  }
}

function initCommand(args) {
  ensureDirs()
  const install = args.includes('--install')
  const pkg = hasBinary('pkg')

  if (install && pkg) {
    process.stdout.write('执行: pkg install -y nodejs ollama\n')
    const result = spawnSync('pkg', ['install', '-y', 'nodejs', 'ollama'], { stdio: 'inherit' })
    if (result.status !== 0) {
      process.stdout.write('pkg 安装失败，请手动执行: pkg install nodejs ollama\n')
    }
  }

  process.stdout.write(`Node:    ${process.version}\n`)
  process.stdout.write(`ollama:  ${hasOllamaBinary() ? '已安装' : '未安装（执行 pkg install ollama）'}\n`)
  process.stdout.write(`配置:    ${CONFIG_FILE}\n`)
  process.stdout.write(`日志:    ${OLLAMA_LOG}\n`)
  process.stdout.write('\n下一步: likkahub model pull qwen2.5:1.5b 然后 likkahub go\n')
}

async function startCommand() {
  const result = await startServer()
  if (!result.reachable) {
    process.stdout.write('服务启动超时，查看日志: likkahub log\n')
    return process.exitCode = 1
  }
  process.stdout.write(`${result.started ? '已启动' : '已在运行'} 本地AI服务 (127.0.0.1:11434)\n`)
}

async function stopCommand() {
  await stopServer()
  process.stdout.write('已停止本地AI服务\n')
}

async function statusCommand() {
  const up = await isServerUp()
  process.stdout.write(up ? '本地AI服务运行中\n' : '本地AI服务未运行\n')
  if (up) {
    const models = await listModels().catch(() => [])
    process.stdout.write(`模型 (${models.length}):\n`)
    models.forEach((name) => process.stdout.write(`  - ${name}\n`))
  }
}

async function modelCommand(args) {
  const [action = 'list', name] = args
  switch (action) {
    case 'list': {
      const up = await isServerUp()
      if (!up) {
        process.stdout.write('本地AI服务未运行，先执行: likkahub start\n')
        return
      }
      const models = await listModels()
      if (models.length === 0) {
        process.stdout.write('暂无模型，使用 likkahub model pull <名称> 拉取\n')
        return
      }
      models.forEach((model) => process.stdout.write(`- ${model}\n`))
      return
    }
    case 'pull':
    case 'add': {
      if (!name) return usage('likkahub model pull <名称>')
      await startServer()
      await pullModel(name)
      return
    }
    case 'rm':
    case 'remove': {
      if (!name) return usage('likkahub model rm <名称>')
      await removeModel(name)
      return
    }
    default:
      return usage('likkahub model <list|pull|rm> [名称]')
  }
}

function providerCommand(args) {
  const [action = 'list', ...rest] = args
  const config = loadConfig()
  switch (action) {
    case 'list': {
      if (config.providers.length === 0) {
        process.stdout.write('暂无云端 provider\n')
        return
      }
      config.providers.forEach((provider) => {
        process.stdout.write(
          `- ${provider.id}  ${provider.name}  ${provider.baseUrl}  [${provider.models.join(', ')}]\n`
        )
      })
      return
    }
    case 'add': {
      const [id, baseUrl, apiKey = ''] = rest
      if (!id || !baseUrl) return usage('likkahub provider add <id> <baseUrl> [apiKey]')
      if (findProvider(config, id)) {
        process.stdout.write(`provider 已存在: ${id}\n`)
        return
      }
      config.providers.push({
        id,
        type: 'openai',
        name: id,
        baseUrl,
        apiKey,
        models: []
      })
      saveConfig(config)
      process.stdout.write(`已添加 provider: ${id}\n`)
      return
    }
    case 'key': {
      const [id, key] = rest
      const provider = findProvider(config, id)
      if (!provider) return usage(`likkahub provider key <id> <apiKey>`)
      provider.apiKey = key || ''
      saveConfig(config)
      process.stdout.write(`已更新 ${id} 的 apiKey\n`)
      return
    }
    case 'model': {
      const [id, model] = rest
      const provider = findProvider(config, id)
      if (!provider || !model) return usage('likkahub provider model <id> <模型名>')
      if (!provider.models.includes(model)) provider.models.push(model)
      saveConfig(config)
      process.stdout.write(`已为 ${id} 添加模型: ${model}\n`)
      return
    }
    default:
      return usage('likkahub provider <list|add|key|model> ...')
  }
}

function logCommand() {
  ensureDirs()
  if (!fs.existsSync(OLLAMA_LOG)) {
    fs.writeFileSync(OLLAMA_LOG, '')
  }
  process.stdout.write(`跟踪日志 (Ctrl+C 退出): ${OLLAMA_LOG}\n`)
  const child = spawn('tail', ['-f', OLLAMA_LOG], { stdio: 'inherit' })
  child.on('error', () => {
    process.stdout.write('未找到 tail 命令\n')
  })
}

function help(exitCode = 0) {
  process.stdout.write(
    [
      'likkahub - 终端里的本地AI与云端模型',
      '',
      '用法:',
      '  likkahub init [--install]          初始化；--install 自动 pkg install nodejs ollama',
      '  likkahub go                        进入对话（日志静默，另有 likkahub log）',
      '  likkahub start | stop | status     管理本地AI服务',
      '  likkahub model list                列出本地模型',
      '  likkahub model pull <名称>         拉取模型，例如 qwen2.5:1.5b',
      '  likkahub model rm <名称>           删除模型',
      '  likkahub provider list             列出云端 provider',
      '  likkahub provider add <id> <baseUrl> [apiKey]',
      '  likkahub provider key <id> <apiKey>',
      '  likkahub provider model <id> <模型名>',
      '  likkahub log                       实时查看本地AI服务日志',
      '',
      '对话内命令: /model /models /clear /exit',
      ''
    ].join('\n')
  )
  process.exitCode = exitCode
}

function hasBinary(name) {
  const result = spawnSync('sh', ['-c', `command -v ${name}`], { stdio: 'ignore' })
  return result.status === 0
}

function usage(text) {
  process.stdout.write(`用法: ${text}\n`)
}
