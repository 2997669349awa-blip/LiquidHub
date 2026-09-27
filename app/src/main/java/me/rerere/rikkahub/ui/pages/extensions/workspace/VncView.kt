// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// A minimal native RFB (VNC) client so the workspace desktop is rendered directly in-app
// instead of loading noVNC through a WebView.

package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 直连 x11vnc (127.0.0.1:5900) 的原生 VNC 客户端视图。
 * 只实现 RFB 3.8 + 无认证 + Raw 编码，足够显示一个本机桌面。
 */
class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onState(state: String)
        fun onError(message: String)
        fun onFps(fps: Int) {}
    }

    var listener: Listener? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile private var running = false
    @Volatile private var frame: IntArray? = null
    private var copyScratch = IntArray(0)
    private var framesThisSecond = 0
    private var lastFpsAt = 0L

    /** true = 触摸板（相对移动 + 点击），false = 触屏（绝对坐标） */
    var touchpadMode: Boolean = false
    @Volatile private var cursorX = 0
    @Volatile private var cursorY = 0
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false

    private var worker: Thread? = null
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val writeLock = Any()

    fun connect(host: String = "127.0.0.1", port: Int = 5900) {
        disconnect()
        running = true
        worker = Thread({
            var attempt = 0
            while (running) {
                try {
                    runSession(host, port)
                    return@Thread
                } catch (t: Throwable) {
                    if (!running) return@Thread
                    attempt++
                    post { listener?.onError("连接失败(第 $attempt 次)：${t.message ?: t::class.java.simpleName}") }
                    if (attempt >= 15) return@Thread
                    runCatching { Thread.sleep(1500) }
                }
            }
        }, "vnc-client").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        running = false
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null
        out = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        disconnect()
    }

    fun sendText(text: String) {
        for (ch in text) {
            val keysym = when (ch) {
                '\n' -> 0xFF0D
                '\b' -> 0xFF08
                '\t' -> 0xFF09
                else -> ch.code.let { if (it in 32..126) it else 0 }
            }
            if (keysym == 0) continue
            sendKey(keysym, true)
            sendKey(keysym, false)
        }
    }

    private fun sendKey(keysym: Int, down: Boolean) {
        val o = out ?: return
        synchronized(writeLock) {
            runCatching {
                o.writeByte(4)
                o.writeByte(if (down) 1 else 0)
                o.writeByte(0)
                o.writeByte(0)
                o.writeInt(keysym)
                o.flush()
            }
        }
    }

    private fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val o = out ?: return
        synchronized(writeLock) {
            runCatching {
                o.writeByte(5)
                o.writeByte(buttonMask)
                o.writeShort(x)
                o.writeShort(y)
                o.flush()
            }
        }
    }

    /** 在当前光标处左键单击。 */
    fun leftClick() {
        sendPointer(cursorX, cursorY, 1)
        sendPointer(cursorX, cursorY, 0)
    }

    /** 在当前光标处右键单击（VNC button mask 4）。 */
    fun rightClick() {
        sendPointer(cursorX, cursorY, 4)
        sendPointer(cursorX, cursorY, 0)
    }

    private fun runSession(host: String, port: Int) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
            socket = s
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            out = output

            val version = ByteArray(12)
            input.readFully(version)
            output.write("RFB 003.008\n".toByteArray())
            output.flush()

            val numSec = input.readUnsignedByte()
            if (numSec == 0) {
                val reasonLen = input.readInt()
                val reason = ByteArray(reasonLen.coerceIn(0, 4096))
                input.readFully(reason)
                fail("服务器拒绝连接: ${String(reason)}")
            }
            val secTypes = ByteArray(numSec)
            input.readFully(secTypes)
            if (secTypes.none { it.toInt() == 1 }) {
                fail("x11vnc 要求密码，请以无密码模式运行（-nopw）")
            }
            output.writeByte(1)
            output.flush()
            val secResult = input.readInt()
            if (secResult != 0) fail("VNC 认证失败")

            output.writeByte(1) // ClientInit: shared
            output.flush()

            frameWidth = input.readUnsignedShort()
            frameHeight = input.readUnsignedShort()
            input.skipBytes(16) // server pixel format
            val nameLen = input.readInt()
            if (nameLen > 0) input.skipBytes(nameLen)

            // SetPixelFormat: 16bpp RGB565 小端（数据量是 32bpp 的一半，明显更快）
            output.writeByte(0)
            output.writeByte(0); output.writeByte(0); output.writeByte(0)
            output.writeByte(16); output.writeByte(16); output.writeByte(0); output.writeByte(1)
            output.writeShort(31); output.writeShort(63); output.writeShort(31)
            output.writeByte(11); output.writeByte(5); output.writeByte(0)
            output.writeByte(0); output.writeByte(0); output.writeByte(0)
            // SetEncodings: Hextile(5) > CopyRect(1) > Raw(0)
            output.writeByte(2)
            output.writeByte(0)
            output.writeShort(3)
            output.writeInt(5)
            output.writeInt(1)
            output.writeInt(0)
            output.flush()

            val w = frameWidth
            val h = frameHeight
            cursorX = w / 2
            cursorY = h / 2
            sendPointer(cursorX, cursorY, 0)
            val colors = IntArray(w * h)
            frame = colors
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            post {
                listener?.onState("已连接 ${w}x$h")
                invalidate()
            }

            while (running) {
                synchronized(writeLock) {
                    output.writeByte(3) // FramebufferUpdateRequest
                    output.writeByte(1) // incremental
                    output.writeShort(0); output.writeShort(0)
                    output.writeShort(w); output.writeShort(h)
                    output.flush()
                }
                when (val msg = input.readUnsignedByte()) {
                    0 -> {
                        input.skipBytes(1)
                        val rects = input.readUnsignedShort()
                        for (r in 0 until rects) {
                            val x = input.readUnsignedShort()
                            val y = input.readUnsignedShort()
                            val rw = input.readUnsignedShort()
                            val rh = input.readUnsignedShort()
                            val enc = input.readInt()
                            when (enc) {
                                0 -> readRaw(input, colors, w, x, y, rw, rh)
                                1 -> readCopyRect(input, colors, w, x, y, rw, rh)
                                5 -> readHextile(input, colors, w, x, y, rw, rh)
                                else -> fail("不支持的 VNC 编码: $enc")
                            }
                            // 只把变化的矩形写回 Bitmap，避免每次整屏 setPixels 造成卡顿
                            synchronized(bmp) {
                                bmp.setPixels(colors, y * w + x, w, x, y, rw, rh)
                            }
                        }
                        if (rects > 0) {
                            postInvalidateOnAnimation()
                        }
                        framesThisSecond++
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastFpsAt >= 1000) {
                            lastFpsAt = nowMs
                            val fps = framesThisSecond
                            framesThisSecond = 0
                            post { listener?.onFps(fps) }
                        }
                    }
                    1 -> {
                        input.skipBytes(3)
                        val n = input.readUnsignedShort()
                        input.skipBytes(n * 6)
                    }
                    2 -> Unit
                    3 -> {
                        input.skipBytes(3)
                        val len = input.readInt()
                        if (len > 0) input.skipBytes(len)
                    }
                    else -> fail("未知的 VNC 消息: $msg")
                }
                Thread.sleep(16)
            }
        } catch (t: Throwable) {
            // 交给外层 connect() 的重试循环
            if (running) throw t
        } finally {
            runCatching { socket?.close() }
            socket = null
            out = null
        }
    }

    private fun cpixel(input: DataInputStream): Int {
        // 16bpp RGB565 小端
        val lo = input.readUnsignedByte()
        val hi = input.readUnsignedByte()
        val v = lo or (hi shl 8)
        val r = ((v shr 11) and 0x1F) * 255 / 31
        val g = ((v shr 5) and 0x3F) * 255 / 63
        val b = (v and 0x1F) * 255 / 31
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun readRaw(input: DataInputStream, colors: IntArray, stride: Int, x: Int, y: Int, rw: Int, rh: Int) {
        for (yy in 0 until rh) {
            val base = (y + yy) * stride + x
            for (xx in 0 until rw) colors[base + xx] = cpixel(input)
        }
    }

    private fun readCopyRect(input: DataInputStream, colors: IntArray, stride: Int, x: Int, y: Int, rw: Int, rh: Int) {
        val sx = input.readUnsignedShort()
        val sy = input.readUnsignedShort()
        if (sx == x && sy == y) return
        // 复用缓冲，避免每帧分配导致 GC 卡顿
        if (copyScratch.size < rw * rh) copyScratch = IntArray(rw * rh)
        val tmp = copyScratch
        for (yy in 0 until rh) {
            System.arraycopy(colors, (sy + yy) * stride + sx, tmp, yy * rw, rw)
        }
        for (yy in 0 until rh) {
            System.arraycopy(tmp, yy * rw, colors, (y + yy) * stride + x, rw)
        }
    }

    private fun readHextile(input: DataInputStream, colors: IntArray, stride: Int, x: Int, y: Int, rw: Int, rh: Int) {
        var bg = 0
        var fg = 0
        var ty = 0
        while (ty < rh) {
            val th = minOf(16, rh - ty)
            var tx = 0
            while (tx < rw) {
                val tw = minOf(16, rw - tx)
                val sub = input.readUnsignedByte()
                if (sub and 1 != 0) {
                    for (yy in 0 until th) {
                        val base = (y + ty + yy) * stride + x + tx
                        for (xx in 0 until tw) colors[base + xx] = cpixel(input)
                    }
                } else {
                    if (sub and 2 != 0) bg = cpixel(input)
                    if (sub and 4 != 0) fg = cpixel(input)
                    for (yy in 0 until th) {
                        val base = (y + ty + yy) * stride + x + tx
                        for (xx in 0 until tw) colors[base + xx] = bg
                    }
                    if (sub and 8 != 0) {
                        val n = input.readUnsignedByte()
                        val colored = sub and 16 != 0
                        for (i in 0 until n) {
                            val color = if (colored) cpixel(input) else fg
                            val xy = input.readUnsignedByte()
                            val wh = input.readUnsignedByte()
                            val sxx = xy shr 4
                            val syy = xy and 0x0F
                            val sw = (wh shr 4) + 1
                            val sh = (wh and 0x0F) + 1
                            for (yy in 0 until sh) {
                                val ry = syy + yy
                                if (ry >= th) break
                                val base = (y + ty + ry) * stride + x + tx
                                for (xx in 0 until sw) {
                                    if (sxx + xx >= tw) break
                                    colors[base + sxx + xx] = color
                                }
                            }
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
    }

    private fun fail(message: String): Nothing = throw RuntimeException(message)

    private fun dstRect(): Rect {
        val vw = width
        val vh = height
        if (frameWidth == 0 || frameHeight == 0 || vw == 0 || vh == 0) return Rect(0, 0, 0, 0)
        val scale = minOf(vw.toFloat() / frameWidth, vh.toFloat() / frameHeight)
        val dw = (frameWidth * scale).toInt()
        val dh = (frameHeight * scale).toInt()
        val left = (vw - dw) / 2
        val top = (vh - dh) / 2
        return Rect(left, top, left + dw, top + dh)
    }

    private fun mapToFrame(vx: Float, vy: Float): Pair<Int, Int> {
        val d = dstRect()
        if (d.width() <= 0 || d.height() <= 0) return 0 to 0
        val fx = ((vx - d.left) / d.width() * frameWidth).toInt().coerceIn(0, frameWidth - 1)
        val fy = ((vy - d.top) / d.height() * frameHeight).toInt().coerceIn(0, frameHeight - 1)
        return fx to fy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val dst = dstRect()
        if (dst.width() <= 0) return
        synchronized(bmp) { canvas.drawBitmap(bmp, null, dst, paint) }
        // 自绘光标：否则触摸板模式下指针不可见，看起来像“没动”
        val cx = dst.left + cursorX.toFloat() / frameWidth * dst.width()
        val cy = dst.top + cursorY.toFloat() / frameHeight * dst.height()
        canvas.drawCircle(cx, cy, 14f, cursorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (frameWidth == 0 || frameHeight == 0) return false
        parent?.requestDisallowInterceptTouchEvent(true)

        if (!touchpadMode) {
            // 触屏：绝对坐标，按下即在该点按下左键（先移动再按下，兼容部分服务端）
            val (fx, fy) = mapToFrame(event.x, event.y)
            cursorX = fx
            cursorY = fy
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sendPointer(fx, fy, 0)
                    sendPointer(fx, fy, 1)
                }
                MotionEvent.ACTION_MOVE -> sendPointer(fx, fy, 1)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendPointer(fx, fy, 0)
            }
            return true
        }

        // 触摸板：相对移动，单指轻点=左键
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = System.currentTimeMillis()
                moved = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> moved = true

            MotionEvent.ACTION_MOVE -> {
                val d = dstRect()
                val sx = if (d.width() > 0) frameWidth.toFloat() / d.width() * 1.6f else 1.6f
                val sy = if (d.height() > 0) frameHeight.toFloat() / d.height() * 1.6f else 1.6f
                val dx = (event.x - downX) * sx
                val dy = (event.y - downY) * sy
                if (kotlin.math.abs(dx) > 1f || kotlin.math.abs(dy) > 1f) moved = true
                cursorX = (cursorX + dx).toInt().coerceIn(0, frameWidth - 1)
                cursorY = (cursorY + dy).toInt().coerceIn(0, frameHeight - 1)
                downX = event.x
                downY = event.y
                sendPointer(cursorX, cursorY, 0)
            }

            MotionEvent.ACTION_UP -> {
                if (!moved && System.currentTimeMillis() - downAt < 250) leftClick()
            }
        }
        return true
    }
}
