package me.rerere.liquidhub.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        }
    }
}

private fun objSchema(properties: Map<String, String>, required: List<String>): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (k, desc) ->
                putJsonObject(k) {
                    put("type", "string")
                    put("description", desc)
                }
            }
        }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

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
        val cmd = if (os.contains("win")) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }
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

fun workspaceTools(): List<ToolSpec> = listOf(
    ToolSpec(
        name = "list_dir",
        description = "列出工作区目录内容。path 为相对工作区根目录的路径，默认 \".\"。",
        parameters = objSchema(mapOf("path" to "相对工作区根目录的目录路径"), emptyList()),
    ),
    ToolSpec(
        name = "read_file",
        description = "读取工作区内的文本文件。",
        parameters = objSchema(mapOf("path" to "相对工作区根目录的文件路径"), listOf("path")),
    ),
    ToolSpec(
        name = "write_file",
        description = "把内容写入工作区内的文件（不存在则创建，父目录自动创建）。",
        parameters = objSchema(
            mapOf("path" to "相对路径", "content" to "要写入的完整文本"),
            listOf("path", "content"),
        ),
    ),
    ToolSpec(
        name = "run_command",
        description = "在工作区根目录执行一条 shell 命令并返回输出。仅在用户开启命令执行时可用。",
        parameters = objSchema(mapOf("command" to "要执行的命令"), listOf("command")),
    ),
)

suspend fun executeWorkspaceTool(settings: AppSettings, call: ToolCall): String {
    val ws = Workspace(settings.workspaceDir, settings.allowCommands)
    val args = runCatching { Json.parseToJsonElement(call.function.arguments).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }
    fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull ?: ""
    return runCatching {
        when (call.function.name) {
            "list_dir" -> ws.listDir(str("path"))
            "read_file" -> ws.readFile(str("path"))
            "write_file" -> ws.writeFile(str("path"), str("content"))
            "run_command" -> ws.runCommand(str("command"))
            else -> "未知工具：${call.function.name}"
        }
    }.getOrElse { "工具执行失败：${it.message}" }
}
