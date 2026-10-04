package com.pocketcode.studio.core.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

/**
 * C 语言编辑器语言层。
 *
 * - 继承 [EmptyLanguage]，接入 [CAnalyzer] 提供语法高亮；
 * - 重写 [requireAutoComplete] 提供关键字/类型/库函数补全。
 *
 * 挂到 CodeEditor：`editor.setEditorLanguage(CLanguage())`。
 */
class CLanguage : EmptyLanguage() {

    private val analyzer = CAnalyzer()

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val line = runCatching { content.getLine(position.line) }.getOrNull() ?: return
        val col = position.column.coerceAtMost(line.length)
        // 取光标前正在输入的单词
        var i = col
        while (i > 0) {
            val c = line[i - 1]
            if (c.isLetterOrDigit() || c == '_') i-- else break
        }
        val prefix = line.substring(i, col)
        if (prefix.isEmpty()) return

        val prefixLen = prefix.length
        C_KEYWORDS.asSequence()
            .filter { it.startsWith(prefix) || it.startsWith(prefix.lowercase()) }
            .forEach { kw ->
                publisher.addItem(
                    SimpleCompletionItem(kw, prefixLen, kw)
                        .kind(kindOf(kw))
                )
            }
        publisher.updateList()
    }

    private fun kindOf(kw: String): CompletionItemKind = when {
        kw in C_TYPES -> CompletionItemKind.Keyword
        kw in C_KEYWORDS_LIST -> CompletionItemKind.Keyword
        else -> CompletionItemKind.Function
    }

    companion object {
        private val C_TYPES = setOf(
            "void", "char", "short", "int", "long", "float", "double",
            "signed", "unsigned", "const", "volatile", "auto", "register",
            "static", "extern", "size_t", "ssize_t", "int8_t", "int16_t",
            "int32_t", "int64_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t",
            "ptrdiff_t", "intptr_t", "uintptr_t", "FILE", "NULL", "bool",
            "true", "false",
        )

        private val C_KEYWORDS_LIST = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "goto", "struct", "union", "enum",
            "typedef", "sizeof", "inline", "restrict",
            "include", "define", "ifdef", "ifndef", "endif", "elif", "pragma", "undef", "error",
        )

        private val C_LIB = setOf(
            "printf", "fprintf", "sprintf", "snprintf", "scanf", "fscanf",
            "sscanf", "fopen", "fclose", "fread", "fwrite", "fgets", "fputs",
            "fputc", "fgetc", "feof", "ferror", "fflush", "fseek", "ftell",
            "rewind", "malloc", "calloc", "realloc", "free", "memcpy", "memmove",
            "memset", "memcmp", "memchr", "strcpy", "strncpy", "strcat", "strncat",
            "strcmp", "strncmp", "strlen", "strchr", "strrchr", "strstr",
            "strtok", "atoi", "atol", "atof", "strtol", "strtoul", "strtod",
            "abs", "labs", "rand", "srand", "time", "clock", "exit", "abort",
            "qsort", "bsearch", "sqrt", "pow", "sin", "cos", "tan", "log",
            "log2", "log10", "exp", "fabs", "floor", "ceil", "round",
        )

        private val C_KEYWORDS: List<String> = (C_TYPES + C_KEYWORDS_LIST + C_LIB)
            .toList().sorted()
    }
}
