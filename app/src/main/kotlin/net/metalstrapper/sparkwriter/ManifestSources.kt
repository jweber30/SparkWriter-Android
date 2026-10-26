package net.metalstrapper.sparkwriter

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.List
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.metalstrapper.sparkwriter.core.ConfigFetchSpec
import net.metalstrapper.sparkwriter.core.FormPrefillButton
import net.metalstrapper.sparkwriter.core.InlineText
import net.metalstrapper.sparkwriter.core.PackageDocumentRef
import net.metalstrapper.sparkwriter.core.PackageFileRef
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.SparkPackageRoot
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.manifest.ManifestValidationException
import net.metalstrapper.sparkwriter.manifest.SPARKPLUG_VERSION
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun SparkManifest.isOfferedForOwnSource(): Boolean {
    if (uiVisibility.isEmpty()) return true
    val sourceSelectors = setOf("source_family", "source_id", "source_capabilities")
    val supportedConditions = uiVisibility.filter { it.field in sourceSelectors }
    if (supportedConditions.isEmpty()) return true
    val manifestSource = source
    val visibilityValues = buildMap {
        manifestSource?.family?.let { put("source_family", it) }
        manifestSource?.id?.let { put("source_id", it) }
        if (manifestSource?.capabilities?.isNotEmpty() == true) {
            put("source_capabilities", manifestSource.capabilities.joinToString("\n"))
        }
    }
    return supportedConditions.all { it.matches(visibilityValues) }
}

private const val RAW_IMAGE_MANIFEST_ID = "sparkwriter.raw-image"

internal fun ManifestRuntime.rawImageManifest(): SparkManifest {
    return parse(
        workflowRaw = """
        version: "$SPARKPLUG_VERSION"
        kind: sparkplug.workflow
        metadata:
          id: $RAW_IMAGE_MANIFEST_ID
          name: Raw Image
        image: raw-image
        """.trimIndent(),
        imageRaw = """
        version: "$SPARKPLUG_VERSION"
        kind: sparkplug.image
        metadata:
          id: raw-image
          name: Raw image
        source:
          id: raw-image
          name: Raw image
          user_supplied: true
        outputs:
          usb: true
          iso: false
        """.trimIndent(),
        installOrigin = "built-in",
    )
}

/**
 * Bundled SparkPlugs: workflow documents at the assets root, image documents under
 * `images/`. Each workflow is paired with the image its `image` key names, so
 * several workflows can share one image.
 */
internal fun Context.loadBundledManifests(runtime: ManifestRuntime): List<SparkManifest> {
    fun readAsset(path: String) = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    return pairBundledManifests(
        runtime,
        workflows = assets.list("").orEmpty().filter { it.isManifestFileName() }.associateWith { readAsset(it) },
        images = assets.list(BUNDLED_IMAGE_DIR).orEmpty().filter { it.isManifestFileName() }
            .associateWith { readAsset("$BUNDLED_IMAGE_DIR/$it") },
    )
}

/** Pairs workflow documents with image documents by image id. Inputs map file name to text. */
internal fun pairBundledManifests(
    runtime: ManifestRuntime,
    workflows: Map<String, String>,
    images: Map<String, String>,
): List<SparkManifest> {
    val imagesById = images.values.associateBy { runtime.imageDocumentId(it) }
    return workflows.toSortedMap().map { (name, workflow) ->
        val imageId = runtime.workflowImageId(workflow)
        val image = imagesById[imageId] ?: throw ManifestValidationException("Bundled workflow $name needs missing image $imageId")
        runtime.parse(workflow, image, installOrigin = "bundled:$name")
    }
}

private fun String.isManifestFileName() = endsWith(".yaml") || endsWith(".yml") || endsWith(".json")

private const val BUNDLED_IMAGE_DIR = "images"

private val manifestHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun preflightManifestSource(runtime: ManifestRuntime, manifestUrl: String): String {
    val uri = runtime.validateInstallUrl(manifestUrl)
    val request = Request.Builder().url(manifestUrl).get().build()
    try {
        manifestHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ManifestValidationException("Source check failed: HTTP ${response.code}")
            }
        }
    } catch (error: IOException) {
        throw ManifestValidationException(localDevelopmentFetchError(manifestUrl, error))
    }
    val origin = buildString {
        append(uri.scheme).append("://").append(uri.host)
        if (uri.port >= 0) append(':').append(uri.port)
    }
    return if (uri.scheme.equals("https", ignoreCase = true)) {
        "Connected securely to $origin and validated its HTTPS certificate. Review the source before downloading."
    } else {
        "Connected to local development source $origin. This source does not use HTTPS. Review it before downloading."
    }
}

internal fun installManifestFromUrl(runtime: ManifestRuntime, manifestUrl: String): SparkManifest {
    val budget = SparkPlugDownloadBudget(MAX_SPARKPLUG_BYTES)
    runtime.validateInstallUrl(manifestUrl)
    Log.d("SparkWriter", "Fetching package root: $manifestUrl")
    val packageRootText = fetchText(manifestUrl, budget)
    val packageRoot = runtime.parsePackageRoot(packageRootText, installOrigin = manifestUrl)
    val packageRootSha256 = packageRoot.manifestRootSha256 ?: packageRootText.toByteArray(Charsets.UTF_8).sha256()
    val workflowRef = packageRoot.document("workflow")
    val workflowText = workflowRef.readText(manifestUrl, budget)
    workflowRef.verifySha256(workflowText)
    val imageRef = packageRoot.document("image")
    val imageText = imageRef.readText(manifestUrl, budget)
    imageRef.verifySha256(imageText)
    return runtime.parse(workflowText, imageText, installOrigin = manifestUrl, packageRootSha256 = packageRootSha256)
        .withPackageTemplateFiles(runtime, packageRoot, manifestUrl, budget)
}

private class SparkPlugDownloadBudget(var remaining: Int)

private fun fetchText(url: String, budget: SparkPlugDownloadBudget? = null): String {
    val request = Request.Builder().url(url).get().build()
    try {
        manifestHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ManifestValidationException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw ManifestValidationException("Download failed: empty response")
            val bytes = body.byteStream().use { input ->
                val limit = budget?.remaining ?: Int.MAX_VALUE
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw ManifestValidationException("SparkPlug download exceeds 100 KiB")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            budget?.let { it.remaining -= bytes.size }
            return strictUtf8(bytes, url)
        }
    } catch (error: IOException) {
        throw ManifestValidationException(localDevelopmentFetchError(url, error))
    }
}

internal suspend fun fetchConfigFieldText(fetch: ConfigFetchSpec): String = withContext(Dispatchers.IO) {
    ExternalServiceGate.require("Config field fetch")
    if (URI(fetch.url).scheme != "https") {
        throw ManifestValidationException("Config field fetch URLs must use HTTPS")
    }
    val text = fetchText(fetch.url)
    val maxBytes = fetch.maxBytes
    if (maxBytes != null && text.toByteArray(Charsets.UTF_8).size > maxBytes) {
        throw ManifestValidationException("Fetched value exceeds ${maxBytes} bytes")
    }
    text.trimEnd()
}

internal suspend fun fetchRemotePrefillValues(button: FormPrefillButton): Map<String, String> = withContext(Dispatchers.IO) {
    ExternalServiceGate.require("Remote prefill")
    if (URI(button.fetch.url).scheme != "https") {
        throw ManifestValidationException("Config field fetch URLs must use HTTPS")
    }
    val text = fetchText(button.fetch.url)
    val maxBytes = button.fetch.maxBytes
    if (maxBytes != null && text.toByteArray(Charsets.UTF_8).size > maxBytes) {
        throw ManifestValidationException("Fetched prefill response exceeds ${maxBytes} bytes")
    }
    val root = Json.parseToJsonElement(text)
    button.resolvePrefillValues(root)
}

internal fun FormPrefillButton.resolvePrefillValues(root: JsonElement): Map<String, String> {
    return fieldBindings.mapValues { (fieldId, path) ->
        val value = root.jsonValueAtPath(path)
            ?: throw ManifestValidationException("Prefill response missing $path for $fieldId")
        value.prefillResponseScalar(path)
    }
}

private fun JsonElement.jsonValueAtPath(path: String): JsonElement? {
    if (path.isBlank()) return null
    var current: JsonElement = this
    path.split(".").forEach { segment ->
        if (segment.isBlank()) return null
        val obj = current as? JsonObject ?: return null
        current = obj[segment] ?: return null
    }
    return current
}

private fun JsonElement.prefillResponseScalar(path: String): String = when (this) {
    is JsonNull -> ""
    is JsonPrimitive -> contentOrNull ?: toString()
    else -> throw ManifestValidationException("Prefill response path $path must resolve to a scalar")
}

private fun localDevelopmentFetchError(url: String, error: IOException): String {
    val endpoint = runCatching {
        val uri = URI(url)
        val host = uri.host?.trim('[', ']')?.ifBlank { null } ?: uri.authority
        val port = uri.port.takeIf { it > 0 }
            ?: if (uri.scheme == "https") 443 else 80
        "$host:$port"
    }.getOrNull()
    val detail = error.message?.ifBlank { null } ?: error::class.simpleName
    return if (endpoint != null) {
        "Download failed from $endpoint: $detail"
    } else {
        "Download failed: $detail"
    }
}

private fun PackageDocumentRef.readText(packageUrl: String, budget: SparkPlugDownloadBudget? = null): String {
    inline?.let { return it.utf8Text() }
    val documentUrl = url ?: throw ManifestValidationException("SparkPlug document is missing url")
    val resolvedUrl = ManifestRuntime().resolvePackageReferenceUrl(packageUrl, documentUrl)
    Log.d("SparkWriter", "Fetching SparkPlug ${role} document: $resolvedUrl")
    return fetchText(resolvedUrl, budget)
}

private fun SparkManifest.withPackageTemplateFiles(
    runtime: ManifestRuntime,
    packageRoot: SparkPackageRoot,
    packageUrl: String,
    budget: SparkPlugDownloadBudget? = null,
): SparkManifest {
    val templateOverrides = packageRoot.files.mapNotNull { (key, file) ->
        val templateId = file.templateId(key) ?: return@mapNotNull null
        val text = file.readText(packageUrl, budget)
        file.verifySha256(text, key)
        templateId to text
    }.toMap()
    return runtime.withTemplates(this, templateOverrides)
}

internal fun PackageFileRef.templateId(key: String): String? {
    val fileRole = role
    return when {
        fileRole?.startsWith("template:") == true -> fileRole.removePrefix("template:")
        key.startsWith("template:") -> key.removePrefix("template:")
        else -> null
    }?.ifBlank { null }
}

private fun PackageFileRef.readText(packageUrl: String, budget: SparkPlugDownloadBudget? = null): String {
    inline?.let { return it.utf8Text() }
    val fileUrl = url ?: throw ManifestValidationException("SparkPlug file is missing url")
    val resolvedUrl = ManifestRuntime().resolvePackageReferenceUrl(packageUrl, fileUrl)
    Log.d("SparkWriter", "Fetching SparkPlug ${role ?: "package"} file: $resolvedUrl")
    return fetchText(resolvedUrl, budget)
}

private fun InlineText.utf8Text(): String {
    if (encoding != "utf-8") {
        throw ManifestValidationException("Only utf-8 inline SparkPlug documents are supported")
    }
    return text
}

private fun PackageDocumentRef.verifySha256(text: String) {
    val actual = text.toByteArray(Charsets.UTF_8).sha256()
    if (actual != sha256) {
        throw ManifestValidationException("SparkPlug ${role} SHA-256 mismatch")
    }
}

private fun PackageFileRef.verifySha256(text: String, key: String) {
    val actual = text.toByteArray(Charsets.UTF_8).sha256()
    if (actual != sha256) {
        throw ManifestValidationException("SparkPlug file $key SHA-256 mismatch")
    }
}

internal fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}

