package net.metalstrapper.sparkwriter.manifest

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.core.SparkTemplateFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class TemplateValidationTest {
    private val runtime = ManifestRuntime()

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'plain'", SparkTemplateFilters.shellQuote("plain"))
        assertEquals("''", SparkTemplateFilters.shellQuote(""))
        assertEquals("'it'\\''s'", SparkTemplateFilters.shellQuote("it's"))
    }

    @Test
    fun shellQuoteRejectsNul() {
        assertThrows(IllegalArgumentException::class.java) { SparkTemplateFilters.shellQuote("a\u0000b") }
    }

    @Test
    fun shellQuoteRoundTripsThroughBash() {
        val bash = File("/bin/bash")
        assumeTrue("bash not available", bash.canExecute())
        val hostile = listOf(
            "it's",
            "'; echo pwned #",
            "\$(echo pwned) `echo pwned` \${HOME}",
            "line one\nline two\n",
            "back\\slash \"double\" \\'",
            "tab\there\r\n",
            "ünïcødé 日本語",
        )
        hostile.forEach { value ->
            val script = "value=${SparkTemplateFilters.shellQuote(value)}\nprintf '%s' \"\$value\""
            val process = ProcessBuilder(bash.path, "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals("bash exit for ${value.quoted()}", 0, process.exitValue())
            assertEquals("round trip for ${value.quoted()}", value, output)
        }
    }

    @Test
    fun tomlStringEscapesQuotesBackslashesAndControls() {
        assertEquals("\"plain\"", SparkTemplateFilters.tomlString("plain"))
        assertEquals("\"a\\\"b\"", SparkTemplateFilters.tomlString("a\"b"))
        assertEquals("\"c:\\\\dir\"", SparkTemplateFilters.tomlString("c:\\dir"))
        assertEquals("\"x\\ny\\tz\\r\"", SparkTemplateFilters.tomlString("x\ny\tz\r"))
        assertEquals("\"\\u0001\\u007f\"", SparkTemplateFilters.tomlString("\u0001\u007F"))
        assertEquals("\"ünï\"", SparkTemplateFilters.tomlString("ünï"))
    }

    @Test
    fun tomlStringCannotBreakOutOfTheLine() {
        val injected = "pve\"\n[first-boot]\nsource = \"from-url"
        val rendered = SparkTemplateFilters.tomlString(injected)
        assertTrue('\n' !in rendered)
        assertTrue(rendered.startsWith("\"") && rendered.endsWith("\""))
        assertTrue(rendered.drop(1).dropLast(1).replace("\\\"", "").none { it == '"' })
    }

    @Test
    fun filtersChainInOrder() {
        assertEquals(
            SparkTemplateFilters.tomlString(SparkTemplateFilters.shellQuote("a'b")),
            SparkTemplateFilters.apply(listOf("shell_quote", "toml_string"), "a'b"),
        )
    }

    @Test
    fun acceptsWellFormedFilteredPlaceholders() {
        runtime.parseCombined(withTemplate("key = {{ name | toml_string }}\ncmd={{name|shell_quote}}\nraw = {{ name }}"))
    }

    @Test
    fun rejectsUnknownFilter() {
        assertRejected(withTemplate("x = {{ name | upper }}"), "Unsupported template filter")
    }

    @Test
    fun rejectsMalformedPlaceholder() {
        assertRejected(withTemplate("x = {{ name | }}"), "malformed placeholder")
        assertRejected(withTemplate("x = {{ two words }}"), "malformed placeholder")
    }

    @Test
    fun rejectsQuotingFilterInsideQuotes() {
        assertRejected(withTemplate("cmd='{{ name | shell_quote }}'"), "already emits a quoted literal")
        assertRejected(withTemplate("key = \"{{ name | toml_string }}\""), "already emits a quoted literal")
    }

    @Test
    fun rawPlaceholderInsideQuotesIsStillAllowed() {
        runtime.parseCombined(withTemplate("key = \"{{ name }}\""))
    }

    @Test
    fun parsesMaxLength() {
        val manifest = runtime.parseCombined(withField("max_length: 4096"))
        assertEquals(4096, manifest.configFields.single().maxLength)
    }

    @Test
    fun rejectsInvalidMaxLength() {
        assertRejected(withField("max_length: 0"), "max_length must be a positive integer")
        assertRejected(withField("max_length: -5"), "max_length must be a positive integer")
        assertRejected(withField("max_length: \"10\""), "max_length must be a positive integer")
        assertRejected(withField("max_length: 1.5"), "max_length must be a positive integer")
    }

    @Test
    fun rejectsMaxLengthOnSelect() {
        assertRejected(
            withField("type: select\n    max_length: 4\n    options:\n      - a"),
            "not supported for type select",
        )
    }

    @Test
    fun rejectsDefaultLongerThanMaxLength() {
        assertRejected(withField("default: toolong\n    max_length: 3"), "default is longer than its max_length")
    }

    private fun assertRejected(manifest: String, message: String) {
        val error = assertThrows(ManifestValidationException::class.java) { runtime.parseCombined(manifest) }
        assertTrue("Expected '$message' in '${error.message}'", error.message.orEmpty().contains(message))
    }

    private fun withTemplate(template: String): String =
        MINIMAL + "\ntemplates:\n  demo: |\n" + template.lines().joinToString("\n") { "    $it" }

    private fun withField(extra: String): String =
        "$MINIMAL\nconfig_fields:\n  - id: name\n    $extra"

    private fun String.quoted(): String = replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private companion object {
        val MINIMAL = """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
        """.trimIndent()
    }
}
