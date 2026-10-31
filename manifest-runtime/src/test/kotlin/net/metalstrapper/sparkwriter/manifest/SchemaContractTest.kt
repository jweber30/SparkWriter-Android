package net.metalstrapper.sparkwriter.manifest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.fixtures.splitCombinedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

class SchemaContractTest {
    private val runtime = ManifestRuntime()
    private val mapper = ObjectMapper()
    private val repoRoot = listOf(File("."), File("..")).first { File(it, "docs/$SCHEMA_FILE").isFile }
    private val schemaRoot = mapper.readTree(File(repoRoot, "docs/$SCHEMA_FILE"))

    @Test
    fun publishedWorkflowExamplesMatchSchemaAndRuntime() {
        val workflowSchema = schemaFor("workflowManifest")
        val imageSchema = schemaFor("imageManifest")
        val examples = listOf("app/bundled", "examples", "docs").flatMap { directory ->
            File(repoRoot, directory).listFiles().orEmpty().filter { it.name.endsWith(".sparkplug.yaml") }
        }
        assertTrue("No example manifests found", examples.isNotEmpty())

        examples.sortedBy { it.path }.forEach { example ->
            val workflow = example.readText()
            val imageId = runtime.workflowImageId(workflow)
            val imageFile = File(example.parentFile, "images/$imageId.image.yaml")
            assertTrue("${example.name} needs ${imageFile.path}", imageFile.isFile)
            val image = imageFile.readText()

            assertEquals("${example.name} schema errors", emptyList<String>(), workflowSchema.errors(yamlTree(workflow)))
            assertEquals("${imageFile.name} schema errors", emptyList<String>(), imageSchema.errors(yamlTree(image)))
            assertEquals(imageId, runtime.imageDocumentId(image))
            runtime.parse(workflow, image)
        }
    }

    @Test
    fun sharedImagesAreIdenticalWhereverTheyAreCopied() {
        val copies = listOf("app/bundled", "examples", "docs")
            .flatMap { File(repoRoot, "$it/images").listFiles().orEmpty().toList() }
            .groupBy { it.name }
            .filterValues { it.size > 1 }
        copies.forEach { (name, files) ->
            assertEquals("$name differs between copies", 1, files.map { it.readText() }.distinct().size)
        }
    }

    @Test
    fun publishedPackageRootExampleMatchesSchema() {
        val packageRoot = mapper.readTree(File(repoRoot, "docs/sparkwriter-netboot-dual-protocol.manifest.json"))

        assertEquals(emptyList<String>(), schemaFor("packageRootManifest").errors(packageRoot))
        runtime.parsePackageRoot(packageRoot.toString())
    }

    @Test
    fun schemaAndRuntimeBothRejectWhatTheContractDoesNotDefine() {
        val workflowSchema = schemaFor("workflowManifest")
        val imageSchema = schemaFor("imageManifest")
        val rejected = mapOf(
            "unknown top-level key" to "$MINIMAL\nruntime: {}",
            "older version" to MINIMAL.replace("\"3.0\"", "\"2.3\""),
            "unknown action type" to "$MINIMAL\nactions:\n  on_iso_ready:\n    - id: x\n      type: command",
            "unknown action phase" to "$MINIMAL\nactions:\n  on_boot:\n    - id: x\n      type: render_template",
            "unknown host event" to "$MINIMAL\nhost_events:\n  - event: write_failed\n    return_delivery:\n      url: https://example.com/r",
            "unknown field key" to "$MINIMAL\nconfig_fields:\n  - id: token\n    obfuscated: true",
            "unknown field type" to "$MINIMAL\nconfig_fields:\n  - id: token\n    type: checkbox",
            "non-positive max_length" to "$MINIMAL\nconfig_fields:\n  - id: token\n    max_length: 0",
            "legacy boot patch" to "$MINIMAL\nboot_patch:\n  line_match: linux\n  append_args:\n    - quiet",
            "unknown translation key" to "$MINIMAL\nconfig_fields:\n  - id: token\n    label:\n      english: Token",
            "unknown shared text ref" to "$MINIMAL\nconfig_fields:\n  - id: token\n    label:\n      ref: token",
            "empty translation map" to "$MINIMAL\nconfig_fields:\n  - id: token\n    label: {}",
            "blank translation" to "$MINIMAL\nconfig_fields:\n  - id: token\n    label:\n      fr: \"\"",
            "invalid metadata language" to MINIMAL.replace("name: Demo", "name: Demo\n  language: english"),
        )

        rejected.forEach { (name, text) ->
            val split = splitCombinedManifest(text)
            val schemaErrors = workflowSchema.errors(mapper.readTree(split.workflow)) +
                imageSchema.errors(mapper.readTree(split.image))
            assertFalse("Schema accepted $name", schemaErrors.isEmpty())
            assertThrows("Runtime accepted $name", ManifestValidationException::class.java) { runtime.parseCombined(text) }
        }
    }

    @Test
    fun schemaAndRuntimeRejectSlotArtifactsInTheImage() {
        val image = """
            version: "3.0"
            kind: sparkplug.image
            metadata:
              id: img
              name: Image
            installer:
              scheme: ubuntu-nocloud
              accepts_delivery:
                - auxiliary_partition
            delivery:
              mode: auxiliary_partition
              partition:
                label: CIDATA
                filesystem: vfat
                size_mb: 64
                placement: append_after_image
              files:
                /user-data:
                  artifact_id: user-data
        """.trimIndent()

        assertFalse(schemaFor("imageManifest").errors(yamlTree(image)).isEmpty())
        assertThrows(ManifestValidationException::class.java) { runtime.imageDocumentId(image) }
    }

    private fun schemaFor(definition: String): JsonSchema {
        val wrapper = mapper.createObjectNode()
        wrapper.set<JsonNode>("\$schema", schemaRoot["\$schema"])
        wrapper.put("\$ref", "#/\$defs/$definition")
        wrapper.set<JsonNode>("\$defs", schemaRoot["\$defs"])
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(wrapper)
    }

    private fun JsonSchema.errors(node: JsonNode): List<String> = validate(node).map { it.message }

    private fun yamlTree(text: String): JsonNode =
        mapper.valueToTree(Load(LoadSettings.builder().build()).loadFromString(text))

    private companion object {
        const val SCHEMA_FILE = "sparkwriter_manifest_contract_v3_0.schema.json"
        val MINIMAL = """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
        """.trimIndent()
    }
}
