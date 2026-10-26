package net.metalstrapper.sparkwriter

import kotlinx.serialization.json.Json
import net.metalstrapper.sparkwriter.core.ConfigFetchSpec
import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.FormPrefillButton
import net.metalstrapper.sparkwriter.core.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefillPrototypeTest {
    @Test
    fun hostProfilesAreOfferedForVisiblePageTargets() {
        val fields = listOf(ConfigField(id = "hostname", label = "Hostname"))
        val profile = HostProfile(
            id = "lab1",
            label = "Lab 1",
            fields = mapOf("hostname" to "lab-one"),
        )

        assertEquals(listOf(profile), fields.prefillHostProfiles(listOf(profile)))
    }

    @Test
    fun hiddenFieldsRemainUnavailableToPrefillCandidates() {
        val visibleFields = listOf(ConfigField(id = "hostname", label = "Hostname"))
        val hiddenOnlyProfile = HostProfile(
            id = "secret",
            label = "Secret",
            fields = mapOf("hidden_secret" to "value"),
        )

        assertTrue(visibleFields.prefillHostProfiles(listOf(hiddenOnlyProfile)).isEmpty())
    }

    @Test
    fun remoteJsonPrefillResolvesSimpleObjectPaths() {
        val button = FormPrefillButton(
            id = "prefill",
            label = "Prefill",
            fetch = ConfigFetchSpec("https://prefill.example/host"),
            fieldBindings = mapOf(
                "hostname" to "host.network.hostname",
                "enabled" to "host.enabled",
                "token" to "host.token",
            ),
        )
        val root = Json.parseToJsonElement(
            """
            {
              "host": {
                "network": {"hostname": "lab-one"},
                "enabled": true,
                "token": null
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            mapOf("hostname" to "lab-one", "enabled" to "true", "token" to ""),
            button.resolvePrefillValues(root),
        )
    }

    @Test
    fun prefillButtonsRespectPageTargetsAndConditions() {
        val fields = listOf(ConfigField(id = "hostname", label = "Hostname"))
        val button = FormPrefillButton(
            id = "prefill",
            label = "Prefill",
            fetch = ConfigFetchSpec("https://prefill.example/host"),
            fieldBindings = mapOf("hostname" to "host.hostname"),
        )

        assertEquals(listOf(button), fields.prefillButtons(listOf(button), emptyMap()))
    }
}
