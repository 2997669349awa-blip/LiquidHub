// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 实验性：把工作区桌面的声音通过 PulseAudio simple protocol 拉到手机播放。

package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 连接 rootfs 内 PulseAudio 的 simple protocol 服务（127.0.0.1:4713，s16le/44100/立体声），
 * 用 AudioTrack 直接播放。音质为原始 PCM，但依赖 rootfs 里的 pulseaudio 正常运行。
 */
class PulseAudioPlayer {
    fun interface Listener {
        fun onState(state: String)
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    fun start(host: String = "127.0.0.1", port: Int = 4713) {
        if (running) return
        running = true
        thread = Thread({
            var attempt = 0
            while (running) {
                var socket: Socket? = null
                var input: InputStream? = null
                try {
                    socket = Socket().apply { connect(InetSocketAddress(host, port), 4000) }
                    input = socket.getInputStream()
                    attempt = 0
                    val sampleRate = 44100
                    val minBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val bufferSize = if (minBuf > 0) minBuf * 2 else 16384
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    track = t
                    t.play()
                    post("音频已连接")
                    val buf = ByteArray(4096)
                    while (running) {
                        val n = input.read(buf)
                        if (n < 0) break
                        t.write(buf, 0, n)
                    }
                } catch (e: Throwable) {
                    if (!running) break
                    attempt++
                    post("音频连接失败(第 $attempt 次)：${e.message ?: e::class.java.simpleName}")
                    runCatching { Thread.sleep(2000) }
                } finally {
                    runCatching { input?.close() }
                    runCatching { socket?.close() }
                    runCatching { track?.stop() }
                    runCatching { track?.release() }
                    track = null
                }
            }
        }, "pulse-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        post("音频已停止")
    }

    private fun post(state: String) {
        main.post { listener?.onState(state) }
    }
}
