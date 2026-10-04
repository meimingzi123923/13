package com.pocketcode.studio.core.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.textStyle
import io.github.rosemoe.sora.lang.styling.color.EditorColor
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * C 语言词法高亮分析器。
 *
 * 采用「整文本重扫」的简单策略（非增量），在每次 reset/insert/delete 时全量重算样式。
 * 对于单文件 C 代码规模，性能完全够用。
 *
 * 支持高亮：块注释、行注释、字符串/字符字面量、预处理指令、关键字、数字、标识符、运算符。
 */
class CAnalyzer : AnalyzeManager {

    private var receiver: StyleReceiver? = null
    private var content: ContentReference? = null

    override fun setReceiver(receiver: StyleReceiver?) {
        this.receiver = receiver
    }

    override fun reset(content: ContentReference, extraArguments: Bundle) {
        this.content = content
        analyze(content)
    }

    override fun insert(
        start: CharPosition,
        end: CharPosition,
        insertedContent: CharSequence,
    ) {
        rerun()
    }

    override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
        rerun()
    }

    override fun rerun() {
        content?.let { analyze(it) }
    }

    override fun destroy() {
        receiver = null
        content = null
    }

    private fun analyze(content: ContentReference) {
        val rcv = receiver ?: return
        val lineCount = content.lineCount
        val builder = MappedSpans.Builder(lineCount)

        var inBlockComment = false

        for (lineIdx in 0 until lineCount) {
            val line = runCatching { content.getLine(lineIdx) }.getOrNull() ?: ""
            inBlockComment = tokenizeLine(line, lineIdx, inBlockComment, builder)
        }

        val styles = Styles(builder.build())
        rcv.setStyles(this, styles)
    }

    /**
     * 对单行做词法切分并写入 span。
     * @return 该行结束后是否仍处于块注释中。
     */
    private fun tokenizeLine(
        line: String,
        lineIdx: Int,
        inBlockComment: Boolean,
        builder: MappedSpans.Builder,
    ): Boolean {
        var i = 0
        val len = line.length
        var blockComment = inBlockComment

        while (i < len) {
            // 块注释续行
            if (blockComment) {
                val end = line.indexOf("*/", i)
                if (end < 0) {
                    span(builder, lineIdx, i, COMMENT)
                    return true // 整行都是注释
                } else {
                    span(builder, lineIdx, i, COMMENT)
                    i = end + 2
                    blockComment = false
                    continue
                }
            }

            val c = line[i]

            // 行注释 //
            if (c == '/' && i + 1 < len && line[i + 1] == '/') {
                span(builder, lineIdx, i, COMMENT)
                break
            }

            // 块注释开始
            if (c == '/' && i + 1 < len && line[i + 1] == '*') {
                val end = line.indexOf("*/", i + 2)
                span(builder, lineIdx, i, COMMENT)
                if (end < 0) {
                    return true // 跨到下一行
                }
                i = end + 2
                continue
            }

            // 字符串字面量
            if (c == '"') {
                val end = findStringEnd(line, i + 1, '"')
                span(builder, lineIdx, i, LITERAL)
                i = end + 1
                continue
            }

            // 字符字面量
            if (c == '\'') {
                val end = findStringEnd(line, i + 1, '\'')
                span(builder, lineIdx, i, LITERAL)
                i = end + 1
                continue
            }

            // 预处理指令（行首 #）
            if (c == '#' && isStartOfLine(line, i)) {
                span(builder, lineIdx, i, PREPROCESSOR)
                // 预处理到行尾，但要跳过行注释
                var j = i + 1
                while (j < len) {
                    if (line[j] == '/' && j + 1 < len && line[j + 1] == '/') break
                    j++
                }
                i = j
                continue
            }

            // 标识符 / 关键字
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < len && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                val color = when {
                    word in C_KEYWORDS -> KEYWORD
                    word in C_TYPES -> KEYWORD
                    word in C_PREPROCESSOR -> PREPROCESSOR
                    else -> IDENTIFIER
                }
                span(builder, lineIdx, i, color)
                i = j
                continue
            }

            // 数字
            if (c.isDigit() || (c == '.' && i + 1 < len && line[i + 1].isDigit())) {
                var j = i + 1
                while (j < len && (line[j].isLetterOrDigit() || line[j] == '.')) j++
                span(builder, lineIdx, i, LITERAL)
                i = j
                continue
            }

            // 运算符
            if (c in "+-*/%=<>!&|^~?:") {
                span(builder, lineIdx, i, OPERATOR)
                i++
                continue
            }

            i++
        }
        return blockComment
    }

    private fun findStringEnd(line: String, from: Int, quote: Char): Int {
        var j = from
        val len = line.length
        while (j < len) {
            if (line[j] == '\\' && j + 1 < len) {
                j += 2
                continue
            }
            if (line[j] == quote) return j
            j++
        }
        return len - 1
    }

    private fun isStartOfLine(line: String, idx: Int): Boolean {
        for (k in 0 until idx) {
            if (line[k] != ' ' && line[k] != '\t') return false
        }
        return true
    }

    private fun span(builder: MappedSpans.Builder, line: Int, column: Int, colorId: Int) {
        builder.addIfNeeded(line, column, style(colorId))
    }

    private fun style(colorId: Int): Long =
        textStyle(colorId, 0, false, false, false, false)

    companion object {
        private val KEYWORD = EditorColorScheme.KEYWORD
        private val LITERAL = EditorColorScheme.LITERAL
        private val COMMENT = EditorColorScheme.COMMENT
        private val OPERATOR = EditorColorScheme.OPERATOR
        private val IDENTIFIER = EditorColorScheme.IDENTIFIER_NAME
        private val PREPROCESSOR = EditorColorScheme.ANNOTATION

        private val C_KEYWORDS = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "goto", "struct", "union", "enum",
            "typedef", "sizeof", "inline", "restrict", "static", "extern",
            "auto", "register", "volatile", "const", "signed", "unsigned",
        )
        private val C_TYPES = setOf(
            "void", "char", "short", "int", "long", "float", "double",
            "size_t", "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t",
            "uint8_t", "uint16_t", "uint32_t", "uint64_t", "ptrdiff_t",
            "intptr_t", "uintptr_t", "FILE", "bool",
        )
        private val C_PREPROCESSOR = setOf(
            "include", "define", "ifdef", "ifndef", "endif", "elif", "else",
            "pragma", "undef", "error", "line",
        )
    }
}
