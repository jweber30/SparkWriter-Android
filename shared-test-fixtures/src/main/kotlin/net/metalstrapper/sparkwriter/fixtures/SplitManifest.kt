package net.metalstrapper.sparkwriter.fixtures

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** The two documents of a SparkPlug, as JSON text. */
data class SplitManifest(val workflow: String, val image: String)

private val IMAGE_SECTIONS = setOf("source", "outputs", "installer", "delivery", "boot_patch")

/**
 * Test helper: splits one combined manifest (workflow and image keys in a single
 * document) into the workflow and image documents the runtime parses.
 *
 * Image sections move to the image document. Each delivery slot's `artifact_id`
 * (or `from: artifacts.<id>`) becomes a `fill` entry in the workflow. Everything else,
 * including unknown keys, stays in the workflow so strict loading still sees it.
 * Lets behavior tests describe a manifest in one block of YAML.
 */
fun splitCombinedManifest(text: String): SplitManifest {
    val root = Load(LoadSettings.builder().build()).loadFromString(text).toJson() as? JsonObject
        ?: error("Combined manifest must be an object")
    val source = root["source"] as? JsonObject
    val metadata = root["metadata"] as? JsonObject
    val imageId = source?.string("id") ?: "${metadata?.string("id") ?: "test"}-image"

    val fill = linkedMapOf<String, JsonElement>()
    fun JsonObject.withoutArtifact(slot: String): JsonObject {
        val artifact = string("artifact_id") ?: string("from")?.substringAfter("artifacts.")
        artifact?.let { fill[slot] = JsonPrimitive(it) }
        return JsonObject(this - "artifact_id" - "from")
    }
    val delivery = (root["delivery"] as? JsonObject)?.let { original ->
        JsonObject(
            original + buildMap {
                when (val patches = original["patches"]) {
                    is JsonArray -> put("patches", JsonArray(patches.map { patch -> patch.slot { withoutArtifact(it) } }))
                    is JsonObject -> put("patches", patches.slot { patches.withoutArtifact(it) })
                    else -> Unit
                }
                (original["files"] as? JsonObject)?.let { files ->
                    put("files", JsonObject(files.mapValues { (path, file) -> (file as? JsonObject)?.withoutArtifact(path) ?: file }))
                }
            },
        )
    }

    val image = buildMap {
        root["version"]?.let { put("version", it) }
        put("kind", JsonPrimitive("sparkplug.image"))
        put(
            "metadata",
            JsonObject(mapOf("id" to JsonPrimitive(imageId), "name" to JsonPrimitive(source?.string("name") ?: imageId))),
        )
        IMAGE_SECTIONS.forEach { key -> root[key]?.let { put(key, it) } }
        delivery?.let { put("delivery", it) }
    }
    val workflow = buildMap {
        root.forEach { (key, value) -> if (key !in IMAGE_SECTIONS) put(key, value) }
        if ("kind" !in root) put("kind", JsonPrimitive("sparkplug.workflow"))
        put("image", JsonPrimitive(imageId))
        if (fill.isNotEmpty()) put("fill", JsonObject(fill))
    }
    return SplitManifest(JsonObject(workflow).toString(), JsonObject(image).toString())
}

/** Parses a combined test manifest by splitting it first. See [splitCombinedManifest]. */
fun ManifestRuntime.parseCombined(
    text: String,
    installOrigin: String? = null,
    packageRootSha256: String? = null,
): SparkManifest {
    val split = splitCombinedManifest(text)
    return parse(split.workflow, split.image, installOrigin, packageRootSha256)
}

private fun JsonElement.slot(transform: JsonObject.(String) -> JsonObject): JsonElement {
    val patch = this as? JsonObject ?: return this
    val id = patch.string("id") ?: return this
    return patch.transform(id)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJson() })
    is List<*> -> JsonArray(map { it.toJson() })
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

/** Parses a workflow file with the image it names, read from the sibling `images/` folder. */
fun ManifestRuntime.parseWorkflowFile(file: java.io.File, installOrigin: String? = null): SparkManifest {
    val workflow = file.readText()
    val image = java.io.File(file.parentFile, "images/${workflowImageId(workflow)}.image.yaml").readText()
    return parse(workflow, image, installOrigin)
}
