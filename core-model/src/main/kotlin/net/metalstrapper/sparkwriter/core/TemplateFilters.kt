package net.metalstrapper.sparkwriter.core

/**
 * Template placeholders: `{{ name }}` or `{{ name | filter | filter }}`.
 *
 * Filters that quote (`shell_quote`, `toml_string`, `toml_array`) emit a complete
 * literal, surrounding quotes or brackets included, so the template must not wrap
 * them in quotes.
 */
object SparkTemplateFilters {
    /** Any `{{ ... }}` span, well-formed or not. */
    val ANY_PLACEHOLDER = Regex("""\{\{(.*?)\}\}""")

    /** A well-formed placeholder. Group 1 is the variable, group 2 the filter chain. */
    val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_.-]+)\s*((?:\|\s*[A-Za-z0-9_]+\s*)*)\}\}""")

    private val filters: Map<String, (String) -> String> = mapOf(
        "shell_quote" to ::shellQuote,
        "toml_string" to ::tomlString,
        "toml_array" to ::tomlArray,
        "mac_hex" to ::macHex,
    )

    /** Filters whose output is a complete literal, with its own quotes or brackets. */
    val QUOTING_FILTERS: Set<String> = setOf("shell_quote", "toml_string", "toml_array")

    val names: Set<String> get() = filters.keys

    fun parseChain(chain: String): List<String> =
        chain.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    fun apply(filterNames: List<String>, value: String): String =
        filterNames.fold(value) { current, name ->
            val filter = filters[name] ?: throw IllegalArgumentException("Unknown template filter: $name")
            filter(current)
        }

    /** POSIX shell single-quoted literal. Safe for any text except NUL. */
    fun shellQuote(value: String): String {
        require('\u0000' !in value) { "shell_quote value may not contain NUL bytes" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * A MAC address as 12 lowercase hex digits with no separators, the form udev
     * uses in names like `enx001122aabbcc`. Accepts `:`, `-` or `.` separators.
     */
    fun macHex(value: String): String {
        val hex = value.trim().lowercase().filterNot { it == ':' || it == '-' || it == '.' }
        require(hex.length == 12 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Not a MAC address: $value"
        }
        return hex
    }

    /** TOML basic string, including the surrounding double quotes. */
    fun tomlString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (char < ' ' || char == '\u007F') {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    /**
     * A one-line TOML array of strings, built from a list as people type it:
     * `["sda", "sdb"]`, `[sda, sdb]`, `sda, sdb`, or one item per line. Each item
     * goes through [tomlString], so no value can end the array or start a new line.
     * Fails the render on an unclosed bracket or quote, or a nested list.
     */
    fun tomlArray(value: String): String =
        parseList(value.trim()).joinToString(", ", prefix = "[", postfix = "]") { tomlString(it) }

    private fun parseList(text: String): List<String> = when {
        text.isEmpty() -> emptyList()
        text.startsWith("[") -> BracketedList(text).items()
        '\n' in text -> text.lines().map { it.trim().removeSuffix(",").trim() }.filter { it.isNotEmpty() }
        else -> text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** A bracketed list of TOML basic strings, literal strings, or bare words. */
    private class BracketedList(private val text: String) {
        private val body = text.removePrefix("[").also {
            require(it.endsWith("]")) { "List is missing its closing ]: $text" }
        }.dropLast(1)
        private var pos = 0

        fun items(): List<String> {
            val items = mutableListOf<String>()
            while (true) {
                skipSpace()
                if (pos >= body.length) return items
                items += when (body[pos]) {
                    '"' -> basicString()
                    '\'' -> literalString()
                    else -> bareWord()
                }
                skipSpace()
                if (pos >= body.length) return items
                require(body[pos] == ',') { "List items must be separated by commas: $text" }
                pos++
            }
        }

        private fun skipSpace() {
            while (pos < body.length && body[pos].isWhitespace()) pos++
        }

        private fun bareWord(): String {
            val start = pos
            while (pos < body.length && body[pos] != ',') pos++
            val word = body.substring(start, pos).trim()
            require(word.isNotEmpty()) { "List has an empty item: $text" }
            require(word.none { it in "\"'[]" || it.isISOControl() }) { "List item is not a plain word: $word" }
            return word
        }

        private fun literalString(): String {
            val end = body.indexOf('\'', pos + 1)
            require(end >= 0) { "List has an unclosed ' quote: $text" }
            val item = body.substring(pos + 1, end)
            require('\n' !in item && '\r' !in item) { "List item spans lines: $text" }
            pos = end + 1
            return item
        }

        private fun basicString(): String = buildString {
            pos++
            while (true) {
                require(pos < body.length) { "List has an unclosed \" quote: $text" }
                val char = body[pos++]
                when (char) {
                    '"' -> return@buildString
                    '\n', '\r' -> throw IllegalArgumentException("List item spans lines: $text")
                    '\\' -> append(escape())
                    else -> append(char)
                }
            }
        }

        private fun escape(): String {
            require(pos < body.length) { "List ends inside an escape: $text" }
            return when (val code = body[pos++]) {
                '"' -> "\""
                '\\' -> "\\"
                'b' -> "\b"
                't' -> "\t"
                'n' -> "\n"
                'f' -> "\u000C"
                'r' -> "\r"
                'u' -> unicode(4)
                'U' -> unicode(8)
                else -> throw IllegalArgumentException("List has an unknown escape \\$code: $text")
            }
        }

        private fun unicode(digits: Int): String {
            val hex = body.substring(pos, minOf(pos + digits, body.length))
            val codePoint = hex.takeIf { it.length == digits }?.toIntOrNull(16)
            require(codePoint != null && Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF) {
                "List has a bad \\u escape: $text"
            }
            pos += digits
            return String(Character.toChars(codePoint))
        }
    }
}
