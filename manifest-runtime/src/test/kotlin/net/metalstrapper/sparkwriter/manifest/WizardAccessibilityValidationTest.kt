package net.metalstrapper.sparkwriter.manifest

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WizardAccessibilityValidationTest {
    private val runtime = ManifestRuntime()

    @Test
    fun noDeclaredPagesCreateImplicitPage() {
        val manifest = runtime.parseCombined(manifest(fields = """
            - id: hostname
            - id: internal_token
              hidden: true
        """))

        assertEquals(1, manifest.wizardPages.size)
        assertEquals("Demo Configuration", manifest.wizardPages.single().title)
        assertEquals(listOf("hostname"), manifest.wizardPages.single().fields)
        assertEquals("Hostname", manifest.configFields.first().label)
    }

    @Test
    fun fullyAssignedDeclaredPagesAreAcceptedForBothVersions() {
        listOf("3.0").forEach { version ->
            val parsed = runtime.parseCombined(manifest(version, wizard = """
                wizard:
                  pages:
                    - id: identity
                      title: Identity
                      fields: [hostname, summary, serial, token, advanced_value, conditional_value]
            """))
            assertEquals("Identity", parsed.wizardPages.single().title)
        }
    }

    @Test
    fun declaredPageRequiresExplicitNonblankTitleForBothVersions() {
        listOf("3.0").forEach { version ->
            listOf("", "   ").forEach { title ->
                val error = assertThrows(ManifestValidationException::class.java) {
                    runtime.parseCombined(manifest(version, wizard = """
                        wizard:
                          pages:
                            - id: identity
                              title: "$title"
                              fields: [hostname, summary, serial, token, advanced_value, conditional_value]
                    """))
                }
                assertTrue(error.message.orEmpty().contains("identity is missing title"))
            }
        }
    }

    @Test
    fun declaredPagesRejectEveryUnassignedVisibleFieldCategory() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(manifest(wizard = """
                wizard:
                  pages:
                    - id: identity
                      title: Identity
                      fields: [hostname]
            """))
        }

        assertTrue(error.message.orEmpty().contains(
            "summary, serial, token, advanced_value, conditional_value",
        ))
        assertTrue(!error.message.orEmpty().contains("internal_token"))
    }

    private fun manifest(
        version: String = "3.0",
        fields: String = defaultFields,
        wizard: String = "",
    ): String = buildString {
        appendLine("version: \"$version\"")
        appendLine("metadata:")
        appendLine("  id: demo")
        appendLine("  name: Demo")
        appendLine("config_fields:")
        appendLine(fields.trimIndent().prependIndent("  "))
        if (wizard.isNotBlank()) appendLine(wizard.trimIndent())
    }

    private companion object {
        val defaultFields = """
            - id: hostname
            - id: summary
              type: info
            - id: serial
              disabled: true
            - id: token
              immutable: true
            - id: advanced_value
              advanced: true
            - id: conditional_value
              when:
                field: hostname
                value: demo
            - id: internal_token
              hidden: true
        """.trimIndent()
    }
}
