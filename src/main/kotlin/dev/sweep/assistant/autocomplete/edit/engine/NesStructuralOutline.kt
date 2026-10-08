package dev.sweep.assistant.autocomplete.edit.engine

/** Conservative PHP fallback. Masks comments/strings before recognizing declarations. */
object NesStructuralOutline {
    data class Declaration(val text: String, val start: Int, val end: Int, val ownerStart: Int, val ownerEnd: Int, val priority: Int)

    fun extract(source: String, cursor: Int, cancelled: () -> Boolean = { false }): List<Declaration> {
        if (source.length > 10_000_000 || cancelled()) return emptyList()
        val masked = source.toCharArray()
        val sanitized = source.toCharArray()
        var i = 0
        while (i < masked.size) {
            if (i % 1024 == 0 && cancelled()) return emptyList()
            val start = i
            val c = source[i]
            if (source.startsWith("<<<", i)) return emptyList() // heredoc: uncertain lexical boundaries
            if (source.startsWith("//", i) || c == '#' && !source.startsWith("#[", i)) {
                while (i < masked.size && source[i] != '\n') i++
            } else if (source.startsWith("/*", i)) {
                val end = source.indexOf("*/", i + 2)
                if (end < 0) return emptyList()
                i = end + 2
            } else if (c == '\'' || c == '"' || c == '`') {
                i++
                var closed = false
                while (i < masked.size) {
                    if (source[i] == '\\') { i += 2; continue }
                    if (source[i++] == c) { closed = true; break }
                }
                if (!closed) return emptyList()
            } else { i++; continue }
            for (j in start until i.coerceAtMost(masked.size)) if (masked[j] != '\n') {
                masked[j] = ' '
                if (c != '\'' && c != '"' && c != '`') sanitized[j] = ' '
            }
        }
        val code = String(masked)
        val signatures = String(sanitized)
        val depth = IntArray(code.length + 1)
        val closing = mutableMapOf<Int, Int>()
        val stack = java.util.ArrayDeque<Int>()
        for (j in code.indices) {
            if (j % 1024 == 0 && cancelled()) return emptyList()
            depth[j + 1] = depth[j]
            if (code[j] == '{') { stack.push(j); depth[j + 1]++ }
            if (code[j] == '}') { if (stack.isNotEmpty()) closing[stack.pop()] = j; depth[j + 1]-- }
        }
        data class Owner(val start: Int, val open: Int, val end: Int)
        val owners = Regex("\\b(?:class|interface|trait|enum)\\s+[A-Za-z_][A-Za-z_0-9]*[^;{}]*\\{")
            .findAll(code).mapNotNull { m -> closing[m.range.last]?.let { Owner(m.range.first, m.range.last, it) } }.toList()
        val declarations = mutableListOf<Declaration>()
        for (owner in owners) {
            if (cancelled()) return emptyList()
            declarations.add(Declaration(code.substring(owner.start, owner.open).trim(), owner.start, owner.open, owner.start, owner.end, 2))
            val members = Regex("\\b(?:(?:public|protected|private|static|final|abstract|readonly|var)\\s+)*(?:function\\s+&?\\s*[A-Za-z_][A-Za-z_0-9]*\\s*\\(|const\\s+|(?:[?\\\\A-Za-z_][\\\\A-Za-z_0-9?|&]*\\s+)?\\$[A-Za-z_])")
            var consumedUntil = owner.open + 1
            for (m in members.findAll(code, owner.open + 1)) {
                val start = m.range.first
                if (start >= owner.end) break
                if (start < consumedUntil) continue
                if (depth[start] != depth[owner.open] + 1) continue
                var end = start
                var parens = 0
                var brackets = 0
                while (end < owner.end && end - start < 8192) {
                    val ch = code[end]
                    if (ch == '(') parens++
                    if (ch == ')') parens--
                    if (ch == '[') brackets++
                    if (ch == ']') brackets--
                    if (parens == 0 && brackets == 0 && (ch == ';' || ch == '{')) break
                    end++
                }
                if (end >= owner.end || end - start >= 8192 || parens != 0 || brackets != 0) continue
                val signature = signatures.substring(start, end).trim()
                val method = Regex("\\bfunction\\b").containsMatchIn(signature)
                if (!method && code[end] != ';') continue
                // Drop initializers, whose quoted values were masked; keep signature/type only.
                consumedUntil = end + 1
                val text = if (method) signature else signature.substringBefore('=').trim() + ";"
                val priority = if (!method || Regex("function\\s+(?:get|set|is|has|__construct)", RegexOption.IGNORE_CASE).containsMatchIn(signature)) 0 else 1
                declarations.add(Declaration(text, start, end + 1, owner.start, owner.end, priority))
            }
        }
        return declarations.distinctBy { it.start }.sortedWith(compareBy<Declaration> { if (cursor in it.ownerStart..it.ownerEnd) 0 else 1 }.thenBy { it.priority }.thenBy { it.start })
    }
}
