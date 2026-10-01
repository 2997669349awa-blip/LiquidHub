package me.rerere.liquidhub.desktop

import java.io.File

/** 本地工作区：限定在一个根目录内的文件读写，可选执行命令。 */
class Workspace(private val rootPath: String, private val allowCommands: Boolean) {
    private val root = File(rootPath)

    private fun ensureRoot() {
        if (!root.exists()) root.mkdirs()
    }

    private fun resolve(rel: String): File {
        val base = root.canonicalFile
        val f = File(base, rel.ifBlank { "." }).canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) {
            error("路径越界：$rel")
        }
        return f
    }

    fun listDir(rel: String): String {
        val dir = resolve(rel)
        if (!dir.exists()) return "目录不存在：$rel"
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return "(空)"
        return entries.joinToString("\n") {
            val kind = if (it.isDirectory) "dir " else "file"
            "$kind ${it.name}" + if (it.isFile) " (${it.length()}B)" else ""
        }.ifBlank { "(空)" }
    }

    fun readFile(rel: String): String {
        val f = resolve(rel)
        if (!f.isFile) return "文件不存在：$rel"
        if (f.length() > 512_000) return "文件过大（${f.length()} 字节），已拒绝读取"
        return f.readText()
    }

    fun writeFile(rel: String, content: String): String {
        val f = resolve(rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "已写入 $rel（${content.length} 字符）"
    }

    fun runCommand(command: String): String {
        if (!allowCommands) return "命令执行未开启（在设置里打开「允许 AI 执行命令」）"
        val os = System.getProperty("os.name").lowercase()
        val cmd = if (os.contains("win")) listOf("cmd.exe", "/c", command) else listOf("sh", "-c", command)
        return runCatching {
            val p = ProcessBuilder(cmd).directory(root).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            "exit=$code\n${out.takeLast(8000)}"
        }.getOrElse { "命令执行失败：${it.message}" }
    }

    fun tree(limit: Int = 300): List<String> {
        ensureRoot()
        val result = mutableListOf<String>()
        fun walk(dir: File, prefix: String) {
            if (result.size >= limit) return
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (c in children) {
                if (result.size >= limit) return
                result.add(prefix + (if (c.isDirectory) "[D] " else "    ") + c.name)
                if (c.isDirectory) walk(c, "$prefix  ")
            }
        }
        walk(root, "")
        return result
    }
}
