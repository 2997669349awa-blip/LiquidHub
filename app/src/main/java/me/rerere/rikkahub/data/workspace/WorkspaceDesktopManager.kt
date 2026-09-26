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

install_pkgs() {
  if command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1 \
     && command -v fluxbox >/dev/null 2>&1 && command -v websockify >/dev/null 2>&1 \
     && command -v xdotool >/dev/null 2>&1 \
     && { command -v scrot >/dev/null 2>&1 || command -v import >/dev/null 2>&1; } \
     && find_novnc; then
    log "desktop already installed, skipping"
    return 0
  fi
  RC=0
  if command -v apk >/dev/null 2>&1; then
    log "Alpine: installing desktop packages"
    apk add --no-cache --allow-untrusted ca-certificates xvfb x11vnc fluxbox chromium novnc websockify xdotool imagemagick scrot dbus \
      font-noto font-noto-cjk bash coreutils || RC=1
  elif command -v apt-get >/dev/null 2>&1; then
    log "Debian/Ubuntu: installing desktop packages"
    export DEBIAN_FRONTEND=noninteractive
    # 上一次安装若被中断，apt/dpkg 进程会残留在 rootfs 里一直占着 dpkg 锁，
    # 导致后续安装一律 "Could not get lock"。先杀掉残留进程并清掉锁文件。
    for d in /proc/[0-9]*; do
      c=$(cat "${'$'}d/comm" 2>/dev/null)
      case "${'$'}c" in
        apt-get|apt|dpkg|unattended-upgrade) kill -9 "${'$'}{d#/proc/}" 2>/dev/null ;;
      esac
    done
    sleep 1
    rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock /var/lib/apt/lists/lock 2>/dev/null
    dpkg --configure -a >/dev/null 2>&1 || true
    apt-get update -y -o Acquire::Retries=3 || RC=1
    apt-get install -y --no-install-recommends ca-certificates xvfb x11vnc fluxbox xdotool imagemagick scrot \
      dbus-x11 novnc websockify fonts-noto-cjk || RC=1
    # Ubuntu arm64 的 chromium 只有 snap 包，apt 装不上；依次退回可用的轻量浏览器
    if ! command -v chromium >/dev/null 2>&1 && ! command -v chromium-browser >/dev/null 2>&1; then
      apt-get install -y --no-install-recommends chromium 2>/dev/null \
        || apt-get install -y --no-install-recommends chromium-browser 2>/dev/null \
        || apt-get install -y --no-install-recommends epiphany-browser 2>/dev/null \
        || apt-get install -y --no-install-recommends falkon 2>/dev/null \
        || log "WARN: no chromium/epiphany/falkon available in this distro"
    fi
    apt-get clean
    rm -rf /var/lib/apt/lists/* 2>/dev/null
  elif command -v pacman >/dev/null 2>&1; then
    log "Arch: installing desktop packages"
    # Arch 的包用 GPG 签名，老 rootfs 的 keyring 过期会报 key expired / unknown trust
    pacman-key --init >/dev/null 2>&1 || true
    pacman-key --populate archlinuxarm >/dev/null 2>&1 || pacman-key --populate archlinux >/dev/null 2>&1 || true
    pacman -Sy --noconfirm --needed archlinux-keyring >/dev/null 2>&1 || true
    pacman -Sy --noconfirm --needed ca-certificates xorg-server-xvfb x11vnc fluxbox chromium novnc websockify xdotool \
      imagemagick scrot dbus noto-fonts || RC=1
    pacman -Scc --noconfirm 2>/dev/null
  else
    log "ERROR: unsupported package manager"
    return 1
  fi
  # 刷新根证书：老 rootfs 的 CA 缺失/过期会导致 HTTPS (如 Chromium 浏览网页) 失败
  command -v update-ca-certificates >/dev/null 2>&1 && update-ca-certificates 2>/dev/null || true
  command -v update-ca-trust >/dev/null 2>&1 && update-ca-trust 2>/dev/null || true
  command -v trust >/dev/null 2>&1 && trust extract-compat 2>/dev/null || true
  if [ "${'$'}RC" -ne 0 ]; then
    log "ERROR: desktop install failed (see the apt/apk/pacman messages above)"
    return 1
  fi
  log "install done"
}

is_running() {
  # 以 X 服务存活为准，而不是只看 websockify：noVNC 缺失时 websockify 不启动，
  # 只看 websockify 会误报 STOPPED 并重复拉起 Xvfb/x11vnc
  for p in x11vnc xvfb; do
    if [ -f "${'$'}RUNDIR/${'$'}p.pid" ] && kill -0 "${'$'}(cat "${'$'}RUNDIR/${'$'}p.pid")" 2>/dev/null; then
      return 0
    fi
  done
  return 1
}

start() {
  if is_running; then log "already running"; return 0; fi
  if ! command -v Xvfb >/dev/null 2>&1; then
    log "ERROR: desktop environment not installed, run install first"
    return 2
  fi
  rm -rf /tmp/.X11-unix/X1 2>/dev/null
  log "starting Xvfb"
  Xvfb "${'$'}DISP" -screen 0 1280x720x24 -nolisten tcp >"${'$'}RUNDIR/xvfb.log" 2>&1 &
  echo ${'$'}! > "${'$'}RUNDIR/xvfb.pid"
  sleep 1
  log "starting fluxbox"
  fluxbox >"${'$'}RUNDIR/fluxbox.log" 2>&1 &
  echo ${'$'}! > "${'$'}RUNDIR/fluxbox.pid"
  log "starting x11vnc"
  x11vnc -display "${'$'}DISP" -forever -shared -localhost -rfbport "${'$'}VNC_PORT" -nopw -quiet \
    >"${'$'}RUNDIR/x11vnc.log" 2>&1 &
  echo ${'$'}! > "${'$'}RUNDIR/x11vnc.pid"
  sleep 1
  if find_novnc; then
    log "starting websockify (noVNC on 127.0.0.1:${'$'}WEB_PORT)"
    websockify --web="${'$'}NOVNC_DIR" "127.0.0.1:${'$'}WEB_PORT" "127.0.0.1:${'$'}VNC_PORT" \
      >"${'$'}RUNDIR/websockify.log" 2>&1 &
    echo ${'$'}! > "${'$'}RUNDIR/websockify.pid"
  else
    log "WARN: noVNC web directory not found, VNC only on port ${'$'}VNC_PORT"
  fi
  sleep 1
  if is_running; then log "STARTED"; else log "FAILED: websockify did not stay alive"; fi
}

browser() {
  BIN=""
  for c in chromium chromium-browser epiphany epiphany-browser falkon firefox firefox-esr; do
    if command -v "${'$'}c" >/dev/null 2>&1; then BIN="${'$'}c"; break; fi
  done
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
  if is_running; then log "RUNNING"; else log "STOPPED"; fi
}

case "${'$'}{1:-status}" in
  install) install_pkgs ;;
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 1; start ;;
  status) status ;;
  browser) browser "${'$'}{2:-about:blank}" ;;
  *) log "usage: ${'$'}0 {install|start|stop|restart|status|browser [url]}"; exit 1 ;;
esac
"""
