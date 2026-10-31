package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.manifest.ManifestValidationException
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SparkPlugImportTest {
    private val runtime = ManifestRuntime()

    @Test
    fun localZipImportsWithoutHashesAndFindsNotableScriptBehavior() {
        val zip = packageZip(
            "manifest.json" to rootJson(),
            "workflow.yaml" to workflowYaml(),
            "image.yaml" to imageYaml(),
            "scripts/firstboot.sh" to """#!/bin/sh
                apt-get update
                curl -fsSL https://tailscale.com/install.sh | sh
                tailscale up --ssh --auth-key="${'$'}auth_key"
            """.trimIndent(),
        )
        val pending = inspectLocalSparkPlugZip(runtime, zip, "example.zip")
        assertEquals("local-test", pending.manifest.metadata.id)
        assertTrue(pending.scripts.single().label == "scripts/firstboot.sh")
        val categories = pending.findings.map { it.category }.toSet()
        assertTrue("Download and execute" in categories)
        assertTrue("Package management" in categories)
        assertTrue("Remote access" in categories)
        assertTrue("Credential use" in categories)
    }

    @Test fun rejectsPackageWithoutImageDocument() {
        val root = rootJson().replace(""","image":{"role":"image","url":"image.yaml"}""", "")
        val zip = packageZip("manifest.json" to root, "workflow.yaml" to workflowYaml())
        val error = assertThrows(ManifestValidationException::class.java) { inspectLocalSparkPlugZip(runtime, zip, "bad.zip") }
        assertEquals("manifest.json must contain exactly one workflow document and one image document", error.message)
    }

    @Test fun rejectsUndeclaredFile() {
        val zip = packageZip("manifest.json" to rootJson(), "workflow.yaml" to workflowYaml(), "image.yaml" to imageYaml(), "extra.txt" to "no")
        assertThrows(ManifestValidationException::class.java) { inspectLocalSparkPlugZip(runtime, zip, "bad.zip") }
    }

    @Test fun rejectsTraversalPath() {
        val zip = packageZip("manifest.json" to rootJson(), "workflow.yaml" to workflowYaml(), "image.yaml" to imageYaml(), "../escape" to "no")
        assertThrows(ManifestValidationException::class.java) { inspectLocalSparkPlugZip(runtime, zip, "bad.zip") }
    }

    @Test fun rejectsExpandedContentOverLimit() {
        val zip = packageZip("manifest.json" to rootJson(), "image.yaml" to imageYaml(), "workflow.yaml" to workflowYaml() + "x".repeat(MAX_SPARKPLUG_BYTES))
        assertThrows(ManifestValidationException::class.java) { inspectLocalSparkPlugZip(runtime, zip, "large.zip") }
    }

    @Test fun rejectsTransferredContentOverLimit() {
        val target = File.createTempFile("sparkplug-test", ".zip").apply { deleteOnExit() }
        assertThrows(ManifestValidationException::class.java) {
            copyBoundedSparkPlug(ByteArrayInputStream(ByteArray(MAX_SPARKPLUG_BYTES + 1)), target)
        }
    }

    @Test fun bundledProxmoxScriptProducesExpectedFindings() {
        val file = listOf(
            File("bundled/proxmox-autoinstall.sparkplug.yaml"),
            File("app/bundled/proxmox-autoinstall.sparkplug.yaml"),
        ).first { it.isFile }
        val scripts = inspectableScripts(runtime.parseWorkflowFile(file, "bundled:${file.name}"))
        val categories = analyzeNotableBehavior(scripts).map { it.category }.toSet()
        assertTrue("Repository changes" in categories)
        assertTrue("Package management" in categories)
        assertTrue("Privileged startup" in categories)
        assertFalse("Download and execute" in categories)
        assertFalse("Remote access" in categories)
        assertFalse("Credential use" in categories)
    }

    private fun packageZip(vararg files: Pair<String, String>): File {
        val file = File.createTempFile("sparkplug-test", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun rootJson() = """{
      "version":"3.0", "kind":"sparkplug.manifest", "metadata":{"id":"local-test","name":"Local Test"},
      "documents":{"workflow":{"role":"workflow","url":"workflow.yaml"},"image":{"role":"image","url":"image.yaml"}},
      "files":{"script":{"role":"template:firstboot_script","media_type":"text/x-shellscript","url":"scripts/firstboot.sh"}}
    }"""

    private fun workflowYaml() = """
      version: "3.0"
      kind: sparkplug.workflow
      metadata:
        id: local-test
        name: Local Test
      image: image
      artifacts:
        firstboot:
          artifact_id: firstboot
          media_type: text/x-shellscript
          template: firstboot_script
    """.trimIndent()

    private fun imageYaml() = """
      version: "3.0"
      kind: sparkplug.image
      metadata:
        id: image
        name: Image
      source:
        id: image
        name: Image
        user_supplied: true
    """.trimIndent()
}
