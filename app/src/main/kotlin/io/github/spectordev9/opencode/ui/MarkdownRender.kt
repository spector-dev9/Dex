package io.github.spectordev9.opencode.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Compose rendering for parsed markdown (F02) plus copy/share actions on
 * code blocks (F03). Formatting is preserved when copying: fenced blocks
 * emit their raw body, messages emit the raw markdown source.
 */
@Composable
fun MarkdownText(
    markdown: String,
    palette: OpencodePalette,
    modifier: Modifier = Modifier,
    textSize: Int = 14,
    codeSize: Int = 12,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> textSize + 6
                        2 -> textSize + 4
                        3 -> textSize + 2
                        else -> textSize + 1
                    }
                    Text(
                        text = inlineAnnotated(block.inline, palette, codeSize = codeSize),
                        color = palette.TextCol,
                        fontSize = size.sp,
                        lineHeight = (size + 6).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = inlineAnnotated(block.inline, palette, codeSize = codeSize),
                    color = palette.TextCol,
                    fontSize = textSize.sp,
                    lineHeight = (textSize + 8).sp,
                )

                is MdBlock.Code -> CodeBlock(
                    code = block.code,
                    language = block.language,
                    palette = palette,
                    fontSize = codeSize,
                )

                is MdBlock.ListItem -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (block.ordered) block.marker else "•",
                        color = palette.Accent,
                        fontSize = textSize.sp,
                        lineHeight = (textSize + 8).sp,
                        modifier = Modifier.width(if (block.ordered) 22.dp else 12.dp),
                    )
                    Text(
                        text = inlineAnnotated(block.inline, palette, codeSize = codeSize),
                        color = palette.TextCol,
                        fontSize = textSize.sp,
                        lineHeight = (textSize + 8).sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Blockquote -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.Accent.copy(alpha = 0.55f)),
                    )
                    Text(
                        text = inlineAnnotated(block.inline, palette, codeSize = codeSize),
                        color = palette.Muted,
                        fontSize = textSize.sp,
                        lineHeight = (textSize + 8).sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Table -> MarkdownTable(block, palette, textSize)

                MdBlock.Rule -> HorizontalDivider(color = palette.BorderCol)
            }
        }
    }
}

@Composable
private fun MarkdownTable(block: MdBlock.Table, palette: OpencodePalette, textSize: Int) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = palette.ToolChipBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.ToolChipBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                block.headers.forEach { header ->
                    Text(
                        text = header,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .width(110.dp),
                        fontSize = (textSize - 1).sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.TextCol,
                    )
                }
            }
            HorizontalDivider(color = palette.BorderCol)
            block.rows.forEachIndexed { rowIndex, cells ->
                Row {
                    cells.forEach { cell ->
                        Text(
                            text = cell,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .width(110.dp),
                            fontSize = (textSize - 1).sp,
                            color = palette.Muted,
                            lineHeight = (textSize + 4).sp,
                        )
                    }
                }
                if (rowIndex != block.rows.lastIndex) {
                    HorizontalDivider(color = palette.BorderCol.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private fun inlineAnnotated(text: String, palette: OpencodePalette, codeSize: Int): AnnotatedString {
    val builder = buildAnnotatedString {
        parseInlineMarkdown(text).forEach { span ->
            when (span) {
                is MdSpan.Plain -> append(span.text)
                is MdSpan.Bold -> {
                    val start = length
                    append(span.text)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }

                is MdSpan.Italic -> {
                    val start = length
                    append(span.text)
                    addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, length)
                }

                is MdSpan.Strike -> {
                    val start = length
                    append(span.text)
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                }

                is MdSpan.Code -> {
                    val start = length
                    append(span.text)
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = codeSize.sp,
                            color = palette.InlineCodeText,
                            background = palette.CodeBg,
                        ),
                        start,
                        length,
                    )
                }

                is MdSpan.Link -> {
                    val start = length
                    append(span.text)
                    addStyle(
                        SpanStyle(
                            color = palette.Accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                        start,
                        length,
                    )
                }
            }
        }
    }
    return builder
}

/** Fenced code block with a language tag, copy and share affordances (F03). */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    palette: OpencodePalette,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.CodeBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.CodeBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 2.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language ?: "code",
                    fontSize = 10.sp,
                    color = palette.Muted2,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        clipboard?.setText(AnnotatedString(code))
                        copied = true
                    },
                ) {
                    Text(
                        if (copied) "Copied ✓" else "Copy",
                        fontSize = 10.sp,
                        color = if (copied) palette.SuccessCol else palette.Accent,
                    )
                }
                TextButton(onClick = { shareText(context, code) }) {
                    Text("Share", fontSize = 10.sp, color = palette.Accent)
                }
            }
            Text(
                text = highlightCode(code, language, palette),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 6).sp,
            )
        }
    }
}

/** Copies the given text to the Android clipboard. */
fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard =
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("opencode", text))
}

/** Opens the Android share sheet for the given text. */
fun shareText(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, null))
    }
}

/**
 * Conservative token-based syntax highlighting based on the fence language
 * (F02). Unknown languages fall back to plain code coloring.
 */
fun highlightCode(code: String, language: String?, palette: OpencodePalette): AnnotatedString {
    val lang = normalizeLanguage(language)
    if (lang == null) {
        return AnnotatedString(code)
    }
    val keywords = languageKeywords[lang] ?: return AnnotatedString(code)
    val builder = buildAnnotatedString {
        append(code)
    }
    val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
    tokenRegex.findAll(code).forEach { match ->
        val range = match.range
        val value = match.value
        val style = when {
            value.startsWith("//") || value.startsWith("/*") -> spanStyle(palette.Muted2)
            value.startsWith("#") ||
                (value.startsWith("--") && lang in hashLanguages) -> spanStyle(palette.Muted2)

            value.startsWith("\"") || value.startsWith("'") || value.startsWith("`") ->
                spanStyle(palette.SuccessCol)

            value.startsWith("@") -> spanStyle(palette.Accent)

            value.first().isDigit() -> spanStyle(palette.WarningCol)

            value in keywords -> spanStyle(palette.Highlight)

            else -> {
                val next = code.getOrNull(range.last + 1)
                if (next == '(') spanStyle(palette.Accent) else null
            }
        }
        if (style != null) spans += range to style
    }
    spans.sortedBy { it.first.first }.forEach { (range, style) ->
        builder.addStyle(style, range.first, range.last + 1)
    }
    return builder
}

private fun normalizeLanguage(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    val name = tag.trim().lowercase()
    return when (name) {
        "kt", "kts" -> "kotlin"
        "py" -> "python"
        "js", "jsx", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "sh", "shell", "zsh", "console" -> "bash"
        "yml" -> "yaml"
        "golang" -> "go"
        "c", "h", "cpp", "hpp", "cc", "hh", "cxx" -> "cpp"
        "cs" -> "csharp"
        "rb" -> "ruby"
        "html", "htm", "xml", "svg" -> "xml"
        else -> name.takeIf { it in languageKeywords }
    }
}

private val hashLanguages = setOf("bash", "python", "yaml", "ruby", "toml")

private fun spanStyle(color: Color) = SpanStyle(color = color)

private val tokenRegex = Regex(
    "//[^\\n]*" +
        "|/\\*[\\s\\S]*?\\*/" +
        "|#[^\\n]*" +
        "|--[A-Za-z][^\\n]*" +
        "|\"(?:[^\"\\\\\\n]|\\\\.)*\"" +
        "|'(?:[^'\\\\\\n]|\\\\.)*'" +
        "|`(?:[^`\\\\]|\\\\.)*`" +
        "|@[A-Za-z_][A-Za-z0-9_]*" +
        "|\\b\\d+(?:\\.\\d+)?\\b" +
        "|[A-Za-z_][A-Za-z0-9_]*",
)

private val languageKeywords: Map<String, Set<String>> = mapOf(
    "kotlin" to setOf(
        "package", "import", "class", "object", "interface", "fun", "val", "var", "if", "else",
        "when", "for", "while", "return", "is", "in", "as", "null", "true", "false", "this",
        "super", "override", "open", "private", "public", "internal", "protected", "sealed",
        "data", "enum", "companion", "suspend", "lateinit", "by", "try", "catch", "finally",
        "throw", "typealias", "const", "init", "abstract", "operator", "inline", "vararg",
    ),
    "java" to setOf(
        "package", "import", "class", "interface", "enum", "public", "private", "protected",
        "static", "final", "void", "int", "long", "double", "float", "boolean", "char", "byte",
        "short", "new", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "try", "catch", "finally", "throw", "throws", "extends", "implements",
        "this", "super", "null", "true", "false", "abstract", "synchronized", "record",
    ),
    "python" to setOf(
        "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
        "break", "continue", "pass", "try", "except", "finally", "raise", "with", "lambda",
        "None", "True", "False", "and", "or", "not", "in", "is", "global", "nonlocal", "yield",
        "async", "await", "self",
    ),
    "javascript" to setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
        "switch", "case", "break", "continue", "new", "class", "extends", "import", "export",
        "from", "default", "async", "await", "try", "catch", "finally", "throw", "typeof",
        "instanceof", "in", "of", "null", "undefined", "true", "false", "this", "super", "yield",
    ),
    "typescript" to setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
        "switch", "case", "break", "continue", "new", "class", "extends", "implements",
        "import", "export", "from", "default", "async", "await", "try", "catch", "finally",
        "throw", "typeof", "instanceof", "in", "of", "null", "undefined", "true", "false",
        "this", "super", "interface", "type", "enum", "namespace", "declare", "readonly",
        "public", "private", "protected", "abstract", "as", "keyof", "yield",
    ),
    "go" to setOf(
        "package", "import", "func", "return", "if", "else", "for", "range", "switch", "case",
        "default", "break", "continue", "go", "defer", "chan", "select", "var", "const",
        "type", "struct", "interface", "map", "nil", "true", "false", "fallthrough", "goto",
    ),
    "rust" to setOf(
        "fn", "let", "mut", "const", "static", "if", "else", "match", "for", "while", "loop",
        "break", "continue", "return", "struct", "enum", "impl", "trait", "pub", "crate",
        "mod", "use", "as", "where", "async", "await", "move", "ref", "true", "false", "self",
        "Self", "super", "dyn", "unsafe",
    ),
    "cpp" to setOf(
        "auto", "bool", "break", "case", "catch", "char", "class", "const", "continue",
        "default", "delete", "do", "double", "else", "enum", "explicit", "export", "extern",
        "false", "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable",
        "namespace", "new", "nullptr", "operator", "private", "protected", "public", "return",
        "short", "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw",
        "true", "try", "typedef", "typename", "union", "unsigned", "using", "virtual", "void",
        "volatile", "while",
    ),
    "csharp" to setOf(
        "using", "namespace", "class", "struct", "interface", "enum", "record", "public",
        "private", "protected", "internal", "static", "readonly", "const", "void", "var",
        "new", "return", "if", "else", "for", "foreach", "while", "do", "switch", "case",
        "break", "continue", "try", "catch", "finally", "throw", "async", "await", "in",
        "is", "as", "null", "true", "false", "this", "base", "override", "virtual", "abstract",
        "sealed", "get", "set",
    ),
    "bash" to setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "function", "in", "return", "exit", "local", "export", "echo", "cd", "source",
        "set", "unset", "read", "trap",
    ),
    "sql" to setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
        "create", "table", "primary", "key", "foreign", "references", "join", "left", "right",
        "inner", "outer", "on", "group", "by", "order", "having", "limit", "offset", "and",
        "or", "not", "null", "distinct", "as", "index", "alter", "drop", "commit", "rollback",
    ),
    "json" to setOf("true", "false", "null"),
    "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off"),
    "toml" to setOf("true", "false"),
    "xml" to setOf(),
    "swift" to setOf(
        "func", "let", "var", "if", "else", "guard", "switch", "case", "default", "for", "in",
        "while", "repeat", "return", "class", "struct", "enum", "protocol", "extension",
        "import", "init", "self", "super", "nil", "true", "false", "where", "async", "await",
        "throws", "try", "catch", "deinit", "public", "private", "internal", "static",
    ),
    "ruby" to setOf(
        "def", "end", "class", "module", "if", "elsif", "else", "unless", "while", "until",
        "for", "in", "do", "begin", "rescue", "ensure", "raise", "yield", "return", "self",
        "nil", "true", "false", "and", "or", "not", "then", "case", "when", "attr_accessor",
        "require", "puts",
    ),
    "php" to setOf(
        "function", "return", "if", "else", "elseif", "while", "for", "foreach", "as", "class",
        "interface", "trait", "extends", "implements", "public", "private", "protected",
        "static", "new", "try", "catch", "finally", "throw", "echo", "print", "null", "true",
        "false", "use", "namespace", "const", "abstract", "final",
    ),
    "lua" to setOf(
        "function", "end", "if", "then", "else", "elseif", "for", "while", "do", "repeat",
        "until", "return", "local", "and", "or", "not", "nil", "true", "false", "in", "break",
        "goto",
    ),
    "dart" to setOf(
        "import", "class", "extends", "implements", "with", "enum", "abstract", "mixin",
        "final", "const", "var", "late", "static", "void", "return", "if", "else", "for",
        "while", "do", "switch", "case", "default", "break", "continue", "try", "catch",
        "finally", "throw", "async", "await", "true", "false", "null", "this", "super", "in",
        "is", "as", "factory", "get", "set", "required",
    ),
)
