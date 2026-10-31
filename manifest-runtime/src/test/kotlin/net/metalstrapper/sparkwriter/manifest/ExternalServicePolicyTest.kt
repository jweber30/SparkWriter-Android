package net.metalstrapper.sparkwriter.manifest

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.core.ExternalServices
import net.metalstrapper.sparkwriter.core.withExternalServicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExternalServicePolicyTest {
    private val releaseRuntime = ManifestRuntime()
    private val devRuntime = ManifestRuntime(externalServicesEnabled = true)
    private val repoRoot = listOf(File("."), File("..")).first { File(it, "examples").isDirectory }

    @Test
    fun bundledProxmoxStaysRunnable() {
        val manifest = releaseRuntime.parseWorkflowFile(File(repoRoot, "app/bundled/proxmox-autoinstall.sparkplug.yaml"))
        assertEquals(emptyList<String>(), manifest.unsupportedReasons)
        assertTrue(manifest.isAndroidRunnable)
    }

    @Test
    fun returnDeliveryAndMailroomManifestIsUnavailableByDefault() {
        val manifest = releaseRuntime.parseWorkflowFile(example("debian-https-preseed.sparkplug.yaml"))
        assertFalse(manifest.isAndroidRunnable)
        assertTrue(manifest.unsupportedReasons.any { it.contains("return_delivery") })
        assertTrue(manifest.unsupportedReasons.any { it.contains("Mailroom") })
    }

    @Test
    fun sameManifestIsRunnableWhenServicesAreEnabled() {
        val manifest = devRuntime.parseWorkflowFile(example("debian-https-preseed.sparkplug.yaml"))
        assertTrue(manifest.unsupportedReasons.none { it.startsWith(ExternalServices.DISABLED_PREFIX) })
    }

    @Test
    fun fieldFetchIsDisabled() {
        val manifest = releaseRuntime.parseCombined(
            MINIMAL + "\n" + """
            config_fields:
              - id: token
                fetch:
                  url: https://prefill.example.invalid/token
            """.trimIndent(),
        )
        assertEquals(listOf("${ExternalServices.DISABLED_PREFIX}config field fetch (token)"), manifest.unsupportedReasons)
    }

    @Test
    fun prefillButtonsAreDisabled() {
        val manifest = releaseRuntime.parseCombined(
            MINIMAL + "\n" + """
            config_fields:
              - id: hostname
            form:
              buttons:
                - id: admin-prefill
                  label: Admin Prefill
                  fetch:
                    url: https://prefill.example.invalid/hosts/lab-one
                  field_bindings:
                    hostname: host.hostname
            """.trimIndent(),
        )
        assertEquals(
            listOf("${ExternalServices.DISABLED_PREFIX}remote prefill buttons (admin-prefill)"),
            manifest.unsupportedReasons,
        )
    }

    @Test
    fun mailroomUrlInATemplateIsDisabledEvenWithoutAMailroomSection() {
        val manifest = releaseRuntime.parseCombined(
            "$MINIMAL\ntemplates:\n  ipxe: |\n    chain https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{ id }}",
        )
        assertTrue(manifest.unsupportedReasons.any { it.contains("Mailroom") })
    }

    @Test
    fun attachedTemplateFilesGetThePolicyAndFilterValidation() {
        val base = releaseRuntime.parseCombined(MINIMAL)
        assertTrue(base.isAndroidRunnable)

        val withMailroom = releaseRuntime.withTemplates(base, mapOf("answer" to "url = http://wri7e.net/abc"))
        assertFalse(withMailroom.isAndroidRunnable)

        assertThrows(ManifestValidationException::class.java) {
            releaseRuntime.withTemplates(base, mapOf("answer" to "x = {{ v | nope }}"))
        }
    }

    @Test
    fun policyIsIdempotentAndReversible() {
        val disabled = releaseRuntime.parseWorkflowFile(example("debian-https-preseed.sparkplug.yaml"))
        assertEquals(disabled, disabled.withExternalServicePolicy(enabled = false))

        val reenabled = disabled.withExternalServicePolicy(enabled = true)
        assertTrue(reenabled.unsupportedReasons.none { it.startsWith(ExternalServices.DISABLED_PREFIX) })
        assertEquals(disabled, reenabled.withExternalServicePolicy(enabled = false))
    }

    private fun example(name: String): File = File(repoRoot, "examples/$name")

    private companion object {
        val MINIMAL = """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
        """.trimIndent()
    }
}
