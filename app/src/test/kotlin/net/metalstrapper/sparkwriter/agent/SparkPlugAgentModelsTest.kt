package net.metalstrapper.sparkwriter.agent

import net.metalstrapper.sparkwriter.buildDraftUri
import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.parseDraftUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SparkPlugAgentModelsTest {
    private val runtime = ManifestRuntime()

    private val manifest = runtime.parseCombined(
        """
        {
          "version": "3.0",
          "metadata": {"id": "demo", "name": "Demo", "description": "Demo installer", "version": "1.2"},
          "outputs": {"usb": true, "iso": false},
          "config_fields": [
            {"id": "intro", "label": "Intro", "type": "info", "default": "Hello"},
            {"id": "default.host-name", "label": "Hostname", "required": true, "max_length": 16, "description": "Short name"},
            {
              "id": "network", "label": "Network", "type": "select", "default": "dhcp",
              "options": [{"value": "dhcp", "label": "DHCP"}, {"value": "static", "label": "Static"}]
            },
            {
              "id": "ip_address", "label": "IP Address", "required": true, "pattern": "[0-9./]+",
              "when": {"field": "network", "operator": "equals", "value": "static"}
            },
            {"id": "root_password", "label": "Root password", "type": "password", "required": true},
            {"id": "serial", "label": "Serial", "immutable": true, "default": "abc"},
            {"id": "salt", "label": "Salt", "hidden": true}
          ],
          "wizard": {
            "pages": [
              {"id": "identity", "title": "Identity", "fields": ["intro", "default.host-name", "serial"]},
              {"id": "network", "title": "Network", "fields": ["network", "ip_address"]},
              {"id": "access", "title": "Access", "fields": ["root_password"]}
            ]
          }
        }
        """.trimIndent(),
    )

    @Test
    fun summaryCarriesIdentityAndOutputs() {
        val summary = manifest.toAgentSummary()

        assertEquals("demo", summary.id)
        assertEquals("Demo", summary.name)
        assertEquals("Demo installer", summary.description)
        assertEquals("1.2", summary.version)
        assertTrue(summary.canWriteUsb)
        assertFalse(summary.canSaveIso)
    }

    @Test
    fun detailListsEditableFieldsAndFlagsSecrets() {
        val fields = manifest.toAgentDetail().fields.associateBy { it.id }

        // info, immutable and hidden fields are not offered.
        assertEquals(listOf("default.host-name", "network", "ip_address", "root_password"), fields.keys.toList())
        assertTrue(fields.getValue("root_password").userEntersThis)
        assertFalse(fields.getValue("default.host-name").userEntersThis)
        assertEquals("Identity", fields.getValue("default.host-name").page)
        assertEquals(16, fields.getValue("default.host-name").maxLength)
        assertEquals(0, fields.getValue("network").maxLength)
        assertEquals(listOf("dhcp", "static"), fields.getValue("network").options.map { it.value })
        assertEquals("network equals static", fields.getValue("ip_address").appliesWhen)
        assertNull(fields.getValue("network").appliesWhen)
    }

    @Test
    fun everyListedNonSecretFieldIsAcceptedByTheDraft() {
        // Whatever describeSparkPlug offers, prepareSparkPlugDraft must accept: the two
        // lists drifting apart would have an assistant sending values that are dropped.
        val detail = manifest.toAgentDetail()
        val values = detail.fields.filterNot { it.userEntersThis }.map { field ->
            val value = when {
                field.options.isNotEmpty() -> field.options.last().value
                field.pattern != null -> "10.0.0.5/24"
                else -> "x"
            }
            SparkPlugFieldValue(field.id, value)
        }

        val validation = manifest.validateAgentDraft(values)

        assertEquals(emptyList<Any>(), validation.issues)
        assertEquals(listOf("root_password"), validation.missingRequired)
    }

    @Test
    fun draftRequestSurvivesTheLinkToTheActivity() {
        val values = listOf(SparkPlugFieldValue("host_name", "pve1"), SparkPlugFieldValue("network", "static"))

        val request = values.toDraftRequest("demo")

        assertEquals(request, parseDraftUri(buildDraftUri(request)))
    }

    @Test
    fun bundledProxmoxPlugIsDescribable() {
        val proxmox = runtime.parseWorkflowFile(
            listOf(File("app/bundled"), File("bundled")).first { it.isDirectory }
                .resolve("proxmox-autoinstall.sparkplug.yaml"),
        )
        val detail = proxmox.toAgentDetail()
        val ids = detail.fields.map { it.id }

        assertEquals("proxmox-autoinstall", detail.sparkPlug.id)
        assertTrue("pve_fqdn" in ids)
        assertTrue("pve_network_cidr" in ids)
        assertTrue(detail.fields.single { it.id == "pve_network_cidr" }.appliesWhen!!.contains("from-answer"))
    }
}
