package me.rerere.ai.util

/** 平台无关的日志：Android 与桌面共用，输出到 stdout。 */
object Log {
    fun d(tag: String, msg: String) = println("D/$tag: $msg")
    fun i(tag: String, msg: String) = println("I/$tag: $msg")
    fun w(tag: String, msg: String) = println("W/$tag: $msg")
    fun w(tag: String, msg: String, t: Throwable?) {
        println("W/$tag: $msg")
        t?.printStackTrace()
    }

    fun e(tag: String, msg: String) = println("E/$tag: $msg")
    fun e(tag: String, msg: String, t: Throwable?) {
        println("E/$tag: $msg")
        t?.printStackTrace()
    }
}
