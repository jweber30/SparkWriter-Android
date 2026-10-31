package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDraftTest {
    private val runtime = ManifestRuntime()

    private val manifest = runtime.parseCombined(
        """
        {
          "version": "3.0",
          "metadata": {"id": "demo", "name": "Demo"},
          "config_fields": [
            {"id": "intro", "label": "Intro", "type": "info", "default": "Hello"},
            {"id": "default.host-name", "label": "Hostname", "required": true, "max_length": 16, "pattern": "[a-z0-9-]+"},
            {
              "id": "network", "label": "Network", "type": "select", "default": "dhcp",
              "options": [{"value": "dhcp", "label": "DHCP"}, {"value": "static", "label": "Static"}]
            },
            {
              "id": "ip_address", "label": "IP Address", "required": true,
              "when": {"field": "network", "operator": "equals", "value": "static"}
            },
            {"id": "root_password", "label": "Root password", "type": "password", "required": true},
            {"id": "serial", "label": "Serial", "immutable": true, "default": "abc"},
            {"id": "secret_salt", "label": "Salt", "hidden": true}
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
    fun acceptsFieldsByManifestIdOrFormId() {
        val byManifestId = manifest.validateDraft(mapOf("default.host-name" to "pve1"))
        val byFormId = manifest.validateDraft(mapOf("host_name" to "pve1"))

        assertEquals(mapOf("host_name" to "pve1"), byManifestId.accepted)
        assertEquals(byManifestId.accepted, byFormId.accepted)
        assertTrue(byFormId.issues.isEmpty())
    }

    @Test
    fun refusesSecretsAndLockedFieldsAndUnknownIds() {
        val result = manifest.validateDraft(
            mapOf(
                "root_password" to "hunter2",
                "serial" to "zzz",
                "secret_salt" to "x",
                "intro" to "x",
                "nope" to "x",
            ),
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            mapOf(
                "root_password" to DraftIssueCode.SecretField,
                "serial" to DraftIssueCode.NotEditable,
                "secret_salt" to DraftIssueCode.NotEditable,
                "intro" to DraftIssueCode.NotEditable,
                "nope" to DraftIssueCode.UnknownField,
            ),
            result.issues.associate { it.fieldId to it.code },
        )
    }

    @Test
    fun appliesTheSameRulesAsTheEditor() {
        val result = manifest.validateDraft(
            mapOf("host_name" to "Not Valid!", "network" to "bonded"),
        )
        val tooLong = manifest.validateDraft(mapOf("host_name" to "a".repeat(17)))

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            setOf(DraftIssueCode.BadFormat, DraftIssueCode.NotAnOption),
            result.issues.map { it.code }.toSet(),
        )
        assertEquals(DraftIssueCode.TooLong, tooLong.issues.single().code)
    }

    @Test
    fun dropsValuesForFieldsHiddenByTheirCondition() {
        val dhcp = manifest.validateDraft(mapOf("ip_address" to "10.0.0.5"))
        val static = manifest.validateDraft(mapOf("network" to "static", "ip_address" to "10.0.0.5"))

        assertEquals(DraftIssueCode.Inactive, dhcp.issues.single().code)
        assertFalse("ip_address" in dhcp.accepted)
        assertEquals("10.0.0.5", static.accepted["ip_address"])
    }

    @Test
    fun reportsRequiredFieldsTheUserMustStillFill() {
        val result = manifest.validateDraft(mapOf("host_name" to "pve1", "network" to "static"))

        assertEquals(listOf("ip_address", "root_password"), result.missingRequired)
        assertFalse(result.complete)
    }

    @Test
    fun landsOnFirstIncompletePageOtherwiseReview() {
        val missingPassword = mapOf("host_name" to "pve1", "network" to "dhcp")
        val missingIp = mapOf("host_name" to "pve1", "network" to "static", "root_password" to "x")
        val complete = missingPassword + ("root_password" to "x")

        assertEquals(2, manifest.draftLandingStep(missingPassword))
        assertEquals(1, manifest.draftLandingStep(missingIp))
        // Three pages, so Review is step index 3.
        assertEquals(3, manifest.draftLandingStep(complete))
    }

    @Test
    fun draftUriRoundTripsAwkwardValues() {
        val request = ConfigDraftRequest(
            manifestId = "proxmox-autoinstall",
            values = linkedMapOf(
                "host_name" to "pve 1",
                "ssh_keys" to "ssh-ed25519 AAAA+/= me@host\nssh-rsa BBBB&x=y",
                "notes" to "100% ünïcode",
            ),
        )

        assertEquals(request, parseDraftUri(buildDraftUri(request)))
    }

    @Test
    fun rejectsLinksThatAreNotDrafts() {
        assertNull(parseDraftUri("spark://plugin/add?url=https://example.com/manifest.json"))
        assertNull(parseDraftUri("spark://draft?f.host_name=pve1"))
        assertNull(parseDraftUri("spark://draft/other?plug=demo"))
        assertNull(parseDraftUri("https://draft?plug=demo"))
        assertNull(parseDraftUri("spark://draft?plug=demo&f.x=%zz"))
        assertNull(parseDraftUri("spark://draft?plug=demo&f.x=" + "a".repeat(MAX_DRAFT_URI_LENGTH)))
        assertNull(parseDraftUri("not a uri at all"))
    }

    @Test
    fun ignoresUnrelatedQueryParameters() {
        val parsed = parseDraftUri("spark://draft?plug=demo&utm_source=x&f.host_name=pve1")

        assertEquals(ConfigDraftRequest("demo", mapOf("host_name" to "pve1")), parsed)
    }
}
