// Modified by AI Hello World on 2026-09-27.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// likkahub sound：在 Termux 侧启动 PulseAudio 服务端，把工作区桌面的声音实时播到手机。
// 用法：likkahub sound install   -> 下载/安装必要资源
//       likkahub sound           -> 启动声音服务（tcp:127.0.0.1:4713）
//       likkahub sound status    -> 查看状态
//       likkahub sound stop      -> 停止

import { spawnSync } from 'node:child_process'

function has(cmd) {
  return spawnSync('sh', ['-c', `command -v ${cmd}`], { stdio: 'ignore' }).status === 0
}

function run(cmd, args) {
  return spawnSync(cmd, args, { stdio: 'inherit' })
}

export async function soundCommand(args) {
  const action = args[0] || 'start'
  switch (action) {
    case 'install':
      return install()
    case 'stop':
      return stop()
    case 'status':
      return status()
    case 'start':
    default:
      return start()
  }
}

function install() {
  if (!has('pkg')) {
    process.stdout.write('未检测到 pkg（Termux）。请手动安装 pulseaudio 与 pulseaudio-utils。\n')
    return 1
  }
  process.stdout.write('正在安装 pulseaudio ...\n')
  const r = run('pkg', ['install', '-y', 'pulseaudio', 'pulseaudio-utils'])
  if (r.status !== 0) {
    process.stdout.write('安装失败，请检查网络后重试。\n')
    return r.status
  }
  process.stdout.write('完成。接下来执行：likkahub sound\n')
  return 0
}

function start() {
  if (!has('pulseaudio')) {
    process.stdout.write('未安装 pulseaudio，请先执行：likkahub sound install\n')
    return 1
  }
  spawnSync('pulseaudio', ['-k'], { stdio: 'ignore' })
  const r = spawnSync(
    'pulseaudio',
    [
      '--start',
      '--exit-idle-time=-1',
      '--load=module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1'
    ],
    { stdio: 'inherit' }
  )
  if (r.status !== 0) {
    process.stdout.write('pulseaudio 启动失败。\n')
    return r.status
  }
  // 选择一个 Android 输出 sink（Termux）
  if (run('pactl', ['load-module', 'module-aaudio-sink']).status === 0) {
    spawnSync('pactl', ['set-default-sink', 'AAudio_sink'], { stdio: 'ignore' })
  } else if (run('pactl', ['load-module', 'module-opensles-sink']).status === 0) {
    spawnSync('pactl', ['set-default-sink', 'OpenSL_ES_sink'], { stdio: 'ignore' })
  }
  process.stdout.write('\n声音服务已启动：tcp:127.0.0.1:4713\n')
  process.stdout.write('LiquidHub 工作区桌面里的声音会实时传到手机播放。\n')
  return 0
}

function stop() {
  const r = spawnSync('pulseaudio', ['-k'], { stdio: 'inherit' })
  process.stdout.write('已请求停止声音服务。\n')
  return r.status === 0 ? 0 : 0
}

function status() {
  if (!has('pactl')) {
    process.stdout.write('未安装 pulseaudio-utils，请先执行：likkahub sound install\n')
    return 1
  }
  return spawnSync('pactl', ['info'], { stdio: 'inherit' }).status
}
