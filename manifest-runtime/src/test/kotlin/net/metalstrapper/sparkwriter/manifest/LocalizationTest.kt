package net.metalstrapper.sparkwriter.manifest

import com.fasterxml.jackson.databind.ObjectMapper
import net.metalstrapper.sparkwriter.core.LocalizedText
import net.metalstrapper.sparkwriter.core.SharedText
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.TextLocalizer
import net.metalstrapper.sparkwriter.core.localized
import net.metalstrapper.sparkwriter.fixtures.parseCombined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LocalizationTest {
    private val runtime = ManifestRuntime()

    private val manifest = runtime.parseCombined(
        """
        version: "3.0"
        metadata:
          id: demo
          name: Demo
        actions:
          on_iso_ready:
            - id: make-password
              type: generate_ephemeral_password
              output_var: root_password
              user_secret: true
              user_secret_label:
                en: Root password
                fr: Mot de passe root
        config_fields:
          - id: keys
            label:
              en: Root SSH keys
              fr: Clés SSH root
            description: Paste one key per line.
          - id: proxy
            label: APT proxy
            standard_field: apt.proxy
          - id: host
            label:
              ref: hostname
          - id: reboot
            label: Reboot mode
            type: select
            options:
              - value: reboot
                label:
                  ref: reboot
              - value: off
                label: Power off
        wizard:
          pages:
            - id: identity
              title:
                ref: identity
                fr: Identité de la machine
              fields: [keys, proxy, host, reboot]
        """.trimIndent(),
    )

    // Stands in for the app's resources: French when the app shows French, else English.
    private fun localizer(vararg preferred: String, ui: String) = TextLocalizer(
        preferredLanguages = preferred.toList(),
        uiLanguage = ui,
        sharedText = { id -> if (ui == "fr") "fr:$id" else SharedText.english[id] },
    )

    private fun SparkManifest.label(id: String) = configFields.single { it.id == id }.label

    @Test
    fun parsesPlainAndTranslatedText() {
        val keys = manifest.configFields.single { it.id == "keys" }
        assertEquals("Root SSH keys", keys.label)
        assertEquals(LocalizedText(mapOf("en" to "Root SSH keys", "fr" to "Clés SSH root")), keys.localizedText["label"])
        assertNull(keys.localizedText["description"])

        val host = manifest.configFields.single { it.id == "host" }
        assertEquals("Hostname", host.label)
        assertEquals(LocalizedText(ref = "hostname"), host.localizedText["label"])
        assertEquals("en", manifest.metadata.language)
    }

    @Test
    fun frenchUserGetsAuthorThenSharedTranslations() {
        val french = manifest.localized(localizer("fr-FR", ui = "fr"))

        assertEquals("Clés SSH root", french.label("keys"))
        assertEquals("Paste one key per line.", french.configFields.single { it.id == "keys" }.description)
        assertEquals("fr:apt.proxy", french.label("proxy"))
        assertEquals("fr:hostname", french.label("host"))
        assertEquals(listOf("fr:reboot", "Power off"), french.configFields.single { it.id == "reboot" }.options.map { it.label })
        assertEquals("Identité de la machine", french.wizardPages.single().title)
        assertEquals("Mot de passe root", french.actionPhases.getValue("on_iso_ready").single().userSecretLabel)
    }

    @Test
    fun englishUserKeepsAuthorWording() {
        val english = manifest.localized(localizer("en-US", ui = "en"))

        assertEquals("Root SSH keys", english.label("keys"))
        assertEquals("APT proxy", english.label("proxy"))
        assertEquals("Hostname", english.label("host"))
        assertEquals("Identity", english.wizardPages.single().title)
    }

    @Test
    fun unsupportedLanguageFallsBackToSparkplugLanguage() {
        // German is not an app language, so the app shows English.
        val german = manifest.localized(localizer("de-DE", ui = "en"))

        assertEquals("APT proxy", german.label("proxy"))
        assertEquals("Hostname", german.label("host"))
    }

    @Test
    fun laterPreferredLanguageIsUsedBeforeFallback() {
        val germanThenFrench = manifest.localized(localizer("de-DE", "fr-CA", ui = "fr"))

        assertEquals("Clés SSH root", germanThenFrench.label("keys"))
        assertEquals("fr:apt.proxy", germanThenFrench.label("proxy"))
    }

    @Test
    fun sharedTextIdsMatchSchema() {
        val repoRoot = listOf(File("."), File("..")).first { File(it, "docs/$SCHEMA_FILE").isFile }
        val schema = ObjectMapper().readTree(File(repoRoot, "docs/$SCHEMA_FILE"))
        val refEnum = schema["\$defs"]["displayText"]["oneOf"][1]["properties"]["ref"]["enum"].map { it.asText() }

        assertEquals(SharedText.ids.toList(), refEnum)
    }

    private companion object {
        const val SCHEMA_FILE = "sparkwriter_manifest_contract_v3_0.schema.json"
    }
}
