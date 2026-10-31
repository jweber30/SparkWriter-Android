package net.metalstrapper.sparkwriter.core

/**
 * Template placeholders: `{{ name }}` or `{{ name | filter | filter }}`.
 *
 * Filters that quote (`shell_quote`, `toml_string`) emit a complete literal,
 * surrounding quotes included, so the template must not wrap them in quotes.
 */
object SparkTemplateFilters {
    /** Any `{{ ... }}` span, well-formed or not. */
    val ANY_PLACEHOLDER = Regex("""\{\{(.*?)\}\}""")

    /** A well-formed placeholder. Group 1 is the variable, group 2 the filter chain. */
    val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_.-]+)\s*((?:\|\s*[A-Za-z0-9_]+\s*)*)\}\}""")

    private val filters: Map<String, (String) -> String> = mapOf(
        "shell_quote" to ::shellQuote,
        "toml_string" to ::tomlString,
        "mac_hex" to ::macHex,
    )

    /** Filters whose output already carries its own quotes. */
    val QUOTING_FILTERS: Set<String> = setOf("shell_quote", "toml_string")

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
}
