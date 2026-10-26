package net.metalstrapper.sparkwriter.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowImageSplitTest {
    private val runtime = ManifestRuntime()
    private val hash = "c".repeat(64)

    @Test
    fun fillPutsArtifactsIntoImageSlots() {
        val manifest = runtime.parse(workflow(), image())

        val patches = manifest.delivery!!.patches.associateBy { it.id }
        assertEquals("answer_toml", patches.getValue("answer").artifactId)
        assertNull(patches.getValue("notes").artifactId)
        assertEquals("proxmox", manifest.source?.id)
        assertEquals("demo", manifest.metadata.id)
    }

    @Test
    fun fillsAuxiliaryFilesByPath() {
        val image = """
            version: "3.0"
            kind: sparkplug.image
            metadata:
              id: ubuntu
              name: Ubuntu
            source:
              id: ubuntu
              sha256: $hash
            installer:
              scheme: ubuntu-nocloud
              accepts_delivery:
                - auxiliary_partition
              auxiliary_partition:
                label: CIDATA
                filesystem: vfat
                required_files:
                  - /user-data
            delivery:
              mode: auxiliary_partition
              partition:
                label: CIDATA
                filesystem: vfat
                size_mb: 64
                placement: append_after_image
              files:
                /user-data:
                  media_type: text/cloud-config
        """.trimIndent()
        val workflow = """
            version: "3.0"
            kind: sparkplug.workflow
            metadata:
              id: ubuntu-demo
              name: Ubuntu Demo
            image: ubuntu
            fill:
              /user-data: user-data
            artifacts:
              user-data:
                template: user_data
            templates:
              user_data: "#cloud-config"
        """.trimIndent()

        val manifest = runtime.parse(workflow, image)

        assertEquals("user-data", manifest.delivery!!.files.getValue("/user-data").artifactId)
    }

    @Test
    fun createArtifactOutputsCanFillSlots() {
        val workflow = workflow(artifacts = "", extra = "actions:\n  on_iso_ready:\n    - id: make\n      type: create_artifact\n      artifact_id: answer_toml\n      content_var: x")
        val manifest = runtime.parse(workflow, image())

        assertEquals("answer_toml", manifest.delivery!!.patches.first().artifactId)
    }

    @Test
    fun rejectsWorkflowWrittenForAnotherImage() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parse(workflow(imageId = "debian"), image())
        }
        assertEquals("Workflow is for image debian, but the package image is proxmox-generic", error.message)
    }

    @Test
    fun rejectsFillForUnknownSlot() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parse(workflow(fill = "  answer: answer_toml\n  missing: answer_toml"), image())
        }
        assertEquals("fill names slots the image does not declare: missing", error.message)
    }

    @Test
    fun rejectsUnfilledRequiredSlot() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parse(workflow(fill = "  notes: answer_toml"), image())
        }
        assertEquals("Image slot answer is not filled by the workflow", error.message)
    }

    @Test
    fun rejectsFillWithUnknownArtifact() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parse(workflow(fill = "  answer: nope"), image())
        }
        assertEquals("fill.answer names unknown artifact nope", error.message)
    }

    @Test
    fun rejectsDocumentsPassedInTheWrongOrder() {
        val error = assertThrows(ManifestValidationException::class.java) { runtime.parse(image(), workflow()) }
        assertEquals("Unsupported workflow keys: delivery, installer, source", error.message)
    }

    @Test
    fun rejectsWorkflowWithImageSections() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parse(workflow(extra = "source:\n  id: proxmox"), image())
        }
        assertEquals("Unsupported workflow keys: source", error.message)
    }

    @Test
    fun rejectsWrongDocumentKind() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parse(workflow().replace("kind: sparkplug.workflow", "kind: sparkplug.image"), image())
        }
        assertEquals("Expected a sparkplug.workflow document, found sparkplug.image", error.message)
    }

    @Test
    fun readsIdsForPairing() {
        assertEquals("proxmox-generic", runtime.workflowImageId(workflow()))
        assertEquals("proxmox-generic", runtime.imageDocumentId(image()))
    }

    @Test
    fun rejectsPackageRootWithOnlyAWorkflow() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parsePackageRoot(
                """
                {
                  "version": "3.0",
                  "kind": "sparkplug.manifest",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "documents": {"workflow": {"role": "workflow", "url": "workflow.yaml"}}
                }
                """.trimIndent(),
                requireIntegrity = false,
            )
        }
        assertEquals("manifest.json must contain exactly one workflow document and one image document", error.message)
    }

    private fun workflow(
        imageId: String = "proxmox-generic",
        fill: String = "  answer: answer_toml",
        artifacts: String = "artifacts:\n  answer_toml:\n    template: answer\ntemplates:\n  answer: \"x = 1\"",
        extra: String = "",
    ): String = buildString {
        appendLine("version: \"3.0\"")
        appendLine("kind: sparkplug.workflow")
        appendLine("metadata:\n  id: demo\n  name: Demo")
        appendLine("image: $imageId")
        appendLine("fill:\n$fill")
        if (artifacts.isNotEmpty()) appendLine(artifacts)
        if (extra.isNotEmpty()) appendLine(extra)
    }

    private fun image(): String = """
        version: "3.0"
        kind: sparkplug.image
        metadata:
          id: proxmox-generic
          name: Proxmox generic
        source:
          id: proxmox
          sha256: $hash
        installer:
          scheme: proxmox-auto-install
          accepts_delivery:
            - fixed_byte_ranges
        delivery:
          mode: fixed_byte_ranges
          base_image_sha256: $hash
          patches:
            - id: answer
              byte_offset: 1024
              byte_length: 128
            - id: notes
              byte_offset: 4096
              byte_length: 128
              optional: true
    """.trimIndent()
}
