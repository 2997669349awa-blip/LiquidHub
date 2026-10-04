// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Adds an optional in-workspace remote desktop (Fluxbox + Chromium + VNC + noVNC).

package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Writes the helper script into the workspace files area (bind mounted at /workspace). */
    suspend fun ensureScript(workspaceId: String) {
        workspaceRepository.writeText(workspaceId, SCRIPT_PATH, DESKTOP_SCRIPT, overwrite = true)
    }

    suspend fun runAction(workspaceId: String, action: String) {
        writeAction(workspaceId, action)
        if (action == "install" || action == "reinstall") {
            DesktopInstallNotifier.start(appContext, action == "reinstall")
            scope.launch { pollInstall(workspaceId) }
        }
    }

    /** 安装/重装桌面并等待完成（AI 的 desktop_start 用），同时更新通知栏进度。 */
    suspend fun installAndWait(workspaceId: String, reinstall: Boolean = false): Boolean {
        writeAction(workspaceId, if (reinstall) "reinstall" else "install")
        DesktopInstallNotifier.start(appContext, reinstall)
        return pollInstall(workspaceId)
    }

    private suspend fun writeAction(workspaceId: String, action: String) {
        ensureScript(workspaceId)
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        // 会话创建 + updateSize()（fork/exec proot）都放在有 Looper 的后台线程上
        val session = withContext(sessionDispatcher) {
            sessionFor(workspace.root, workspace.shellCompatibilityMode)
        }
        // 会话没起来时 write() 会被静默丢弃，这里显式报错，避免桌面 start 误报 ok
        if (session.emulator == null || !session.isRunning) {
            error("Desktop session failed to start (workspace shell not running). Check the rootfs / shell status.")
        }
        val cmd = "mkdir -p /workspace/.liquidhub/logs; " +
            "sh /workspace/$SCRIPT_PATH $action > /workspace/.liquidhub/logs/last.log 2>&1; " +
            "echo \"__DONE__\" >> /workspace/.liquidhub/logs/last.log; " +
            "cat /workspace/.liquidhub/logs/last.log\n"
        val bytes = cmd.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private suspend fun pollInstall(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        val hostDir = workspaceRepository.workspaceHostDir(workspaceId)
        if (hostDir == null) {
            DesktopInstallNotifier.fail(appContext, "找不到工作区目录")
            return@withContext false
        }
        val logFile = java.io.File(hostDir, "$LOG_DIR/last.log")
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        var lastLine = ""
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val text = runCatching { logFile.readText() }.getOrNull().orEmpty()
            val tail = text.lineSequence().lastOrNull { it.isNotBlank() }?.take(140).orEmpty()
            if (tail.isNotBlank() && tail != lastLine && tail != "__DONE__") {
                lastLine = tail
                DesktopInstallNotifier.progress(appContext, tail)
            }
            when {
                text.contains("install done") -> {
                    DesktopInstallNotifier.success(appContext)
                    return@withContext true
                }

                text.contains("desktop install failed") || text.contains("ERROR:") -> {
                    DesktopInstallNotifier.fail(appContext, tail)
                    return@withContext false
                }

                text.contains("__DONE__") -> {
                    DesktopInstallNotifier.fail(appContext, tail.ifBlank { "安装结束但未完成" })
                    return@withContext false
                }
            }
        }
        DesktopInstallNotifier.fail(appContext, "安装超时")
        false
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

        /** 日志目录（相对工作区 /workspace），宿主机可直接读取 */
        const val LOG_DIR = ".liquidhub/logs"

        /** VNC Unix socket 名（相对工作区 /workspace）：容器内 /workspace/.vnc，宿主机 <files>/.vnc */
        const val VNC_SOCKET_NAME = ".vnc"
    }
}

private const val DESKTOP_SCRIPT = """
#!/bin/sh
# LiquidHub workspace desktop helper: install / start / stop / status / browser
set -u

DISP=":1"
VNC_PORT=5900
WEB_PORT=6080
# 日志与运行时文件放在 /workspace（宿主机 = App 的 files/ 目录）下：
# 1) 持久保留，不会随 proot 退出或下一次 start 被清空；
# 2) App 可直接读取文件，启动过程中也能实时刷新，无需再起一个 proot。
LOGDIR=/workspace/.liquidhub/logs
RUNDIR="${'$'}LOGDIR"
VNC_SOCK=/tmp/.liquidhub-vnc
NOVNC_DIR=""
FORCE_INSTALL=0

export DISPLAY="${'$'}DISP"
export HOME="${'$'}{HOME:-/root}"
export XDG_RUNTIME_DIR="${'$'}RUNDIR/xdg"
# 声音：桌面应用把音频发到 Termux 侧的 PulseAudio（终端执行 likkahub sound 启动）
if ! { [ -f /workspace/.liquidhub/audio ] && [ "${'$'}(cat /workspace/.liquidhub/audio 2>/dev/null)" = "0" ]; }; then
  export PULSE_SERVER=tcp:127.0.0.1:4713
fi
mkdir -p "${'$'}RUNDIR" "${'$'}XDG_RUNTIME_DIR" 2>/dev/null
chmod 700 "${'$'}XDG_RUNTIME_DIR" 2>/dev/null

# 同时写终端与持久日志；带时间戳
log() {
  TS=${'$'}(date '+%H:%M:%S' 2>/dev/null || true)
  LINE="[desktop ${'$'}TS] ${'$'}*"
  echo "${'$'}LINE"
  echo "${'$'}LINE" >> "${'$'}LOGDIR/desktop.log" 2>/dev/null
}

# 单个服务日志过大时轮转一次，避免长期运行把工作区撑爆
rotate_log() {
  F="${'$'}1"
  [ -f "${'$'}F" ] || return 0
  SZ=${'$'}(wc -c < "${'$'}F" 2>/dev/null || echo 0)
  [ "${'$'}SZ" -gt 1048576 ] 2>/dev/null && mv -f "${'$'}F" "${'$'}F.1" 2>/dev/null
  return 0
}

# 在 tuna / aliyun / iscas 三个国内镜像之间切换，应对单镜像故障/限速。
# /etc/hosts 里已预置这三个域名的 IP（App 侧解析），所以切换后无需依赖 proot 里脆弱的 DNS。
switch_mirror() {
  NEW="${'$'}1"
  [ -n "${'$'}NEW" ] || return 0
  for f in /etc/apk/repositories /etc/apt/sources.list /etc/apt/sources.list.d/*.sources \
           /etc/apt/sources.list.d/*.list /etc/pacman.d/mirrorlist; do
    [ -f "${'$'}f" ] || continue
    sed -i "s#mirrors\.tuna\.tsinghua\.edu\.cn#${'$'}{NEW}#g; s#mirrors\.aliyun\.com#${'$'}{NEW}#g; s#mirror\.iscas\.ac\.cn#${'$'}{NEW}#g" "${'$'}f" 2>/dev/null
  done
  log "已切换镜像源 -> ${'$'}NEW"
}

mirror_for_attempt() {
  case "$((${'$'}{1} % 3))" in
    0) echo "mirrors.tuna.tsinghua.edu.cn" ;;
    1) echo "mirrors.aliyun.com" ;;
    *) echo "mirror.iscas.ac.cn" ;;
  esac
}

# apt：更新索引 / 安装，失败就切镜像重试（最多 3 次），并加超时避免卡死。
apt_do() {
  ACTION="${'$'}1"; shift
  n=0
  while [ "${'$'}n" -lt 3 ]; do
    if [ "${'$'}ACTION" = "update" ]; then
      if apt-get update -y -o Acquire::Retries=5 -o Acquire::ForceIPv4=true \
          -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30; then return 0; fi
    else
      if DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --fix-missing \
          -o Acquire::Retries=5 -o Acquire::ForceIPv4=true \
          -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 "${'$'}@"; then return 0; fi
    fi
    n=${'$'}((n+1))
    switch_mirror "${'$'}(mirror_for_attempt ${'$'}n)"
    sleep 2
  done
  return 1
}

# apk：更新索引并安装，失败就切镜像重试（最多 3 次）。
apk_do() {
  n=0
  while [ "${'$'}n" -lt 3 ]; do
    apk update >/dev/null 2>&1 || true
    if apk add --no-cache --allow-untrusted "${'$'}@"; then return 0; fi
    n=${'$'}((n+1))
    switch_mirror "${'$'}(mirror_for_attempt ${'$'}n)"
    sleep 2
  done
  return 1
}

# 确保 TigerVNC 的 Xvnc 可用（VNC 走 Unix socket 需要它）。best-effort，失败返回非 0。
# 失败后写标记，避免每次 start 都反复 apt/apk；重装时清除标记。
ensure_xvnc() {
  command -v Xvnc >/dev/null 2>&1 && return 0
  if [ -f /workspace/.liquidhub/xvnc_unavailable ]; then
    return 1
  fi
  log "TigerVNC 未安装，尝试安装（用于 Unix socket VNC）"
  if command -v apk >/dev/null 2>&1; then
    apk_do tigervnc >/dev/null 2>&1 || true
  elif command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt_do update >/dev/null 2>&1 || true
    apt_do install tigervnc-standalone-server >/dev/null 2>&1 || true
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm --needed tigervnc 2>/dev/null || true
  fi
  if command -v Xvnc >/dev/null 2>&1; then
    rm -f /workspace/.liquidhub/xvnc_unavailable
    return 0
  fi
  touch /workspace/.liquidhub/xvnc_unavailable 2>/dev/null
  return 1
}

find_novnc() {
  for d in /usr/share/novnc /usr/share/webapps/novnc /usr/local/share/novnc; do
    if [ -d "${'$'}d" ]; then NOVNC_DIR="${'$'}d"; return 0; fi
  done
  return 1
}

read_tier() {
  T=$(cat /workspace/.liquidhub/tier 2>/dev/null)
  [ -z "${'$'}T" ] && T=normal
  echo "${'$'}T"
}

install_browser() {
  TIER=$(read_tier)
  if [ "${'$'}TIER" = "mini" ]; then log "tier=mini：跳过浏览器"; return 0; fi
  log "installing browser (tier ${'$'}TIER)"
  # 默认火狐；chromium 在部分发行版是 snap 空壳，放到最后兜底
  if command -v apk >/dev/null 2>&1; then
    apk_do firefox 2>/dev/null || apk_do chromium 2>/dev/null || true
  elif command -v apt-get >/dev/null 2>&1; then
    apt_do install firefox-esr 2>/dev/null \
      || apt_do install firefox 2>/dev/null \
      || apt_do install epiphany-browser 2>/dev/null \
      || apt_do install falkon 2>/dev/null \
      || apt_do install chromium 2>/dev/null \
      || apt_do install chromium-browser 2>/dev/null \
      || log "WARN: 没有可用的浏览器"
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm --needed firefox 2>/dev/null \
      || pacman -Sy --noconfirm --needed chromium 2>/dev/null || true
  fi
}

install_pkgs() {
  TIER=$(read_tier)
  log "system tier: ${'$'}TIER"
  log "disk:"
  df -h / 2>/dev/null
  AVAIL=$(df -Pk / 2>/dev/null | awk 'NR==2{print $4}')
  if [ -n "${'$'}AVAIL" ] && [ "${'$'}AVAIL" -lt 500000 ]; then
    log "ERROR: 磁盘空间不足（剩余约 ${'$'}((AVAIL/1024)) MB），无法安装桌面，请清理空间后重试"
    return 1
  fi
  export TIER
  if [ "${'$'}{FORCE_INSTALL:-0}" != "1" ] \
     && command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1 \
     && command -v fluxbox >/dev/null 2>&1 \
     && command -v xdotool >/dev/null 2>&1 \
     && command -v Xvnc >/dev/null 2>&1; then
    log "desktop already installed, skipping"
    return 0
  fi
  RC=0
  rm -f /workspace/.liquidhub/xvnc_unavailable 2>/dev/null
  if command -v apk >/dev/null 2>&1; then
    log "Alpine(apk): installing desktop packages"
    apk_do ca-certificates xvfb x11vnc fluxbox openbox xdotool bash coreutils || RC=1
    if [ "${'$'}TIER" != "mini" ]; then
      apk_do xterm scrot font-noto-cjk || log "WARN: 部分可选组件安装失败（不影响桌面）"
    fi
  elif command -v apt-get >/dev/null 2>&1; then
    log "Debian/Ubuntu(apt): installing desktop packages"
    export DEBIAN_FRONTEND=noninteractive
    ensure_universe
    getent hosts mirrors.tuna.tsinghua.edu.cn >/dev/null 2>&1 || log "WARN: 无法解析镜像域名，DNS 可能有问题"
    apt_do update || log "WARN: apt-get update 失败，继续尝试"
    apt-cache policy xvfb x11vnc fluxbox xdotool 2>/dev/null | head -n 30
    if ! apt_do install ca-certificates xvfb x11vnc fluxbox openbox xdotool; then
      log "核心包整体安装失败，逐包重试定位："
      for p in ca-certificates xvfb x11vnc fluxbox openbox xdotool; do
        apt_do install "${'$'}p" || echo "[desktop]   FAIL ${'$'}p"
      done
    fi
    for p in Xvfb x11vnc fluxbox xdotool; do
      command -v "${'$'}p" >/dev/null 2>&1 || RC=1
    done
    if [ "${'$'}TIER" != "mini" ]; then
      apt_do install xterm scrot fonts-noto-cjk || log "WARN: 部分可选组件安装失败（不影响桌面）"
    fi
  elif command -v pacman >/dev/null 2>&1; then
    log "Arch(pacman): installing desktop packages"
    pacman-key --init >/dev/null 2>&1 || true
    pacman-key --populate archlinuxarm >/dev/null 2>&1 || pacman-key --populate archlinux >/dev/null 2>&1 || true
    pacman -Sy --noconfirm --needed archlinux-keyring >/dev/null 2>&1 || true
    pacman -Sy --noconfirm --needed ca-certificates xorg-server-xvfb x11vnc fluxbox openbox xdotool || RC=1
    if [ "${'$'}TIER" != "mini" ]; then
      pacman -Sy --noconfirm --needed xterm scrot noto-fonts \
        || log "WARN: 部分可选组件安装失败（不影响桌面）"
    fi
  else
    log "ERROR: unsupported package manager"
    return 1
  fi
  # TigerVNC(Xvnc)：VNC 走 Unix socket 需要它（同 tiny_container，App 用 LocalSocket 连接）；
  # 缺失时 start 会自动回退到 Xvfb + x11vnc(TCP)。best-effort，避免整体安装失败。
  if ensure_xvnc; then
    log "Xvnc ready"
  else
    log "WARN: Xvnc 不可用，桌面将回退 Xvfb+x11vnc(TCP)"
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
  for b in Xvfb x11vnc fluxbox xdotool Xvnc; do
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

is_x_running() { pid_alive "${'$'}RUNDIR/xvnc.pid" || pid_alive "${'$'}RUNDIR/xvfb.pid"; }
is_vnc_running() { pid_alive "${'$'}RUNDIR/xvnc.pid" || pid_alive "${'$'}RUNDIR/x11vnc.pid"; }

# 运行与否以 VNC 服务（Xvnc 或 x11vnc）为准
is_running() { is_vnc_running; }

setup_theme() {
  mkdir -p "${'$'}HOME/.fluxbox" 2>/dev/null
  # 渐变壁纸
  if command -v convert >/dev/null 2>&1; then
    convert -size 1280x720 gradient:'#0f172a'-'#1d4ed8' "${'$'}RUNDIR/wallpaper.png" 2>/dev/null
  fi
  # Fluxbox 配置：显示底部工具栏（工作区 + 时钟），作为美化基础
  {
    cat <<'FBEOF'
session.screen0.toolbar.visible: true
session.screen0.toolbar.autoHide: false
session.screen0.toolbar.placement: BottomCenter
session.screen0.toolbar.widthPercent: 100
session.screen0.toolbar.alpha: 200
session.screen0.toolbar.tools: prevworkspace, workspacename, nextworkspace, iconbar, systemtray
session.screen0.strftimeFormat: %H:%M
session.screen0.workspaces: 1
session.screen0.focusModel: ClickToFocus
FBEOF
    # 显式指向我们自己的 keys/menu，避免 fluxbox 去读系统默认（会刷 Invalid key/modifier）
    echo "session.keyFile: ${'$'}HOME/.fluxbox/keys"
    echo "session.menuFile: ${'$'}HOME/.fluxbox/menu"
  } > "${'$'}HOME/.fluxbox/init"
  # 桌面右键菜单（让桌面不是“空气”）
  cat > "${'$'}HOME/.fluxbox/menu" <<'FBMENU'
[begin] (LiquidHub)
  [exec] (终端 Terminal) { xterm }
  [exec] (浏览器 Browser) { firefox }
  [separator]
  [restart] (重新加载)
  [exit] (退出)
[end]
FBMENU
  # 自带 ~/.fluxbox/keys 的鼠标绑定在部分版本会刷 "Invalid key/modifier" 告警，
  # 这里用一份最小且兼容的绑定覆盖它。
  cat > "${'$'}HOME/.fluxbox/keys" <<'FBKEYS'
OnDesktop Mouse1 :HideMenus
OnDesktop Mouse2 :WorkspaceMenu
OnDesktop Mouse3 :RootMenu
OnTitlebar Mouse1 :Raise
OnTitlebar Mouse3 :WindowMenu
FBKEYS
}

start() {
  if is_running; then log "already running"; return 0; fi
  RES=$(cat /workspace/.liquidhub/resolution 2>/dev/null)
  [ -z "${'$'}RES" ] && RES=1280x720
  pkill -f "Xvnc ${'$'}DISP" 2>/dev/null
  pkill -f "Xvfb ${'$'}DISP" 2>/dev/null
  pkill -f "x11vnc" 2>/dev/null
  rm -f "${'$'}RUNDIR/xvnc.pid" "${'$'}RUNDIR/xvfb.pid" "${'$'}RUNDIR/x11vnc.pid" /tmp/.X11-unix/X1 "${'$'}VNC_SOCK" 2>/dev/null

  # 优先 TigerVNC 的 Xvnc：X 服务器 + VNC 服务器一体，VNC 监听 Unix socket（同 tiny_container，
  # 绕过 TCP 网络栈，App 侧用 LocalSocket 连接）。缺失或启动失败时回退 Xvfb + x11vnc(TCP)。
  USE_XVNC=0
  ensure_xvnc >/dev/null 2>&1 || true
  if command -v Xvnc >/dev/null 2>&1; then
    log "starting Xvnc (TigerVNC) ${'$'}DISP, unix socket ${'$'}VNC_SOCK"
    rotate_log "${'$'}RUNDIR/xvnc.log"
    echo "===== $(date '+%F %T' 2>/dev/null) start Xvnc ${'$'}RES =====" >> "${'$'}RUNDIR/xvnc.log" 2>/dev/null
    # -extension MIT-SHM：安卓下 SysV 共享内存不可用，关掉可避免 x11grab 反复报
    # "Could not get shared memory buffer"（ffmpeg 会走普通抓屏路径）
    Xvnc "${'$'}DISP" -geometry "${'$'}RES" -depth 24 -extension MIT-SHM \
      -rfbunixpath "${'$'}VNC_SOCK" -rfbunixmode 700 -rfbport 0 \
      -SecurityTypes None -AlwaysShared -desktop LiquidHub \
      >>"${'$'}RUNDIR/xvnc.log" 2>&1 &
    echo ${'$'}! > "${'$'}RUNDIR/xvnc.pid"
    i=0
    while [ ! -e "${'$'}VNC_SOCK" ] && [ ${'$'}i -lt 30 ]; do sleep 0.5 2>/dev/null || sleep 1; i=${'$'}((i+1)); done
    if is_vnc_running; then
      USE_XVNC=1
      echo unix > /workspace/.liquidhub/vnc_mode
    else
      log "WARN: Xvnc 启动失败，回退 Xvfb+x11vnc。Xvnc 日志："
      tail -n 30 "${'$'}RUNDIR/xvnc.log" 2>/dev/null
      kill "${'$'}(cat "${'$'}RUNDIR/xvnc.pid" 2>/dev/null)" 2>/dev/null
      rm -f "${'$'}RUNDIR/xvnc.pid" "${'$'}VNC_SOCK" 2>/dev/null
    fi
  fi

  if [ "${'$'}USE_XVNC" -ne 1 ]; then
    if ! command -v Xvfb >/dev/null 2>&1; then
      log "ERROR: desktop environment not installed, run install first"
      return 2
    fi
    log "starting Xvfb + x11vnc (TCP fallback)"
    rotate_log "${'$'}RUNDIR/xvfb.log"
    echo "===== $(date '+%F %T' 2>/dev/null) start Xvfb ${'$'}RES =====" >> "${'$'}RUNDIR/xvfb.log" 2>/dev/null
    Xvfb "${'$'}DISP" -screen 0 "${'$'}{RES}x24" -nolisten tcp -ac -extension MIT-SHM +extension XTEST +extension RANDR >>"${'$'}RUNDIR/xvfb.log" 2>&1 &
    echo ${'$'}! > "${'$'}RUNDIR/xvfb.pid"
    i=0
    while [ ! -e /tmp/.X11-unix/X1 ] && [ ${'$'}i -lt 20 ]; do sleep 0.5 2>/dev/null || sleep 1; i=${'$'}((i+1)); done
    if ! is_x_running; then
      log "ERROR: Xvfb 启动失败，日志："
      tail -n 20 "${'$'}RUNDIR/xvfb.log" 2>/dev/null
      return 2
    fi
    echo tcp > /workspace/.liquidhub/vnc_mode
    log "starting x11vnc on ${'$'}VNC_PORT"
    rotate_log "${'$'}RUNDIR/x11vnc.log"
    x11vnc -display "${'$'}DISP" -forever -shared -localhost -rfbport "${'$'}VNC_PORT" \
      -nopw -noshm -quiet >>"${'$'}RUNDIR/x11vnc.log" 2>&1 &
    echo ${'$'}! > "${'$'}RUNDIR/x11vnc.pid"
    i=0
    while [ ${'$'}i -lt 20 ]; do
      is_vnc_running && break
      sleep 0.5 2>/dev/null || sleep 1
      i=${'$'}((i+1))
    done
    if ! is_vnc_running; then
      log "ERROR: x11vnc 启动失败，日志："
      tail -n 30 "${'$'}RUNDIR/x11vnc.log" 2>/dev/null
      return 3
    fi
  fi

  setup_theme
  # 优先 openbox：它的鼠标事件处理稳定，不像 fluxbox 会因 keys 配置吞掉点击
  if command -v openbox >/dev/null 2>&1; then
    log "starting openbox"
    openbox >>"${'$'}RUNDIR/wm.log" 2>&1 &
    echo ${'$'}! > "${'$'}RUNDIR/wm.pid"
  else
    log "starting fluxbox"
    fluxbox >>"${'$'}RUNDIR/wm.log" 2>&1 &
    echo ${'$'}! > "${'$'}RUNDIR/wm.pid"
  fi
  sleep 1
  # 自动开一个终端，桌面不至于空无一物
  if command -v xterm >/dev/null 2>&1; then
    xterm >>"${'$'}RUNDIR/xterm.log" 2>&1 &
  fi
  set_background
  log "STARTED: VNC ready (mode=${'$'}(cat /workspace/.liquidhub/vnc_mode 2>/dev/null || echo unknown))"
}

# Firefox 的内容进程沙箱在 proot 下会崩，写 user.js 关闭沙箱 + 软件渲染
setup_firefox_profile() {
  FF_DIR="${'$'}HOME/.mozilla/firefox"
  mkdir -p "${'$'}FF_DIR"
  PROFILE=""
  for d in "${'$'}FF_DIR"/*.default-release "${'$'}FF_DIR"/*.default "${'$'}FF_DIR"/*.default-esr; do
    [ -d "${'$'}d" ] && { PROFILE="${'$'}d"; break; }
  done
  if [ -z "${'$'}PROFILE" ]; then
    PROFILE="${'$'}FF_DIR/liquidhub.default-release"
    mkdir -p "${'$'}PROFILE"
    cat > "${'$'}FF_DIR/profiles.ini" <<'FFINI'
[Profile0]
Name=default-release
IsRelative=1
Path=liquidhub.default-release
Default=1

[General]
StartWithLastProfile=1
Version=2
FFINI
  fi
  cat > "${'$'}PROFILE/user.js" <<'FFJS'
user_pref("security.sandbox.content.level", 0);
user_pref("security.sandbox.gpu.level", 0);
user_pref("security.sandbox.socket.level", 0);
user_pref("security.sandbox.rdd.level", 0);
user_pref("security.sandbox.utility.level", 0);
user_pref("dom.ipc.processCount", 1);
user_pref("gfx.webrender.software", true);
user_pref("layers.acceleration.disabled", true);
user_pref("media.hardware-video-decoding.enabled", false);
user_pref("toolkit.startup.max_resumed_crashes", -1);
FFJS
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
  echo "${'$'}BIN" > /workspace/.liquidhub/browser
  if pgrep -f "${'$'}BIN" >/dev/null 2>&1; then
    "${'$'}BIN" "${'$'}{1:-about:blank}" >/dev/null 2>&1
    log "BROWSER_NAVIGATED"
    return 0
  fi
  case "${'$'}BIN" in
    firefox*)
      setup_firefox_profile
      MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 MOZ_WEBRENDER=0 \
        "${'$'}BIN" -no-remote "${'$'}{1:-about:blank}" >"${'$'}RUNDIR/browser.log" 2>&1 & ;;
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
  for p in websockify xvnc x11vnc xvfb wm fluxbox openbox xterm chromium ffmpeg; do
    if [ -f "${'$'}RUNDIR/${'$'}p.pid" ]; then
      kill "${'$'}(cat "${'$'}RUNDIR/${'$'}p.pid")" 2>/dev/null
      rm -f "${'$'}RUNDIR/${'$'}p.pid"
    fi
  done
  pkill -f websockify 2>/dev/null
  pkill -f "Xvnc ${'$'}DISP" 2>/dev/null
  pkill -f "Xvfb ${'$'}DISP" 2>/dev/null
  pkill -f x11vnc 2>/dev/null
  pkill -f fluxbox 2>/dev/null
  pkill -f openbox 2>/dev/null
  pkill -f x11grab 2>/dev/null
  rm -f "${'$'}VNC_SOCK" /tmp/.X11-unix/X1 2>/dev/null
  log "STOPPED"
}

status() {
  if is_x_running; then echo "X_RUNNING"; else echo "X_STOPPED"; fi
  if is_vnc_running; then echo "VNC_RUNNING"; else echo "VNC_STOPPED"; fi
  echo "MODE=$(cat /workspace/.liquidhub/vnc_mode 2>/dev/null || echo unknown)"
}

logs() {
  echo "== VNC 状态 =="
  if is_vnc_running; then echo "VNC server: RUNNING"; else echo "VNC server: STOPPED"; fi
  if is_x_running; then echo "X server: RUNNING"; else echo "X server: STOPPED"; fi
  echo "mode: $(cat /workspace/.liquidhub/vnc_mode 2>/dev/null || echo unknown)"
  if [ -e "${'$'}VNC_SOCK" ]; then echo "unix socket: ${'$'}VNC_SOCK (present)"; fi
  echo "== 端口 5900 (TCP 回退模式才会监听) =="
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
      Xvnc|Xvfb|x11vnc|fluxbox|openbox) echo "${'$'}c pid ${'$'}{d#/proc/}"; found=1 ;;
    esac
  done
  [ "${'$'}found" -eq 0 ] && echo "(无 Xvnc/Xvfb/x11vnc/WM 进程)"
  for f in desktop xvnc xvfb x11vnc wm ffmpeg browser; do
    if [ -f "${'$'}RUNDIR/${'$'}f.log" ]; then
      echo "==== ${'$'}f.log (末尾 30 行) ===="
      tail -n 30 "${'$'}RUNDIR/${'$'}f.log"
    fi
  done
  if [ -f "${'$'}RUNDIR/last.log" ]; then
    echo "==== last.log (最近一次动作输出) ===="
    tail -n 50 "${'$'}RUNDIR/last.log"
  fi
}

set_background() {
  # fluxbox 的 bsetroot 最可靠：直接给根窗口设渐变背景，避免被刷成全黑
  if command -v bsetroot >/dev/null 2>&1; then
    bsetroot -gradient northsouth "#1e3a8a" "#0f172a" 2>/dev/null && return 0
  fi
  if [ -f "${'$'}RUNDIR/wallpaper.png" ] && command -v fbsetbg >/dev/null 2>&1; then
    fbsetbg -f "${'$'}RUNDIR/wallpaper.png" 2>/dev/null && return 0
  fi
  if command -v xsetroot >/dev/null 2>&1; then
    xsetroot -solid "#0f172a" 2>/dev/null
  fi
}

ensure_universe() {
  # Ubuntu base 默认只有 main/restricted，而 fluxbox/x11vnc/xterm 都在 universe
  if [ -f /etc/apt/sources.list ]; then
    if ! grep -q universe /etc/apt/sources.list 2>/dev/null; then
      sed -i 's/ main$/ main universe/' /etc/apt/sources.list 2>/dev/null
      sed -i 's/ main restricted$/ main restricted universe multiverse/' /etc/apt/sources.list 2>/dev/null
    fi
  fi
  for f in /etc/apt/sources.list.d/*.sources; do
    [ -f "${'$'}f" ] || continue
    grep -q universe "${'$'}f" 2>/dev/null || sed -i 's/^Components:.*/Components: main restricted universe multiverse/' "${'$'}f" 2>/dev/null
  done
  for f in /etc/apt/sources.list.d/*.list; do
    [ -f "${'$'}f" ] || continue
    grep -q universe "${'$'}f" 2>/dev/null || sed -i 's/ main$/ main universe/' "${'$'}f" 2>/dev/null
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

TS=${'$'}(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || true)
echo "" >> "${'$'}LOGDIR/desktop.log" 2>/dev/null
echo "========== ${'$'}TS · action=${'$'}{1:-status} ==========" >> "${'$'}LOGDIR/desktop.log" 2>/dev/null

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
