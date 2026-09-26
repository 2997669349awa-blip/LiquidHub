// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Adds an optional in-workspace remote desktop (Fluxbox + Chromium + VNC + noVNC).

package me.rerere.rikkahub.data.workspace

import android.content.Context
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
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

    /** Writes the helper script into the workspace files area (bind mounted at /workspace). */
    suspend fun ensureScript(workspaceId: String) {
        workspaceRepository.writeText(workspaceId, SCRIPT_PATH, DESKTOP_SCRIPT, overwrite = true)
    }

    suspend fun runAction(workspaceId: String, action: String) {
        ensureScript(workspaceId)
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        val session = withContext(Dispatchers.Main.immediate) {
            sessionFor(workspace.root, workspace.shellCompatibilityMode)
        }
        val bytes = "sh /workspace/$SCRIPT_PATH $action\n".toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    fun closeWorkspace(root: String) {
        sessions.remove(root)?.finishIfRunning()
    }

    private fun sessionFor(root: String, shellCompatibilityMode: Boolean): TerminalSession {
        val existing = sessions[root]
        if (existing != null && existing.isRunning) return existing
        // 会话已退出时重建，否则后续写入会落到一个已结束的 session 上而静默失效
        existing?.finishIfRunning()
        return createWorkspaceTerminalSession(
            context = appContext,
            root = root,
            client = WorkspaceTerminalSessionClient(appContext, onTitleUpdated = {}, onFinished = {}),
            shellCompatibilityMode = shellCompatibilityMode,
        ).also { sessions[root] = it }
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
  if command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1 && find_novnc; then
    log "desktop already installed, skipping"
    return 0
  fi
  if command -v apk >/dev/null 2>&1; then
    log "Alpine: installing desktop packages"
    apk add --no-cache xvfb x11vnc fluxbox chromium novnc websockify xdotool imagemagick scrot dbus \
      font-noto font-noto-cjk bash coreutils
  elif command -v apt-get >/dev/null 2>&1; then
    log "Debian/Ubuntu: installing desktop packages"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y -o Acquire::Retries=3
    apt-get install -y --no-install-recommends xvfb x11vnc fluxbox xdotool imagemagick scrot \
      dbus-x11 novnc websockify fonts-noto-cjk
    apt-get install -y --no-install-recommends chromium 2>/dev/null \
      || apt-get install -y --no-install-recommends chromium-browser 2>/dev/null \
      || log "WARN: chromium unavailable in this distro, install a browser manually"
    apt-get clean
    rm -rf /var/lib/apt/lists/* 2>/dev/null
  elif command -v pacman >/dev/null 2>&1; then
    log "Arch: installing desktop packages"
    pacman -Sy --noconfirm --needed xorg-server-xvfb x11vnc fluxbox chromium novnc websockify xdotool \
      imagemagick scrot dbus noto-fonts
    pacman -Scc --noconfirm 2>/dev/null
  else
    log "ERROR: unsupported package manager"
    return 1
  fi
  log "install done"
}

is_running() {
  [ -f "${'$'}RUNDIR/websockify.pid" ] || return 1
  kill -0 "${'$'}(cat "${'$'}RUNDIR/websockify.pid")" 2>/dev/null
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
  if command -v chromium >/dev/null 2>&1; then BIN=chromium
  elif command -v chromium-browser >/dev/null 2>&1; then BIN=chromium-browser
  fi
  if [ -z "${'$'}BIN" ]; then log "ERROR: chromium not installed"; return 3; fi
  if pgrep -f "${'$'}BIN" >/dev/null 2>&1; then
    "${'$'}BIN" "${'$'}{1:-about:blank}" >/dev/null 2>&1
    log "BROWSER_NAVIGATED"
    return 0
  fi
  "${'$'}BIN" --no-sandbox --disable-dev-shm-usage --disable-gpu --no-first-run \
    --user-data-dir="${'$'}HOME/.chromium" --window-size=1280,720 --window-position=0,0 \
    "${'$'}{1:-about:blank}" >"${'$'}RUNDIR/chromium.log" 2>&1 &
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
