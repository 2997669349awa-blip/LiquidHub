// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Adds an optional in-workspace remote desktop (Fluxbox + Chromium + VNC + noVNC).

package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionClient
import me.rerere.rikkahub.ui.pages.extensions.workspace.createWorkspaceTerminalSession

/**
 * Owns the long-lived terminal session that keeps the workspace desktop (Xvfb / Fluxbox /
 * Chromium / x11vnc / websockify) alive.
 *
 * The rootfs is entered with `--kill-on-exit`, so a one-shot `executeCommand` would tear every
 * daemon down as soon as it returned. Instead we keep a single interactive [TerminalSession]
 * per workspace in the app process and write commands into it.
 */
class WorkspaceDesktopManager internal constructor(
    context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val appContext = context.applicationContext
    private val sessions = mutableMapOf<String, TerminalSession>()

    // TerminalSession 的构造函数内部会 new Handler()，用的是「当前线程的 Looper」。
    // 因此必须在有 Looper 的线程上创建；用专用 HandlerThread 既满足要求又不阻塞主线程。
    private val sessionThread = HandlerThread("liquidhub-desktop-session").apply { start() }
    private val sessionDispatcher = Handler(sessionThread.looper).asCoroutineDispatcher()

    /** Writes the helper script into the workspace files area (bind mounted at /workspace). */
    suspend fun ensureScript(workspaceId: String) {
        workspaceRepository.writeText(workspaceId, SCRIPT_PATH, DESKTOP_SCRIPT, overwrite = true)
    }

    suspend fun runAction(workspaceId: String, action: String) {
        ensureScript(workspaceId)
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        // 会话创建 + updateSize()（fork/exec proot）都放在有 Looper 的后台线程上
        val session = withContext(sessionDispatcher) {
            sessionFor(workspace.root, workspace.shellCompatibilityMode)
        }
        val bytes = "sh /workspace/$SCRIPT_PATH $action\n".toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    fun closeWorkspace(root: String) {
        val session = synchronized(sessions) { sessions.remove(root) } ?: return
        // emulator 为 null 表示进程从未启动 (mShellPid == 0)，此时 finishIfRunning()
        // 会执行 kill(0, SIGKILL)，即向整个进程组发 SIGKILL 并杀掉 App 自身
        if (session.emulator != null) {
            runCatching { session.finishIfRunning() }
        }
    }

    private fun sessionFor(root: String, shellCompatibilityMode: Boolean): TerminalSession {
        synchronized(sessions) {
            val existing = sessions[root]
            if (existing != null && existing.emulator != null && existing.isRunning) return existing
            if (existing != null && existing.emulator != null) {
                runCatching { existing.finishIfRunning() }
            }
            val session = createWorkspaceTerminalSession(
                context = appContext,
                root = root,
                client = WorkspaceTerminalSessionClient(appContext, onTitleUpdated = {}, onFinished = {}),
                shellCompatibilityMode = shellCompatibilityMode,
            )
            // 该 session 不绑定 TerminalView，必须显式初始化：否则不会 fork 出 proot 进程，
            // write() 因 mShellPid <= 0 而被静默丢弃，桌面 start/stop/browser 全部失效
            session.updateSize(80, 24)
            sessions[root] = session
            return session
        }
    }

    companion object {
        const val SCRIPT_PATH = ".liquidhub/desktop.sh"
        const val NOVNC_URL = "http://127.0.0.1:6080/vnc.html?autoconnect=1&resize=scale"
    }
}

private const val DESKTOP_SCRIPT = """
#!/bin/sh
# LiquidHub workspace desktop helper: install / start / stop / status / browser
set -u

DISP=":1"
VNC_PORT=5900
WEB_PORT=6080
RUNDIR=/tmp/.liquidhub-desktop
NOVNC_DIR=""
FORCE_INSTALL=0

export DISPLAY="${'$'}DISP"
export HOME="${'$'}{HOME:-/root}"
export XDG_RUNTIME_DIR="${'$'}RUNDIR/xdg"
mkdir -p "${'$'}RUNDIR" "${'$'}XDG_RUNTIME_DIR" 2>/dev/null
chmod 700 "${'$'}XDG_RUNTIME_DIR" 2>/dev/null

log() { echo "[desktop] ${'$'}*"; }

find_novnc() {
  for d in /usr/share/novnc /usr/share/webapps/novnc /usr/local/share/novnc; do
    if [ -d "${'$'}d" ]; then NOVNC_DIR="${'$'}d"; return 0; fi
  done
  return 1
}

install_browser() {
  log "installing browser (firefox preferred)"
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache --allow-untrusted firefox 2>/dev/null \
      || apk add --no-cache --allow-untrusted chromium 2>/dev/null || true
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true firefox-esr 2>/dev/null \
      || apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true firefox 2>/dev/null \
      || apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true chromium 2>/dev/null \
      || apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true chromium-browser 2>/dev/null \
      || apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true epiphany-browser 2>/dev/null \
      || apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true falkon 2>/dev/null \
      || log "WARN: 没有可用的浏览器（可稍后手动安装）"
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm --needed firefox 2>/dev/null \
      || pacman -Sy --noconfirm --needed chromium 2>/dev/null || true
  fi
}

install_pkgs() {
  if [ "${'$'}{FORCE_INSTALL:-0}" != "1" ] \
     && command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1 \
     && command -v fluxbox >/dev/null 2>&1 \
     && command -v xdotool >/dev/null 2>&1 \
     && { command -v scrot >/dev/null 2>&1 || command -v import >/dev/null 2>&1; }; then
    log "desktop already installed, skipping"
    return 0
  fi
  RC=0
  if command -v apk >/dev/null 2>&1; then
    log "Alpine: installing desktop packages"
    apk add --no-cache --allow-untrusted ca-certificates xvfb x11vnc fluxbox xdotool imagemagick scrot dbus \
      pulseaudio pulseaudio-utils font-noto font-noto-cjk bash coreutils || RC=1
  elif command -v apt-get >/dev/null 2>&1; then
    log "Debian/Ubuntu: installing desktop packages"
    export DEBIAN_FRONTEND=noninteractive
    getent hosts mirrors.tuna.tsinghua.edu.cn >/dev/null 2>&1 || log "WARN: 无法解析镜像域名，DNS 可能有问题"
    apt-get update -y -o Acquire::Retries=5 -o Acquire::ForceIPv4=true || RC=1
    apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true \
      ca-certificates xvfb x11vnc fluxbox xdotool imagemagick scrot dbus-x11 fonts-noto-cjk \
      pulseaudio pulseaudio-utils || RC=1
  elif command -v pacman >/dev/null 2>&1; then
    log "Arch: installing desktop packages"
    # Arch 的包用 GPG 签名，老 rootfs 的 keyring 过期会报 key expired / unknown trust
    pacman-key --init >/dev/null 2>&1 || true
    pacman-key --populate archlinuxarm >/dev/null 2>&1 || pacman-key --populate archlinux >/dev/null 2>&1 || true
    pacman -Sy --noconfirm --needed archlinux-keyring >/dev/null 2>&1 || true
    pacman -Sy --noconfirm --needed ca-certificates xorg-server-xvfb x11vnc fluxbox xdotool \
      imagemagick scrot dbus noto-fonts pulseaudio || RC=1
  else
    log "ERROR: unsupported package manager"
    return 1
  fi
  # 浏览器单独装：失败也不影响桌面核心
  install_browser
  if command -v apt-get >/dev/null 2>&1; then
    apt-get clean
    rm -rf /var/lib/apt/lists/* 2>/dev/null
  fi
  # 刷新根证书：老 rootfs 的 CA 缺失/过期会导致 HTTPS 失败
  command -v update-ca-certificates >/dev/null 2>&1 && update-ca-certificates 2>/dev/null || true
  command -v update-ca-trust >/dev/null 2>&1 && update-ca-trust 2>/dev/null || true
  command -v trust >/dev/null 2>&1 && trust extract-compat 2>/dev/null || true
  if [ "${'$'}RC" -ne 0 ]; then
    log "ERROR: desktop install failed (see the apt/apk/pacman messages above)"
    return 1
  fi
  # 安装后自检：直接看到底装没装上、用的是哪个镜像源
  log "verify:"
  for b in Xvfb x11vnc fluxbox xdotool; do
    command -v "${'$'}b" >/dev/null 2>&1 && echo "[desktop]   OK   ${'$'}b" || echo "[desktop]   MISS ${'$'}b"
  done
  if command -v scrot >/dev/null 2>&1 || command -v import >/dev/null 2>&1; then
    echo "[desktop]   OK   screenshot"
  else
    echo "[desktop]   MISS screenshot"
  fi
  for b in firefox firefox-esr chromium chromium-browser epiphany falkon; do
    command -v "${'$'}b" >/dev/null 2>&1 && { echo "[desktop]   OK   browser=${'$'}b"; break; }
  done
  if [ -f /etc/apk/repositories ]; then
    echo "[desktop] mirror: ${'$'}(head -n1 /etc/apk/repositories 2>/dev/null)"
  fi
  if [ -f /etc/apt/sources.list ]; then
    echo "[desktop] mirror: ${'$'}(grep -m1 -E '^(deb|URIs)' /etc/apt/sources.list 2>/dev/null)"
  fi
  log "install done"
}

pid_alive() {
  [ -f "${'$'}1" ] && kill -0 "${'$'}(cat "${'$'}1")" 2>/dev/null
}

is_x_running() { pid_alive "${'$'}RUNDIR/xvfb.pid"; }
is_vnc_running() { pid_alive "${'$'}RUNDIR/x11vnc.pid"; }

# App 内置的 VNC 客户端需要 x11vnc，所以「运行中」以 x11vnc 为准
is_running() { is_vnc_running; }

setup_theme() {
  mkdir -p "${'$'}HOME/.fluxbox" 2>/dev/null
  # 渐变壁纸
  if command -v convert >/dev/null 2>&1; then
    convert -size 1280x720 gradient:'#0f172a'-'#1d4ed8' "${'$'}RUNDIR/wallpaper.png" 2>/dev/null
  fi
  if [ -f "${'$'}RUNDIR/wallpaper.png" ] && command -v display >/dev/null 2>&1; then
    display -window root "${'$'}RUNDIR/wallpaper.png" >/dev/null 2>&1 &
  elif command -v xsetroot >/dev/null 2>&1; then
    xsetroot -solid "#0f172a" 2>/dev/null
  fi
  # Fluxbox 配置：显示底部工具栏（工作区 + 时钟），作为美化基础
  cat > "${'$'}HOME/.fluxbox/init" <<'FBEOF'
session.screen0.toolbar.visible: true
session.screen0.toolbar.autoHide: false
session.screen0.toolbar.placement: BottomCenter
session.screen0.toolbar.widthPercent: 100
session.screen0.toolbar.alpha: 200
session.screen0.toolbar.tools: prevworkspace, workspacename, nextworkspace, iconbar, systemtray, clock
session.screen0.strftimeFormat: %H:%M
session.screen0.workspaces: 1
session.screen0.focusModel: ClickToFocus
FBEOF
}

start() {
  if is_running; then log "already running"; return 0; fi
  if ! command -v Xvfb >/dev/null 2>&1; then
    log "ERROR: desktop environment not installed, run install first"
    return 2
  fi
  pkill -f "Xvfb ${'$'}DISP" 2>/dev/null
  pkill -f "x11vnc" 2>/dev/null
  rm -f /tmp/.X11-unix/X1 2>/dev/null

  log "starting Xvfb"
  # -ac 关闭访问控制，避免 rootfs 里没有 xauth/Xauthority 导致 x11vnc 连不上 X
  RES=$(cat /workspace/.liquidhub/resolution 2>/dev/null)
  [ -z "${'$'}RES" ] && RES=1280x720
  Xvfb "${'$'}DISP" -screen 0 "${'$'}RESx24" -nolisten tcp -ac >"${'$'}RUNDIR/xvfb.log" 2>&1 &
  echo ${'$'}! > "${'$'}RUNDIR/xvfb.pid"
  i=0
  while [ ! -e /tmp/.X11-unix/X1 ] && [ ${'$'}i -lt 20 ]; do sleep 0.5 2>/dev/null || sleep 1; i=${'$'}((i+1)); done
  if ! is_x_running; then
    log "ERROR: Xvfb 启动失败，日志："
    tail -n 20 "${'$'}RUNDIR/xvfb.log" 2>/dev/null
    return 2
  fi

  setup_theme
  log "starting fluxbox"
  fluxbox >"${'$'}RUNDIR/fluxbox.log" 2>&1 &
  echo ${'$'}! > "${'$'}RUNDIR/fluxbox.pid"

  log "starting x11vnc on ${'$'}VNC_PORT"
  x11vnc -display "${'$'}DISP" -forever -shared -localhost -rfbport "${'$'}VNC_PORT" \
    -nopw -quiet -noshm >"${'$'}RUNDIR/x11vnc.log" 2>&1 &
  echo ${'$'}! > "${'$'}RUNDIR/x11vnc.pid"
  sleep 1
  if is_vnc_running; then
    log "STARTED: VNC on 127.0.0.1:${'$'}VNC_PORT"
  else
    log "ERROR: x11vnc 启动失败，桌面无法连接。x11vnc 日志："
    tail -n 30 "${'$'}RUNDIR/x11vnc.log" 2>/dev/null
    return 3
  fi
  start_audio
}

browser() {
  PREF=""
  [ -f /workspace/.liquidhub/browser ] && PREF=$(cat /workspace/.liquidhub/browser 2>/dev/null)
  BIN=""
  if [ -n "${'$'}PREF" ] && command -v "${'$'}PREF" >/dev/null 2>&1; then BIN="${'$'}PREF"; fi
  if [ -z "${'$'}BIN" ]; then
    for c in firefox firefox-esr chromium chromium-browser epiphany epiphany-browser falkon; do
      if command -v "${'$'}c" >/dev/null 2>&1; then BIN="${'$'}c"; break; fi
    done
  fi
  if [ -z "${'$'}BIN" ]; then log "ERROR: no browser installed"; return 3; fi
  if pgrep -f "${'$'}BIN" >/dev/null 2>&1; then
    "${'$'}BIN" "${'$'}{1:-about:blank}" >/dev/null 2>&1
    log "BROWSER_NAVIGATED"
    return 0
  fi
  case "${'$'}BIN" in
    chromium|chromium-browser)
      "${'$'}BIN" --no-sandbox --disable-dev-shm-usage --disable-gpu --no-first-run \
        --user-data-dir="${'$'}HOME/.chromium" --window-size=1280,720 --window-position=0,0 \
        "${'$'}{1:-about:blank}" >"${'$'}RUNDIR/browser.log" 2>&1 & ;;
    *)
      "${'$'}BIN" "${'$'}{1:-about:blank}" >"${'$'}RUNDIR/browser.log" 2>&1 & ;;
  esac
  echo ${'$'}! > "${'$'}RUNDIR/chromium.pid"
  log "BROWSER_STARTED"
}

stop() {
  for p in websockify x11vnc fluxbox chromium xvfb; do
    if [ -f "${'$'}RUNDIR/${'$'}p.pid" ]; then
      kill "${'$'}(cat "${'$'}RUNDIR/${'$'}p.pid")" 2>/dev/null
      rm -f "${'$'}RUNDIR/${'$'}p.pid"
    fi
  done
  pkill -f websockify 2>/dev/null
  pkill -f x11vnc 2>/dev/null
  pkill -f fluxbox 2>/dev/null
  pkill -f Xvfb 2>/dev/null
  log "STOPPED"
}

status() {
  if is_x_running; then echo "X_RUNNING"; else echo "X_STOPPED"; fi
  if is_vnc_running; then echo "VNC_RUNNING"; else echo "VNC_STOPPED"; fi
}

logs() {
  echo "== VNC 状态 =="
  if is_vnc_running; then echo "x11vnc: RUNNING"; else echo "x11vnc: STOPPED"; fi
  if is_x_running; then echo "Xvfb: RUNNING"; else echo "Xvfb: STOPPED"; fi
  echo "== 端口 5900 =="
  if [ -r /proc/net/tcp ] && grep -qi ":170C" /proc/net/tcp 2>/dev/null; then
    echo "5900 LISTENING"
  else
    echo "5900 NOT listening"
  fi
  echo "== 相关进程 =="
  found=0
  for d in /proc/[0-9]*; do
    c=$(cat "${'$'}d/comm" 2>/dev/null)
    case "${'$'}c" in
      Xvfb|x11vnc|fluxbox) echo "${'$'}c pid ${'$'}{d#/proc/}"; found=1 ;;
    esac
  done
  [ "${'$'}found" -eq 0 ] && echo "(无 Xvfb/x11vnc/fluxbox 进程)"
  for f in xvfb fluxbox x11vnc browser; do
    if [ -f "${'$'}RUNDIR/${'$'}f.log" ]; then
      echo "==== ${'$'}f.log ===="
      tail -n 40 "${'$'}RUNDIR/${'$'}f.log"
    fi
  done
}

start_audio() {
  command -v pulseaudio >/dev/null 2>&1 || { log "pulseaudio 未安装，跳过音频"; return 0; }
  if [ -f /workspace/.liquidhub/audio ] && [ "${'$'}(cat /workspace/.liquidhub/audio 2>/dev/null)" = "0" ]; then
    log "音频已被用户关闭"; return 0
  fi
  export PULSE_RUNTIME_PATH="${'$'}RUNDIR/pulse"
  mkdir -p "${'$'}PULSE_RUNTIME_PATH"
  pulseaudio --start --exit-idle-time=-1 --disallow-exit >"${'$'}RUNDIR/pulse.log" 2>&1 || true
  sleep 1
  pactl load-module module-null-sink sink_name=liquidhub sink_properties=device.description=LiquidHub >/dev/null 2>&1 || true
  pactl set-default-sink liquidhub >/dev/null 2>&1 || true
  M="${'$'}(pactl load-module module-simple-protocol-tcp port=4713 source=liquidhub.monitor format=s16le rate=44100 channels=2 2>/dev/null)"
  if [ -n "${'$'}M" ]; then
    log "audio: PulseAudio ready on 4713"
  else
    log "WARN: 音频服务未就绪（见 pulse.log）"
    tail -n 15 "${'$'}RUNDIR/pulse.log" 2>/dev/null
  fi
}

case "${'$'}{1:-status}" in
  install) install_pkgs ;;
  reinstall) FORCE_INSTALL=1; stop; install_pkgs ;;
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 1; start ;;
  status) status ;;
  logs) logs ;;
  browser) browser "${'$'}{2:-about:blank}" ;;
  *) log "usage: ${'$'}0 {install|reinstall|start|stop|restart|status|logs|browser [url]}"; exit 1 ;;
esac
"""
