package me.rerere.liquidhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Text(val lines: List<String>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
}

fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val textLines = mutableListOf<String>()
    val codeLines = mutableListOf<String>()
    var inCode = false
    var lang = ""
    fun flushText() {
        if (textLines.isNotEmpty()) {
            blocks.add(MdBlock.Text(textLines.toList()))
            textLines.clear()
        }
    }
    for (line in text.split("\n")) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                flushText()
                inCode = true
                lang = trimmed.removePrefix("```").trim()
                codeLines.clear()
            } else {
                blocks.add(MdBlock.Code(lang, codeLines.joinToString("\n")))
                inCode = false
                codeLines.clear()
            }
            continue
        }
        if (inCode) codeLines.add(line) else textLines.add(line)
    }
    if (inCode && codeLines.isNotEmpty()) blocks.add(MdBlock.Code(lang, codeLines.joinToString("\n")))
    flushText()
    return blocks
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Text -> block.lines.forEach { line -> MarkdownLine(line) }
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
    if (heading != null) {
        val level = heading.groupValues[1].length
        Text(
            text = heading.groupValues[2],
            fontSize = when (level) {
                1 -> 20.sp
                2 -> 18.sp
                3 -> 16.sp
                else -> 15.sp
            },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        return
    }
    val bullet = Regex("^\\s*[-*]\\s+(.*)$").find(line)
    Text(
        text = if (bullet != null) "• ${bullet.groupValues[1]}" else line,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0E1524))
            .padding(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = lang.ifBlank { "code" },
                color = Color(0xFF8AB4FF),
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Text("复制", fontSize = 11.sp)
            }
        }
        Text(
            text = remember(code, lang) { highlightCode(code, lang) },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

private val COMMON_KEYWORDS = setOf(
    "class", "interface", "object", "fun", "val", "var", "if", "else", "for", "while", "when",
    "return", "break", "continue", "import", "package", "public", "private", "protected",
    "override", "super", "this", "null", "true", "false", "try", "catch", "finally", "throw",
    "new", "in", "is", "as", "typeof", "await", "async", "def", "elif", "None", "True", "False",
    "self", "lambda", "pass", "with", "yield", "void", "int", "long", "double", "float", "bool",
    "string", "static", "final", "const", "let", "function", "export", "default", "struct", "enum",
)

private fun keywordsFor(lang: String): Set<String> = when (lang.lowercase()) {
    "kotlin", "kt" -> COMMON_KEYWORDS + setOf("data", "sealed", "suspend", "companion", "init", "lateinit", "by", "where")
    "python", "py" -> COMMON_KEYWORDS + setOf("from", "global", "nonlocal", "assert", "raise", "except", "not", "and", "or")
    "json" -> emptySet()
    "sql" -> setOf("select", "from", "where", "insert", "update", "delete", "join", "group", "order", "by", "create", "table", "index", "and", "or", "not", "null", "primary", "key", "foreign", "references")
    else -> COMMON_KEYWORDS
}

private fun commentToken(lang: String): String {
    val l = lang.lowercase()
    return when {
        l in setOf("python", "py", "sh", "bash", "shell", "yaml", "yml", "toml", "properties") -> "#[^\\n]*"
        l in setOf("html", "xml", "svg") -> "<!--[\\s\\S]*?-->"
        else -> "//[^\\n]*"
    }
}

fun highlightCode(code: String, lang: String): AnnotatedString {
    val keywordColor = Color(0xFFC586C0)
    val stringColor = Color(0xFFCE9178)
    val commentColor = Color(0xFF6A9955)
    val numberColor = Color(0xFFB5CEA8)
    val defaultColor = Color(0xFFD4D4D4)

    val keywords = keywordsFor(lang)
    val kwPattern = if (keywords.isEmpty()) null else keywords.joinToString("|") { Regex.escape(it) }
    val parts = mutableListOf<String>()
    parts.add("(?<comment>${commentToken(lang)}|/\\*[\\s\\S]*?\\*/)")
    parts.add("(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')")
    if (kwPattern != null) parts.add("(?<keyword>\\b(?:$kwPattern)\\b)")
    parts.add("(?<number>\\b\\d+(?:\\.\\d+)?\\b)")
    val regex = Regex(parts.joinToString("|"))

    return buildAnnotatedString {
        append(code)
        for (match in regex.findAll(code)) {
            val color = when {
                match.groups["comment"] != null -> commentColor
                match.groups["string"] != null -> stringColor
                match.groups["keyword"] != null -> keywordColor
                match.groups["number"] != null -> numberColor
                else -> defaultColor
            }
            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }
}
