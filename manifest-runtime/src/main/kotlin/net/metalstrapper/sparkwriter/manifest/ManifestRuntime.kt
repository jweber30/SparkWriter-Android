package net.metalstrapper.sparkwriter.manifest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.metalstrapper.sparkwriter.core.AcquireSpec
import net.metalstrapper.sparkwriter.core.ArtifactSpec
import net.metalstrapper.sparkwriter.core.AuxiliaryPartitionSpec
import net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec
import net.metalstrapper.sparkwriter.core.BootKernelArgSelector
import net.metalstrapper.sparkwriter.core.BootPatchSpec
import net.metalstrapper.sparkwriter.core.BootPlaceholderPatchSpec
import net.metalstrapper.sparkwriter.core.BootScriptPatchSpec
import net.metalstrapper.sparkwriter.core.BootTextPatchSpec
import net.metalstrapper.sparkwriter.core.ConfigBinding
import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.ConfigFetchSpec
import net.metalstrapper.sparkwriter.core.ConfigOption
import net.metalstrapper.sparkwriter.core.DeliveryFileSpec
import net.metalstrapper.sparkwriter.core.DeliveryPatchSpec
import net.metalstrapper.sparkwriter.core.DeliveryPartitionSpec
import net.metalstrapper.sparkwriter.core.DeliverySpec
import net.metalstrapper.sparkwriter.core.FieldCondition
import net.metalstrapper.sparkwriter.core.FormPrefillButton
import net.metalstrapper.sparkwriter.core.FormSpec
import net.metalstrapper.sparkwriter.core.HostProfile
import net.metalstrapper.sparkwriter.core.HostEventSubscription
import net.metalstrapper.sparkwriter.core.InlineText
import net.metalstrapper.sparkwriter.core.InstallerSpec
import net.metalstrapper.sparkwriter.core.LANGUAGE_TAG_PATTERN
import net.metalstrapper.sparkwriter.core.LocalizedText
import net.metalstrapper.sparkwriter.core.ManifestMetadata
import net.metalstrapper.sparkwriter.core.MailroomCompactArtifactSpec
import net.metalstrapper.sparkwriter.core.MailroomSpec
import net.metalstrapper.sparkwriter.core.OutputSpec
import net.metalstrapper.sparkwriter.core.PaddingSpec
import net.metalstrapper.sparkwriter.core.PackageDocumentRef
import net.metalstrapper.sparkwriter.core.PackageFileRef
import net.metalstrapper.sparkwriter.core.PartitionGeometry
import net.metalstrapper.sparkwriter.core.PlaceholderSpec
import net.metalstrapper.sparkwriter.core.ReturnDeliverySpec
import net.metalstrapper.sparkwriter.core.SharedText
import net.metalstrapper.sparkwriter.core.Source
import net.metalstrapper.sparkwriter.core.SourceAdaptation
import net.metalstrapper.sparkwriter.core.SparkPackageRoot
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.SparkTemplateFilters
import net.metalstrapper.sparkwriter.core.withExternalServicePolicy
import net.metalstrapper.sparkwriter.core.WorkflowAction
import net.metalstrapper.sparkwriter.core.WizardPage
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URI
import java.net.IDN
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ManifestValidationException(message: String) : IllegalArgumentException(message)

class ManifestRuntime(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
    /**
     * Off by default: manifests using return_delivery, Mailroom, field fetch, or
     * prefill buttons load but are marked unavailable. See [externalServiceReasons].
     */
    private val externalServicesEnabled: Boolean = false,
) {
    fun parsePackageRoot(
        raw: String,
        installOrigin: String? = null,
        requireIntegrity: Boolean = true,
    ): SparkPackageRoot {
        val root = parseManifestTree(raw)
        requireKnownKeys(root, PACKAGE_ROOT_SHAPE)
        val version = root.string("version") ?: throw ManifestValidationException("manifest.json missing version")
        if (version !in SUPPORTED_SPARKPLUG_VERSIONS) {
            throw ManifestValidationException("Android supports SparkPlug manifest.json version $SPARKPLUG_VERSION")
        }
        val kind = root.string("kind") ?: throw ManifestValidationException("manifest.json missing kind")
        if (kind != "sparkplug.manifest") {
            throw ManifestValidationException("Android install URLs must point to a SparkPlug manifest.json package root")
        }
        val metadataRoot = root.obj("metadata") ?: throw ManifestValidationException("manifest.json missing metadata")
        val documents = root.obj("documents") ?: throw ManifestValidationException("manifest.json missing documents")
        rejectUnsupportedPackageRootSignature(metadataRoot)
        val metadata = metadataRoot.toMetadata()
        val manifestRootSha256 = metadata.manifestRootSha256
        if (requireIntegrity && manifestRootSha256 == null) throw ManifestValidationException(
            "manifest.json missing metadata.manifest_root_sha256",
        )
        if (manifestRootSha256 != null && !manifestRootSha256.isSha256()) {
            throw ManifestValidationException("manifest.json manifest_root_sha256 must be a lowercase SHA-256 hex string")
        }
        val canonicalRootSha = root.withoutManifestRootSha().canonicalJson().sha256()
        if (requireIntegrity && manifestRootSha256 != canonicalRootSha) {
            throw ManifestValidationException("manifest.json manifest_root_sha256 does not match manifest.json contents")
        }
        val packageRoot = SparkPackageRoot(
            version = version,
            kind = kind,
            metadata = metadata,
            documents = documents.mapValues { it.value.jsonObject.toDocumentRef(requireIntegrity) },
            files = root.obj("files")?.mapValues { it.value.jsonObject.toFileRef(requireIntegrity) }.orEmpty(),
            installOrigin = installOrigin,
            manifestRootSha256 = manifestRootSha256,
        )
        validatePackageRoot(packageRoot, requireIntegrity)
        return packageRoot
    }

    /**
     * Parses a SparkPlug from its two documents: the workflow (what to ask and render) and
     * the image (the base image, its slots, and how to patch it). The workflow's `fill`
     * map assigns rendered artifacts to the image's slots.
     */
    fun parse(
        workflowRaw: String,
        imageRaw: String,
        installOrigin: String? = null,
        packageRootSha256: String? = null,
    ): SparkManifest {
        val workflow = parseDocument(workflowRaw, WORKFLOW_KIND, WORKFLOW_SHAPE)
        val image = parseDocument(imageRaw, IMAGE_KIND, IMAGE_SHAPE)
        return buildManifest(composeWorkflowAndImage(workflow, image), installOrigin, packageRootSha256)
    }

    /** The image id a workflow document was written for (its `image` key). */
    fun workflowImageId(workflowRaw: String): String =
        parseDocument(workflowRaw, WORKFLOW_KIND, WORKFLOW_SHAPE).string("image")
            ?: throw ManifestValidationException("Workflow missing image")

    /** The id of an image document (its `metadata.id`). */
    fun imageDocumentId(imageRaw: String): String =
        parseDocument(imageRaw, IMAGE_KIND, IMAGE_SHAPE).obj("metadata")?.string("id")
            ?: throw ManifestValidationException("Image missing metadata.id")

    private fun parseDocument(raw: String, kind: String, shape: Obj): JsonObject {
        val root = parseManifestTree(raw)
        val label = kind.substringAfter('.')
        rejectLegacySecureKeys(root)
        requireKnownKeys(root, shape)
        val version = root.string("version") ?: throw ManifestValidationException("SparkPlug $label missing version")
        if (version !in SUPPORTED_SPARKPLUG_VERSIONS) {
            throw ManifestValidationException("Android supports SparkPlug $label version $SPARKPLUG_VERSION")
        }
        val actualKind = root.string("kind") ?: throw ManifestValidationException("SparkPlug $label missing kind")
        if (actualKind != kind) {
            throw ManifestValidationException("Expected a $kind document, found $actualKind")
        }
        return root
    }

    private fun buildManifest(root: JsonObject, installOrigin: String?, packageRootSha256: String?): SparkManifest {
        val version = root.string("version") ?: throw ManifestValidationException("Manifest missing version")
        val metadataRoot = root.obj("metadata") ?: throw ManifestValidationException("Manifest missing metadata")
        val metadata = metadataRoot.toMetadata()

        val manifest = SparkManifest(
            version = version,
            metadata = metadata,
            source = root.obj("source")?.toSource(),
            outputs = root.obj("outputs")?.toOutputs() ?: OutputSpec(),
            installer = root.obj("installer")?.toInstaller(),
            artifacts = root.obj("artifacts")?.mapValues { it.value.jsonObject.toArtifactSpec(it.key) }.orEmpty(),
            delivery = root.obj("delivery")?.toDelivery(),
            mailroom = root.obj("mailroom")?.toMailroom(),
            bootPatch = root.obj("boot_patch")?.toBootPatch(),
            actionPhases = root.obj("actions")?.mapValues { phase ->
                (phase.value as? JsonArray)?.map { it.jsonObject.toWorkflowAction() }.orEmpty()
            }.orEmpty(),
            hostEvents = root.parseHostEvents(),
            configFields = root.array("config_fields").map { it.toConfigFieldSpec() },
            wizardPages = root.parseWizardPages(),
            form = root.obj("form")?.toFormSpec() ?: FormSpec(),
            uiVisibility = root.obj("ui_visibility")?.conditions("when").orEmpty(),
            templates = root.obj("templates")?.mapValues { it.value.templateString() }.orEmpty(),
            installOrigin = installOrigin,
            packageRootSha256 = packageRootSha256,
        ).validateWizard().validateContract()
        return manifest.copy(
            unsupportedReasons = (manifest.androidUnavailableReasons() + manifest.androidMailroomNetbootReasons()).distinct(),
        ).withExternalServicePolicy(externalServicesEnabled)
    }

    /**
     * Attaches package template files to a parsed manifest. They get the same
     * placeholder validation and external-service policy as inline templates.
     */
    fun withTemplates(manifest: SparkManifest, extra: Map<String, String>): SparkManifest {
        if (extra.isEmpty()) return manifest
        extra.forEach { (id, text) -> validateTemplateText(text, "template file $id") }
        return manifest.copy(templates = manifest.templates + extra).withExternalServicePolicy(externalServicesEnabled)
    }

    private fun parseManifestTree(raw: String): JsonObject {
        val trimmed = raw.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            json.parseToJsonElement(raw).jsonObject
        } else {
            yamlToJsonObject(raw)
        }
    }

    private fun yamlToJsonObject(raw: String): JsonObject {
        val loaded = Load(LoadSettings.builder().build()).loadFromString(raw)
        return yamlValueToJson(loaded).jsonObject
    }

    fun validateInstallUrl(url: String): URI {
        val uri = URI(url)
        validateUnicodeHostname(uri)
        if (!uri.isTrustedPackageOrigin()) {
            throw ManifestValidationException("Only HTTPS or local development manifest installs are trusted by default")
        }
        if (!uri.path.endsWith("/manifest.json") && uri.path != "manifest.json") {
            throw ManifestValidationException("Android installs must target manifest.json")
        }
        return uri
    }

    fun resolvePackageReferenceUrl(packageUrl: String, referenceUrl: String): String {
        val packageUri = validateInstallUrl(packageUrl)
        val resolved = packageUri.resolve(referenceUrl)
        validateUnicodeHostname(resolved)
        if (!resolved.isTrustedPackageOrigin()) {
            throw ManifestValidationException("Package references must use HTTPS or local development hosts")
        }
        if (!packageUri.hasSameOriginAs(resolved)) {
            throw ManifestValidationException("Package references must remain on the package origin")
        }
        return resolved.toString()
    }

    fun parseSparkUri(uri: String): String? {
        val parsed = URI(uri)
        if (parsed.scheme != "spark" || parsed.host != "plugin" || parsed.path != "/add") return null
        return parsed.rawQuery
            ?.split("&")
            ?.mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], Charsets.UTF_8) else null
            }
            ?.firstOrNull { it.first == "manifest" || it.first == "url" }
            ?.second
    }

    private fun rejectLegacySecureKeys(root: JsonObject) {
        val legacy = listOf("secure_manifest", "signature").filter(root::containsKey)
        if (legacy.isNotEmpty()) {
            throw ManifestValidationException("Unsupported manifest fields: ${legacy.joinToString()}")
        }
    }

    private fun rejectUnsupportedPackageRootSignature(metadata: JsonObject) {
        if (metadata["signature"] != null) {
            throw ManifestValidationException("manifest.json metadata.signature is no longer supported")
        }
    }
}

const val SPARKPLUG_VERSION = "3.0"
private val SUPPORTED_SPARKPLUG_VERSIONS = setOf(SPARKPLUG_VERSION)
private const val WORKFLOW_KIND = "sparkplug.workflow"
private const val IMAGE_KIND = "sparkplug.image"
private val PACKAGE_DOCUMENT_ROLES = listOf("workflow", "image")

private fun validatePackageRoot(root: SparkPackageRoot, requireIntegrity: Boolean = true) {
    val unsupportedRoles = root.documents.values.map { it.role }.filter { it !in PACKAGE_DOCUMENT_ROLES }.distinct()
    if (unsupportedRoles.isNotEmpty()) {
        throw ManifestValidationException("Unsupported manifest.json document roles: ${unsupportedRoles.joinToString()}")
    }
    if (root.documents.values.map { it.role }.sorted() != PACKAGE_DOCUMENT_ROLES.sorted()) {
        throw ManifestValidationException("manifest.json must contain exactly one workflow document and one image document")
    }
    root.documents.values.forEach { validatePackageReference(it.sha256, it.url, it.inline, requireIntegrity) }
    root.files.values.forEach { validatePackageReference(it.sha256, it.url, it.inline, requireIntegrity) }
}

private val IMAGE_SECTIONS = listOf("source", "outputs", "installer", "delivery", "boot_patch")
private val WORKFLOW_ONLY_KEYS = setOf("kind", "image", "fill")

/**
 * Merges a workflow and its image into the one tree the manifest builder reads.
 * The image's slots are its delivery patches (by id) and auxiliary files (by path).
 * The workflow's `fill` map names the artifact written into each slot.
 */
private fun composeWorkflowAndImage(workflow: JsonObject, image: JsonObject): JsonObject {
    val imageId = image.obj("metadata")?.string("id") ?: throw ManifestValidationException("Image missing metadata.id")
    val wantedImage = workflow.string("image") ?: throw ManifestValidationException("Workflow missing image")
    if (wantedImage != imageId) {
        throw ManifestValidationException("Workflow is for image $wantedImage, but the package image is $imageId")
    }
    val fill = workflow.obj("fill")?.mapValues { (slot, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw ManifestValidationException("fill.$slot must name an artifact")
    }.orEmpty()

    val delivery = image.obj("delivery")
    val patchSlots = delivery?.get("patches").objectList()
    val fileSlots = delivery?.obj("files").orEmpty()
    val patchIds = patchSlots.map { it.string("id") ?: throw ManifestValidationException("Image delivery patch missing id") }
    val slotIds = patchIds + fileSlots.keys
    val duplicates = slotIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw ManifestValidationException("Image declares duplicate slots: ${duplicates.sorted().joinToString()}")
    }
    val unknownSlots = fill.keys - slotIds.toSet()
    if (unknownSlots.isNotEmpty()) {
        throw ManifestValidationException("fill names slots the image does not declare: ${unknownSlots.sorted().joinToString()}")
    }
    val artifactIds = workflow.declaredArtifactIds()
    fill.forEach { (slot, artifact) ->
        if (artifact !in artifactIds) throw ManifestValidationException("fill.$slot names unknown artifact $artifact")
    }

    fun JsonObject.filled(slot: String): JsonObject {
        val artifact = fill[slot] ?: run {
            if (boolean("optional")) return this
            throw ManifestValidationException("Image slot $slot is not filled by the workflow")
        }
        return JsonObject(this + ("artifact_id" to JsonPrimitive(artifact)))
    }
    val composedDelivery = delivery?.let { original ->
        JsonObject(
            original + buildMap {
                if ("patches" in original) put("patches", JsonArray(patchSlots.mapIndexed { i, patch -> patch.filled(patchIds[i]) }))
                if ("files" in original) put("files", JsonObject(fileSlots.mapValues { (path, file) -> file.jsonObject.filled(path) }))
            },
        )
    }
    return JsonObject(
        buildMap {
            workflow.forEach { (key, value) -> if (key !in WORKFLOW_ONLY_KEYS) put(key, value) }
            IMAGE_SECTIONS.forEach { key -> image[key]?.let { put(key, it) } }
            composedDelivery?.let { put("delivery", it) }
        },
    )
}

/** Artifacts a workflow can put into a slot: declared artifacts and create_artifact outputs. */
private fun JsonObject.declaredArtifactIds(): Set<String> = buildSet {
    obj("artifacts")?.forEach { (key, value) ->
        add(key)
        (value as? JsonObject)?.string("artifact_id")?.let(::add)
    }
    obj("actions")?.values?.forEach { phase ->
        phase.objectList().forEach { action -> action.string("artifact_id")?.let(::add) }
    }
}

/** A list of objects, also accepting a single object where a list is allowed. */
private fun JsonElement?.objectList(): List<JsonObject> = when (this) {
    is JsonArray -> map { it.jsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

private fun validatePackageReference(sha256: String, url: String?, inline: InlineText?, requireIntegrity: Boolean) {
    if (requireIntegrity && !sha256.isSha256()) {
        throw ManifestValidationException("Package references must use lowercase 64-character SHA-256 hashes")
    }
    val hasUrl = url != null
    val hasInline = inline != null
    if (hasUrl == hasInline) {
        throw ManifestValidationException("Package references must define exactly one of url or inline")
    }
    url?.let(::validatePackageReferenceUrl)
    inline?.let {
        if (it.encoding != "utf-8") throw ManifestValidationException("Inline package references must use utf-8")
    }
}

private fun validatePackageReferenceUrl(url: String) {
    val uri = URI(url)
    if (uri.isAbsolute) validateUnicodeHostname(uri)
    if (uri.isAbsolute && !uri.isTrustedPackageOrigin()) {
        throw ManifestValidationException("Absolute package sidecar references must use HTTPS or local development hosts")
    }
    if (!uri.isAbsolute && (url.startsWith("/") || url.contains(".."))) {
        throw ManifestValidationException("Relative package references must stay inside the package path")
    }
}

private fun validateUnicodeHostname(uri: URI) {
    uri.toString().codePoints().forEach { codePoint ->
        when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> throw ManifestValidationException("URL contains invisible, control, or nonstandard whitespace characters")
        }
    }
    val authority = uri.rawAuthority ?: throw ManifestValidationException("URL is missing a hostname")
    if (authority.contains('@')) throw ManifestValidationException("URL user information is not allowed")
    val hostname = when {
        authority.startsWith("[") -> authority.substringBefore(']') + "]"
        else -> authority.substringBeforeLast(':').takeIf { authority.substringAfterLast(':').all(Char::isDigit) }
            ?: authority
    }
    if (hostname.startsWith("[")) return // IPv6 contains no Unicode hostname labels.
    if ('%' in hostname) throw ManifestValidationException("URL hostnames must not contain percent escapes")

    hostname.split('.').forEach { label ->
        if (label.isEmpty()) throw ManifestValidationException("URL hostname contains an empty label")
        val writingSystems = label.codePoints().toArray().asSequence().mapNotNull { codePoint ->
            when (Character.getType(codePoint)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.SPACE_SEPARATOR.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> throw ManifestValidationException("URL hostname contains invisible or control characters")
            }
            Character.UnicodeScript.of(codePoint).takeUnless {
                it == Character.UnicodeScript.COMMON || it == Character.UnicodeScript.INHERITED
            }
        }.toSet()
        val permittedEastAsianCombination = writingSystems.all {
            it in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
            )
        } || writingSystems.all {
            it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HANGUL
        }
        if (writingSystems.size > 1 && !permittedEastAsianCombination) {
            throw ManifestValidationException("URL hostname label mixes writing systems: $label")
        }
        runCatching { IDN.toASCII(label, IDN.USE_STD3_ASCII_RULES) }.getOrElse {
            throw ManifestValidationException("URL hostname label is not a valid internationalized name: $label")
        }
    }
}

private fun URI.isTrustedPackageOrigin(): Boolean {
    if (scheme == "https") return true
    if (scheme != "http") return false
    val normalizedHost = host?.trim('[', ']')?.lowercase() ?: return false
    return normalizedHost in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2", "10.0.3.2") ||
        normalizedHost.isTrustedAndroidDeviceSubnetHost() ||
        normalizedHost == "penguin.linux.test" ||
        normalizedHost.endsWith(".linux.test")
}

private fun URI.hasSameOriginAs(other: URI): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun String.isTrustedAndroidDeviceSubnetHost(): Boolean {
    val octets = split(".")
    if (octets.size != 4) return false
    return octets[0] == "100" &&
        octets[1] == "115" &&
        octets[2] == "92" &&
        octets[3].toIntOrNull() in 0..255
}

private fun yamlValueToJson(value: Any?): JsonElement = when (value) {
    null -> JsonPrimitive("")
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        value.forEach { (key, child) ->
            if (key != null) put(key.toString(), yamlValueToJson(child))
        }
    }
    is Iterable<*> -> buildJsonArray {
        value.forEach { add(yamlValueToJson(it)) }
    }
    else -> JsonPrimitive(value.toString())
}

private fun SparkManifest.validateWizard(): SparkManifest {
    if (wizardPages.isEmpty()) {
        return copy(
            wizardPages = listOf(
                WizardPage(
                    id = "configuration",
                    title = "${metadata.name} Configuration",
                    fields = configFields.filterNot { it.hidden }.map { it.id },
                ),
            ),
        )
    }
    val fieldIds = configFields.map { it.id }.toSet()
    val seenPages = mutableSetOf<String>()
    val seenFields = mutableSetOf<String>()
    wizardPages.forEach { page ->
        if (page.id.isBlank()) throw ManifestValidationException("Wizard page is missing id")
        if (page.title.isBlank()) {
            throw ManifestValidationException("Wizard page ${page.id} is missing title")
        }
        if (!seenPages.add(page.id)) throw ManifestValidationException("Duplicate wizard page: ${page.id}")
        page.fields.forEach { fieldId ->
            if (fieldId !in fieldIds) {
                throw ManifestValidationException("Wizard page ${page.id} references unknown field $fieldId")
            }
            if (!seenFields.add(fieldId)) {
                throw ManifestValidationException("Field $fieldId appears on more than one wizard page")
            }
        }
    }
    val unassignedFields = configFields
        .filterNot { it.hidden }
        .map { it.id }
        .filterNot(seenFields::contains)
    if (unassignedFields.isNotEmpty()) {
        throw ManifestValidationException(
            "Wizard pages do not assign visible fields: ${unassignedFields.joinToString()}",
        )
    }
    return this
}

private val SUPPORTED_ACTION_PHASES = setOf("on_iso_ready", "on_write_ready", "on_write_complete", "on_workflow_started")
private val SUPPORTED_ACTION_TYPES = setOf("generate_ephemeral_password", "hash_password", "render_template", "create_artifact")
private val SUPPORTED_HASH_ALGORITHMS = setOf("sha512_crypt", "sha512-crypt", "sha512crypt")
private val SUPPORTED_HOST_EVENTS = setOf("write_ready", "write_complete", "workflow.started")
private val SUPPORTED_FIELD_TYPES = setOf("text", "password", "select", "multiline", "array", "info")
private val SUPPORTED_VISIBILITY_SELECTORS = setOf("source_family", "source_id", "source_capabilities")
private val SUPPORTED_ACQUIRE_KINDS = setOf("direct", "torrent")
private val SUPPORTED_PLACEHOLDER_ENCODINGS = setOf("zeroes", "metalstrapper-patch-slot")
private val SUPPORTED_PADDING_STRATEGIES = setOf("zeroes", "space", "toml-comment", "shell-comment")

// Android never skips what it does not understand: an unknown value fails the load.
private fun requireSupported(value: String, supported: Set<String>, what: String) {
    if (value !in supported) throw ManifestValidationException("Unsupported $what: $value")
}

private fun SparkManifest.validateSupportedValues() {
    actionPhases.forEach { (phase, actions) ->
        requireSupported(phase, SUPPORTED_ACTION_PHASES, "action phase")
        actions.forEach { action ->
            requireSupported(action.type, SUPPORTED_ACTION_TYPES, "action type")
            action.algorithm?.let { requireSupported(it, SUPPORTED_HASH_ALGORITHMS, "hash_password algorithm") }
        }
    }
    configFields.forEach { requireSupported(it.type, SUPPORTED_FIELD_TYPES, "config field type") }
    uiVisibility.forEach { requireSupported(it.field, SUPPORTED_VISIBILITY_SELECTORS, "ui_visibility selector") }
    source?.acquire?.let { requireSupported(it.kind.lowercase(), SUPPORTED_ACQUIRE_KINDS, "source acquire kind") }
}

private fun SparkManifest.validateContract(): SparkManifest {
    validateSupportedValues()
    validateConfigFieldLengths()
    validateConfigFieldPatterns()
    validateTemplatePlaceholders()
    validateFormPrefillContract()
    validateHostEvents()
    validateMailroomContract()
    bootPatch?.let(::validateBootPatchContract)
    val delivery = delivery ?: run {
        if (bootPatch?.requiresAppendAfterImageAuxiliary == true) {
            throw ManifestValidationException("boot_patch requires append_after_image auxiliary delivery")
        }
        return this
    }
    val installer = installer ?: throw ManifestValidationException("Delivery requires an installer declaration")
    if (delivery.mode !in installer.acceptsDelivery) {
        throw ManifestValidationException("Installer ${installer.scheme} does not accept ${delivery.mode} delivery")
    }
    when (delivery.mode) {
        "auxiliary_partition" -> validateAuxiliaryDeliveryContract(delivery, installer)
        "fixed_byte_ranges" -> validateFixedByteRangeContract(delivery)
        else -> throw ManifestValidationException("Unsupported Android delivery mode: ${delivery.mode}")
    }
    if (bootPatch?.requiresAppendAfterImageAuxiliary == true && delivery.mode != "auxiliary_partition") {
        throw ManifestValidationException("boot_patch requires append_after_image auxiliary delivery")
    }
    return this
}

private val MAX_LENGTH_FIELD_TYPES = setOf("text", "password", "multiline", "array")

private fun SparkManifest.validateConfigFieldLengths() {
    configFields.forEach { field ->
        val maxLength = field.maxLength ?: return@forEach
        if (field.type !in MAX_LENGTH_FIELD_TYPES) {
            throw ManifestValidationException("config field ${field.id} max_length is not supported for type ${field.type}")
        }
        if (field.default.length > maxLength) {
            throw ManifestValidationException("config field ${field.id} default is longer than its max_length")
        }
    }
}

private fun SparkManifest.validateConfigFieldPatterns() {
    configFields.forEach { field ->
        val pattern = field.pattern ?: return@forEach
        if (field.type !in MAX_LENGTH_FIELD_TYPES) {
            throw ManifestValidationException("config field ${field.id} pattern is not supported for type ${field.type}")
        }
        val regex = try {
            Regex(pattern)
        } catch (error: IllegalArgumentException) {
            throw ManifestValidationException("config field ${field.id} pattern is not a valid regular expression")
        }
        if (field.default.isNotEmpty() && !regex.matches(field.default)) {
            throw ManifestValidationException("config field ${field.id} default does not match its pattern")
        }
    }
}

private fun SparkManifest.validateTemplatePlaceholders() {
    templates.forEach { (id, template) -> validateTemplateText(template, "templates.$id") }
    hostEvents.forEach { subscription ->
        subscription.returnDelivery?.let { validateTemplateText(it.url, "return_delivery url for ${subscription.event}") }
    }
}

// Every {{ ... }} must be a well-formed placeholder with known filters, so a typo
// fails the load instead of reaching the USB drive as literal text.
internal fun validateTemplateText(template: String, where: String) {
    SparkTemplateFilters.ANY_PLACEHOLDER.findAll(template).forEach { span ->
        val placeholder = SparkTemplateFilters.PLACEHOLDER.matchEntire(span.value)
            ?: throw ManifestValidationException("$where has a malformed placeholder: ${span.value}")
        val filters = SparkTemplateFilters.parseChain(placeholder.groupValues[2])
        filters.forEach { requireSupported(it, SparkTemplateFilters.names, "template filter in $where") }
        val quoting = filters.lastOrNull { it in SparkTemplateFilters.QUOTING_FILTERS } ?: return@forEach
        val before = template.getOrNull(span.range.first - 1)
        val after = template.getOrNull(span.range.last + 1)
        if (before in QUOTE_CHARS || after in QUOTE_CHARS) {
            throw ManifestValidationException(
                "$where wraps ${span.value} in quotes; $quoting already emits a quoted literal",
            )
        }
    }
}

private val QUOTE_CHARS = setOf('\'', '"')

private fun SparkManifest.validateFormPrefillContract() {
    val fieldsById = configFields.associateBy { it.id }
    fun requirePrefillTarget(sourceLabel: String, fieldId: String) {
        val field = fieldsById[fieldId] ?: throw ManifestValidationException("$sourceLabel references unknown field $fieldId")
        if (field.hidden) {
            throw ManifestValidationException("$sourceLabel references hidden field $fieldId")
        }
    }
    form.hostProfiles.forEach { profile ->
        if (profile.id.isBlank()) throw ManifestValidationException("Host profile is missing id")
        if (profile.label.isBlank()) throw ManifestValidationException("Host profile ${profile.id} is missing label")
        profile.fields.keys.forEach { fieldId ->
            requirePrefillTarget("Host profile ${profile.id}", fieldId)
        }
        profile.immutableFields.forEach { fieldId ->
            requirePrefillTarget("Host profile ${profile.id} immutable_fields", fieldId)
        }
    }
    form.buttons.forEach { button ->
        if (button.id.isBlank()) throw ManifestValidationException("Form prefill button is missing id")
        if (button.label.isBlank()) throw ManifestValidationException("Form prefill button ${button.id} is missing label")
        if (button.fieldBindings.isEmpty()) {
            throw ManifestValidationException("Form prefill button ${button.id} is missing field_bindings")
        }
        button.fieldBindings.keys.forEach { fieldId ->
            requirePrefillTarget("Form prefill button ${button.id}", fieldId)
        }
        button.immutableFields.forEach { fieldId ->
            requirePrefillTarget("Form prefill button ${button.id} immutable_fields", fieldId)
        }
    }
}

private fun SparkManifest.validateMailroomContract() {
    mailroom?.compactArtifacts.orEmpty().forEachIndexed { index, artifact ->
        if (!artifact.id.matches(MAILROOM_COMPACT_ARTIFACT_ID)) {
            throw ManifestValidationException("mailroom.compact_artifacts[$index].id must be a safe artifact id")
        }
        artifact.readProtocol?.let { protocol ->
            if (protocol !in setOf("http", "https")) {
                throw ManifestValidationException("mailroom.compact_artifacts[$index].read_protocol must be http or https")
            }
        }
        artifact.readBaseUrl?.let { validateCompactArtifactBaseUrl(it, "mailroom.compact_artifacts[$index].read_base_url") }
        artifact.writeBaseUrl?.let { writeBaseUrl ->
            val field = "mailroom.compact_artifacts[$index].write_base_url"
            validateCompactArtifactBaseUrl(writeBaseUrl, field)
            if (!writeBaseUrl.startsWith("https://")) {
                throw ManifestValidationException("$field must use HTTPS")
            }
        }
    }
}

private val MAILROOM_COMPACT_ARTIFACT_ID = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}""")

private fun validateCompactArtifactBaseUrl(url: String, field: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) {
        throw ManifestValidationException("$field must be an absolute HTTP(S) URL")
    }
    if (url.endsWith("/")) {
        throw ManifestValidationException("$field must not end with /")
    }
}

private const val MAILROOM_NETBOOT_RENDEZVOUS_PATH = "/netboot/v1/rendezvous/"

private fun SparkManifest.androidMailroomNetbootReasons(): List<String> {
    if (!requiresMailroomNetbootParticipation()) return emptyList()
    val hasCapability = source?.capabilities?.any { it == "netboot-rendezvous" } ?: false
    if (hasCapability) return emptyList()
    return listOf("Source capabilities must include netboot-rendezvous for mailroom netboot patches")
}

private fun SparkManifest.requiresMailroomNetbootParticipation(): Boolean {
    val source = source ?: return false
    if (!source.family.equals("netboot", ignoreCase = true)) return false
    val bootPatch = bootPatch ?: return false
    if (bootPatch.scriptPatches.any { it.script.contains(MAILROOM_NETBOOT_RENDEZVOUS_PATH) }) return true
    if (bootPatch.placeholderPatches.any { it.replacement.contains(MAILROOM_NETBOOT_RENDEZVOUS_PATH) }) return true
    return false
}

private fun SparkManifest.validateHostEvents() {
    hostEvents.forEach { subscription ->
        if (subscription.event.isBlank()) {
            throw ManifestValidationException("host_events entries must declare an event")
        }
        requireSupported(subscription.event, SUPPORTED_HOST_EVENTS, "host event")
        subscription.returnDelivery?.let { delivery ->
            if (delivery.url.isBlank()) {
                throw ManifestValidationException("return_delivery for ${subscription.event} is missing url")
            }
            if (delivery.deliveryMode !in setOf("required", "best_effort")) {
                throw ManifestValidationException("Unsupported return_delivery delivery_mode: ${delivery.deliveryMode}")
            }
            if (delivery.method !in setOf("POST", "PUT")) {
                throw ManifestValidationException("Unsupported return_delivery method: ${delivery.method}")
            }
        }
    }
}

private fun SparkManifest.validateAuxiliaryDeliveryContract(delivery: DeliverySpec, installer: InstallerSpec) {
    val partition = delivery.partition ?: throw ManifestValidationException("Auxiliary delivery is missing partition metadata")
    val installerPartition = installer.auxiliaryPartition
        ?: throw ManifestValidationException("Installer ${installer.scheme} is missing auxiliary partition metadata")
    if (partition.label != installerPartition.label) {
        throw ManifestValidationException("Auxiliary partition label mismatch")
    }
    if (!partition.filesystem.equals(installerPartition.filesystem, ignoreCase = true)) {
        throw ManifestValidationException("Auxiliary partition filesystem mismatch")
    }
    val sourceHash = source?.sha256?.ifBlank { null }
    when (partition.placement) {
        "preallocated_in_base_image" -> {
            if (sourceHash == null) throw ManifestValidationException("Auxiliary delivery requires source.sha256")
            val geometry = partition.geometry
                ?: throw ManifestValidationException("Auxiliary delivery is missing partition geometry")
            validateGeometryContract(geometry, partition.sizeMb)
            if (!geometry.baseImageSha256.equals(sourceHash, ignoreCase = true)) {
                throw ManifestValidationException("Auxiliary geometry hash does not match source.sha256")
            }
        }
        "append_after_image" -> {
            if (!partition.filesystem.equals("vfat", ignoreCase = true)) {
                throw ManifestValidationException("Dynamic auxiliary delivery supports only vfat")
            }
            if (sourceHash == null && !isPatchableAppendAdapter()) {
                throw ManifestValidationException("Unpinned append auxiliary delivery requires a patchable import boot_patch")
            }
        }
        else -> throw ManifestValidationException("Unsupported auxiliary partition placement: ${partition.placement}")
    }
    delivery.files.keys.forEach(::validateDeliveryPathContract)
    if (bootPatch?.requiresAppendAfterImageAuxiliary == true && partition.placement != "append_after_image") {
        throw ManifestValidationException("boot_patch requires append_after_image auxiliary delivery")
    }
}

private fun SparkManifest.isPatchableAppendAdapter(): Boolean {
    val source = source ?: return false
    val adaptation = source.adaptation ?: return false
    return source.userSupplied &&
        adaptation.status == "patchable_on_import" &&
        bootPatch?.requiresAppendAfterImageAuxiliary == true
}

private val BootPatchSpec.requiresAppendAfterImageAuxiliary: Boolean
    get() {
        return kernelArgPatches.any { patch ->
            patch.ensureArgs.any { arg ->
                arg.startsWith("file=") || arg.startsWith("preseed/file=") || arg.startsWith("ds=nocloud")
            }
        }
    }

private fun validateBootPatchContract(bootPatch: BootPatchSpec) {
    if (bootPatch.filesystem != "iso9660") {
        throw ManifestValidationException("Unsupported boot_patch filesystem: ${bootPatch.filesystem}")
    }
    if (
        bootPatch.textPatches.isEmpty() &&
        bootPatch.kernelArgPatches.isEmpty() &&
        bootPatch.scriptPatches.isEmpty() &&
        bootPatch.placeholderPatches.isEmpty()
    ) {
        throw ManifestValidationException("boot_patch requires text_patches, kernel_arg_patches, script_patches, or placeholder_patches")
    }
    bootPatch.textPatches.forEachIndexed(::validateTextPatchContract)
    bootPatch.kernelArgPatches.forEachIndexed(::validateKernelArgPatchContract)
    bootPatch.scriptPatches.forEachIndexed(::validateScriptPatchContract)
    bootPatch.placeholderPatches.forEachIndexed(::validatePlaceholderPatchContract)
    if (bootPatch.encoding != "utf-8") {
        throw ManifestValidationException("Unsupported boot_patch encoding: ${bootPatch.encoding}")
    }
}

private fun validateTextPatchContract(index: Int, patch: BootTextPatchSpec) {
    if (patch.kind != "replace_once") {
        throw ManifestValidationException("Unsupported boot_patch text_patches[$index] kind: ${patch.kind}")
    }
    validateBootPatchPaths(patch.candidatePaths, "boot_patch.text_patches[$index]")
    if (patch.find.isEmpty()) {
        throw ManifestValidationException("boot_patch text_patches[$index] requires find")
    }
}

private fun validatePlaceholderPatchContract(index: Int, patch: BootPlaceholderPatchSpec) {
    validateBootPatchPaths(patch.candidatePaths, "boot_patch.placeholder_patches[$index]")
    if (patch.encoding !in setOf("ascii", "utf-8")) {
        throw ManifestValidationException("boot_patch placeholder_patches[$index] supports encoding: ascii or utf-8")
    }
    if (patch.padding != "space") {
        throw ManifestValidationException("boot_patch placeholder_patches[$index] supports only padding: space")
    }
    if (patch.placeholder.isBlank()) {
        throw ManifestValidationException("boot_patch placeholder_patches[$index] requires placeholder")
    }
    if (patch.encoding == "ascii" && (!patch.placeholder.isAscii() || !patch.replacement.isAscii())) {
        throw ManifestValidationException("boot_patch placeholder_patches[$index] requires ASCII placeholder and replacement")
    }
    val placeholderBytes = patch.placeholder.toByteArray(patch.placeholderCharset())
    val replacementBytes = patch.replacement.toByteArray(patch.placeholderCharset())
    if (replacementBytes.size > placeholderBytes.size) {
        throw ManifestValidationException("boot_patch placeholder_patches[$index] replacement exceeds placeholder byte length")
    }
}

private fun BootPlaceholderPatchSpec.placeholderCharset(): java.nio.charset.Charset = when (encoding) {
    "ascii" -> Charsets.US_ASCII
    "utf-8" -> Charsets.UTF_8
    else -> error("Unsupported placeholder encoding: $encoding")
}

private fun validateScriptPatchContract(index: Int, patch: BootScriptPatchSpec) {
    if (patch.kind != "netboot_xyz_autoexec") {
        throw ManifestValidationException("Unsupported boot_patch script_patches[$index] kind: ${patch.kind}")
    }
    validateBootPatchPaths(patch.candidatePaths, "boot_patch.script_patches[$index]")
    val espImagePath = patch.espImagePath ?: throw ManifestValidationException(
        "boot_patch script_patches[$index] requires esp_image_path",
    )
    validateBootPatchPaths(listOf(espImagePath), "boot_patch.script_patches[$index].esp_image_path")
    val espScriptPath = patch.espScriptPath ?: throw ManifestValidationException(
        "boot_patch script_patches[$index] requires esp_script_path",
    )
    if (!espScriptPath.startsWith("/") || espScriptPath == "/" || espScriptPath.split('/').any { it == ".." }) {
        throw ManifestValidationException("Invalid boot_patch script_patches[$index] esp_script_path: $espScriptPath")
    }
    if (patch.script.isBlank()) {
        throw ManifestValidationException("boot_patch script_patches[$index] requires script")
    }
    if (!patch.script.startsWith("#!ipxe")) {
        throw ManifestValidationException("boot_patch script_patches[$index] script must start with #!ipxe")
    }
}

private fun validateKernelArgPatchContract(index: Int, patch: BootKernelArgPatchSpec) {
    validateBootPatchPaths(patch.candidatePaths, "boot_patch.kernel_arg_patches[$index]")
    if (patch.configFamily !in setOf("grub", "isolinux", "syslinux")) {
        throw ManifestValidationException("Unsupported boot_patch kernel_arg_patches[$index] config_family: ${patch.configFamily}")
    }
    if (patch.ensureArgs.isEmpty() || patch.ensureArgs.any { it.isBlank() }) {
        throw ManifestValidationException("boot_patch kernel_arg_patches[$index] requires non-blank ensure_args")
    }
    if (patch.removeArgs.any { it.isBlank() }) {
        throw ManifestValidationException("boot_patch kernel_arg_patches[$index] remove_args must be non-blank")
    }
    if (patch.removeLinesContaining.any { it.isBlank() }) {
        throw ManifestValidationException("boot_patch kernel_arg_patches[$index] remove_lines_containing must be non-blank")
    }
    if (patch.insertBefore != "---") {
        throw ManifestValidationException("boot_patch kernel_arg_patches[$index] supports only insert_before: ---")
    }
    when (patch.configFamily) {
        "grub" -> {
            if (patch.selector.kernel.isNullOrBlank()) {
                throw ManifestValidationException("grub kernel_arg_patches[$index] requires selector.kernel")
            }
            if (patch.selector.menuentry.isNullOrBlank() && patch.selector.argsContains.isEmpty()) {
                throw ManifestValidationException("grub kernel_arg_patches[$index] requires selector.menuentry or selector.args_contains")
            }
            if (patch.selector.argsContains.any { it.isBlank() }) {
                throw ManifestValidationException("grub kernel_arg_patches[$index] selector.args_contains must be non-blank")
            }
        }
        "isolinux", "syslinux" -> {
            if (patch.selector.label.isNullOrBlank()) {
                throw ManifestValidationException("${patch.configFamily} kernel_arg_patches[$index] requires selector.label")
            }
        }
    }
}

private fun validateBootPatchPaths(paths: List<String>, owner: String) {
    if (paths.isEmpty()) {
        throw ManifestValidationException("$owner requires candidate_paths")
    }
    paths.forEach { path ->
        if (!path.startsWith("/") || path == "/" || path.split('/').any { it == ".." }) {
            throw ManifestValidationException("Invalid $owner candidate path: $path")
        }
    }
}

private fun validateGeometryContract(geometry: PartitionGeometry, sizeMb: Int) {
    if (!geometry.baseImageSha256.isSha256()) {
        throw ManifestValidationException("Auxiliary geometry base_image_sha256 must be a SHA-256 hash")
    }
    if (geometry.partitionTable != "gpt") throw ManifestValidationException("Android auxiliary delivery supports only GPT")
    if (geometry.partitionNumber <= 0) throw ManifestValidationException("Auxiliary geometry has invalid partition number")
    if (geometry.sectorSize <= 0) throw ManifestValidationException("Auxiliary geometry has invalid sector size")
    if (geometry.startLba < 0 || geometry.byteOffset < 0 || geometry.byteLength <= 0) {
        throw ManifestValidationException("Auxiliary geometry has invalid byte range")
    }
    val expectedByteOffset = geometry.startLba * geometry.sectorSize.toLong()
    if (geometry.byteOffset != expectedByteOffset) {
        throw ManifestValidationException("Auxiliary byte_offset does not equal start_lba * sector_size")
    }
    if (geometry.byteLength < sizeMb.toLong() * 1024L * 1024L) {
        throw ManifestValidationException("Auxiliary geometry is smaller than requested size")
    }
}

private fun SparkManifest.validateFixedByteRangeContract(delivery: DeliverySpec) {
    val sourceHash = source?.sha256?.ifBlank { null }
        ?: throw ManifestValidationException("Fixed byte-range delivery requires source.sha256")
    val deliveryHash = delivery.baseImageSha256?.ifBlank { null }
        ?: throw ManifestValidationException("Fixed byte-range delivery requires base_image_sha256")
    if (!deliveryHash.isSha256()) throw ManifestValidationException("Fixed byte-range base_image_sha256 must be a SHA-256 hash")
    if (!deliveryHash.equals(sourceHash, ignoreCase = true)) {
        throw ManifestValidationException("Fixed byte-range delivery hash does not match source.sha256")
    }
    if (delivery.patches.isEmpty()) throw ManifestValidationException("Fixed byte-range delivery requires patches")
    delivery.patches.forEach { patch ->
        if (patch.id.isBlank()) throw ManifestValidationException("Fixed byte-range patch is missing id")
        if (patch.byteOffset < 0 || patch.byteLength <= 0) {
            throw ManifestValidationException("Fixed byte-range patch ${patch.id} has invalid byte range")
        }
        patch.placeholder?.sha256?.let {
            if (!it.isSha256()) throw ManifestValidationException("Fixed byte-range placeholder hash must be SHA-256")
        }
        patch.placeholder?.encoding?.let {
            requireSupported(it, SUPPORTED_PLACEHOLDER_ENCODINGS, "fixed byte-range placeholder encoding")
        }
        patch.padding?.strategy?.let {
            requireSupported(it, SUPPORTED_PADDING_STRATEGIES, "fixed byte-range padding strategy")
        }
    }
    delivery.patches.sortedBy { it.byteOffset }.zipWithNext().forEach { (left, right) ->
        if (left.byteOffset + left.byteLength > right.byteOffset) {
            throw ManifestValidationException("Fixed byte-range patches must not overlap")
        }
    }
}

private fun validateDeliveryPathContract(path: String) {
    if (!path.startsWith("/")) throw ManifestValidationException("Delivery file path must be absolute: $path")
    if (path == "/") throw ManifestValidationException("Delivery file path must name a file")
    if (path.split('/').any { it == ".." }) {
        throw ManifestValidationException("Delivery file path may not contain traversal: $path")
    }
}

private fun SparkManifest.androidUnavailableReasons(): List<String> {
    val source = source ?: return emptyList()
    val installer = installer ?: return emptyList()
    val required = installer.baseImageRequires.toSet()
    if (required.isEmpty()) return emptyList()
    val adaptation = source.adaptation
    if (adaptation == null || adaptation.status == "none") {
        return listOf("Selected source does not declare required image adaptation")
    }
    if (adaptation.status == "produced") {
        return listOf("Adapted base image must be produced by an adapting service; Android local mode cannot produce it")
    }
    val missing = required - adaptation.satisfies.toSet()
    val reasons = mutableListOf<String>()
    if (missing.isNotEmpty()) {
        reasons += "Source adaptation is missing required flags: ${missing.joinToString()}"
    }
    return reasons
}

private fun JsonObject.toSource(): Source = Source(
    id = string("id") ?: "",
    name = string("name") ?: string("id") ?: "Unnamed Source",
    family = string("family"),
    version = string("version"),
    url = string("url")?.ifBlank { null },
    sha256 = string("sha256")?.ifBlank { null },
    sizeBytes = this["size_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
    first64KSha256 = string("first_64k_sha256")?.ifBlank { null },
    pvdSha256 = string("pvd_sha256")?.ifBlank { null },
    mediaType = string("media_type"),
    userSupplied = this["user_supplied"]?.jsonPrimitive?.booleanOrNull ?: false,
    adaptation = obj("adaptation")?.let {
        SourceAdaptation(
            status = it.string("status") ?: "none",
            satisfies = it.array("satisfies").map { value -> value.scalarString() },
            humanRequirements = it.array("human_requirements").map { value -> value.scalarString() },
        )
    },
    acquire = obj("acquire")?.let {
        val acquireUrl = it.string("url")?.ifBlank { null } ?: string("url")?.ifBlank { null }
        acquireUrl?.let { url ->
            AcquireSpec(
                kind = it.string("kind") ?: "direct",
                url = url,
                artifact = it.string("artifact"),
            )
        }
    },
    capabilities = array("capabilities").map { it.scalarString() },
    outputs = obj("outputs")?.toOutputs(),
    metadata = obj("metadata")?.stringMap().orEmpty(),
)

private fun JsonObject.toMetadata(): ManifestMetadata {
    return ManifestMetadata(
        id = string("id") ?: throw ManifestValidationException("Manifest missing metadata.id"),
        manifestRootSha256 = string("manifest_root_sha256"),
        name = string("name") ?: throw ManifestValidationException("Manifest missing metadata.name"),
        version = string("version"),
        author = string("author") ?: string("publisher"),
        description = string("description"),
        homepage = string("homepage"),
        language = string("language")?.also { language ->
            if (!LANGUAGE_TAG_PATTERN.matches(language)) {
                throw ManifestValidationException("metadata.language must be a language tag such as en or pt-BR")
            }
        } ?: "en",
    )
}

private fun JsonObject.toDocumentRef(requireIntegrity: Boolean): PackageDocumentRef = PackageDocumentRef(
    role = string("role") ?: throw ManifestValidationException("Document reference missing role"),
    sha256 = string("sha256") ?: if (requireIntegrity) throw ManifestValidationException("Document reference missing sha256") else "",
    url = string("url"),
    inline = obj("inline")?.toInlineText(),
)

private fun JsonObject.toFileRef(requireIntegrity: Boolean): PackageFileRef = PackageFileRef(
    sha256 = string("sha256") ?: if (requireIntegrity) throw ManifestValidationException("File reference missing sha256") else "",
    url = string("url"),
    inline = obj("inline")?.toInlineText(),
    role = string("role"),
    mediaType = string("media_type"),
    size = this["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
)

private fun JsonObject.toInstaller(): InstallerSpec = InstallerSpec(
    scheme = string("scheme") ?: "",
    acceptsDelivery = array("accepts_delivery").map { it.scalarString() },
    baseImageRequires = array("base_image_requires").map { it.scalarString() },
    auxiliaryPartition = obj("auxiliary_partition")?.let {
        AuxiliaryPartitionSpec(
            label = it.string("label") ?: "",
            filesystem = it.string("filesystem") ?: "vfat",
            requiredFiles = it.array("required_files").map { file -> file.scalarString() },
        )
    },
)

private fun JsonObject.toArtifactSpec(key: String): ArtifactSpec = ArtifactSpec(
    artifactId = string("artifact_id") ?: key,
    mediaType = string("media_type"),
    template = string("template"),
    optional = this["optional"]?.jsonPrimitive?.booleanOrNull ?: false,
)

private fun JsonObject.toDelivery(): DeliverySpec = DeliverySpec(
    mode = string("mode") ?: "",
    id = string("id"),
    after = string("after"),
    baseImageSha256 = string("base_image_sha256"),
    partition = obj("partition")?.let {
        DeliveryPartitionSpec(
            label = it.string("label") ?: "",
            filesystem = it.string("filesystem") ?: "vfat",
            sizeMb = it["size_mb"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 64,
            placement = it.string("placement"),
            geometry = it.obj("geometry")?.toPartitionGeometry(),
        )
    },
    files = obj("files")?.mapValues { it.value.jsonObject.toDeliveryFile() }.orEmpty(),
    patches = array("patches").map { it.jsonObject.toDeliveryPatch() },
)

private fun JsonObject.toMailroom(): MailroomSpec = MailroomSpec(
    compactArtifacts = array("compact_artifacts").map { it.jsonObject.toMailroomCompactArtifact() },
)

private fun JsonObject.toMailroomCompactArtifact(): MailroomCompactArtifactSpec = MailroomCompactArtifactSpec(
    id = string("id") ?: "",
    readProtocol = string("read_protocol"),
    readBaseUrl = string("read_base_url"),
    writeBaseUrl = string("write_base_url"),
)

private fun JsonObject.toBootPatch(): BootPatchSpec = BootPatchSpec(
    filesystem = string("filesystem") ?: "iso9660",
    textPatches = array("text_patches").map { it.jsonObject.toTextPatch() },
    kernelArgPatches = array("kernel_arg_patches").map { it.jsonObject.toKernelArgPatch() },
    scriptPatches = array("script_patches").map { it.jsonObject.toScriptPatch() },
    placeholderPatches = array("placeholder_patches").map { it.jsonObject.toPlaceholderPatch() },
    encoding = string("encoding") ?: "utf-8",
)

private fun JsonObject.toTextPatch(): BootTextPatchSpec = BootTextPatchSpec(
    kind = string("kind") ?: "",
    candidatePaths = array("candidate_paths").map { it.scalarString().normalizeIsoPath() },
    find = string("find") ?: "",
    replace = string("replace") ?: "",
)

private fun JsonObject.toPlaceholderPatch(): BootPlaceholderPatchSpec = BootPlaceholderPatchSpec(
    candidatePaths = array("candidate_paths").map { it.scalarString().normalizeIsoPath() },
    placeholder = string("placeholder") ?: "",
    replacement = string("replacement") ?: "",
    encoding = string("encoding") ?: "ascii",
    padding = string("padding") ?: "space",
)

private fun JsonObject.toScriptPatch(): BootScriptPatchSpec = BootScriptPatchSpec(
    kind = string("kind") ?: "",
    candidatePaths = array("candidate_paths").map { it.scalarString().normalizeIsoPath() },
    espImagePath = string("esp_image_path")?.normalizeIsoPath(),
    espScriptPath = string("esp_script_path"),
    script = string("script") ?: "",
)

private fun JsonObject.toKernelArgPatch(): BootKernelArgPatchSpec = BootKernelArgPatchSpec(
    candidatePaths = array("candidate_paths").map { it.scalarString().normalizeIsoPath() },
    configFamily = string("config_family") ?: "",
    selector = obj("selector")?.toKernelArgSelector() ?: BootKernelArgSelector(),
    ensureArgs = array("ensure_args").map { it.scalarString() },
    removeArgs = array("remove_args").map { it.scalarString() },
    removeLinesContaining = array("remove_lines_containing").map { it.scalarString() },
    insertBefore = string("insert_before") ?: "---",
)

private fun JsonObject.toKernelArgSelector(): BootKernelArgSelector = BootKernelArgSelector(
    menuentry = string("menuentry"),
    label = string("label"),
    kernel = string("kernel"),
    argsContains = array("args_contains").map { it.scalarString() },
)

private fun String.normalizeIsoPath(): String {
    val path = trim()
    if (path.isEmpty() || path.startsWith("/")) return path
    return "/$path"
}

private fun JsonObject.toDeliveryPatch(): DeliveryPatchSpec = DeliveryPatchSpec(
    id = string("id") ?: "",
    from = string("from"),
    artifactId = string("artifact_id"),
    mediaType = string("media_type"),
    logicalName = string("logical_name"),
    byteOffset = this["byte_offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
    byteLength = this["byte_length"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
    placeholder = obj("placeholder")?.let {
        PlaceholderSpec(
            encoding = it.string("encoding"),
            sha256 = it.string("sha256"),
        )
    },
    padding = obj("padding")?.let { PaddingSpec(strategy = it.string("strategy")) },
    optional = this["optional"]?.jsonPrimitive?.booleanOrNull ?: false,
)

private fun JsonObject.toPartitionGeometry(): PartitionGeometry = PartitionGeometry(
    baseImageSha256 = string("base_image_sha256") ?: "",
    partitionTable = string("partition_table") ?: "",
    partitionNumber = this["partition_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
    startLba = this["start_lba"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
    sectorSize = this["sector_size"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
    byteOffset = this["byte_offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
    byteLength = this["byte_length"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
)

private fun JsonObject.toDeliveryFile(): DeliveryFileSpec = DeliveryFileSpec(
    from = string("from"),
    artifactId = string("artifact_id"),
    mediaType = string("media_type"),
    optional = this["optional"]?.jsonPrimitive?.booleanOrNull ?: false,
)

private fun JsonObject.toWorkflowAction(): WorkflowAction {
    val secretLabel = displayText("user_secret_label", "action ${string("id").orEmpty()}")
    return WorkflowAction(
        id = string("id") ?: "",
        type = string("type") ?: "",
        template = string("template"),
        outputVar = string("output_var"),
        artifactId = string("artifact_id"),
        logicalName = string("logical_name"),
        mediaType = string("media_type"),
        contentVar = string("content_var"),
        inputVar = string("input_var"),
        algorithm = string("algorithm"),
        executable = this["executable"]?.jsonPrimitive?.booleanOrNull ?: false,
        userSecret = this["user_secret"]?.jsonPrimitive?.booleanOrNull ?: false,
        userSecretLabel = secretLabel.default,
        whenCondition = obj("when")?.let {
            FieldCondition(
                field = it.string("field") ?: "",
                operator = it.string("operator") ?: "",
                value = it["value"]?.scalarString().orEmpty(),
            )
        },
        localizedText = localizedTextOf("user_secret_label" to secretLabel),
    )
}

private fun JsonObject.parseHostEvents(): List<HostEventSubscription> {
    return when (val hostEvents = this["host_events"]) {
        null -> emptyList()
        is JsonArray -> hostEvents.map { event ->
            if (event !is JsonObject) throw ManifestValidationException("host_events entries must be objects")
            event.toHostEventSubscription()
        }
        is JsonObject -> hostEvents.flatMap { (rawEvent, rawSubscriptions) ->
            val defaultEvent = normalizeHostEvent(rawEvent)
            when (rawSubscriptions) {
                is JsonArray -> rawSubscriptions.map { subscription ->
                    if (subscription !is JsonObject) throw ManifestValidationException("host_events entries must be objects")
                    subscription.toHostEventSubscription(defaultEvent)
                }
                is JsonObject -> listOf(rawSubscriptions.toHostEventSubscription(defaultEvent))
                else -> throw ManifestValidationException("host_events entries must be objects")
            }
        }
        else -> throw ManifestValidationException("host_events must be an array or object")
    }
}

private fun JsonObject.toHostEventSubscription(defaultEvent: String? = null): HostEventSubscription = HostEventSubscription(
    event = normalizeHostEvent(string("event") ?: defaultEvent.orEmpty()),
    returnDelivery = obj("return_delivery")?.toReturnDeliverySpec(),
)

private fun normalizeHostEvent(rawEvent: String): String = when (rawEvent.lowercase().trim()) {
    "on_write_ready" -> "write_ready"
    "on_write_complete" -> "write_complete"
    "write.ready" -> "write_ready"
    "write.completed" -> "write_complete"
    else -> rawEvent.lowercase().trim().removePrefix("on_")
}

private val EXPLICIT_URL_SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")

// Templated URLs have no scheme until rendered; delivery re-checks the rendered URL.
private fun String.requireHttpsReturnDeliveryUrl(): String {
    val scheme = EXPLICIT_URL_SCHEME.find(trim())?.groupValues?.get(1) ?: return this
    if (!scheme.equals("https", ignoreCase = true)) {
        throw ManifestValidationException("return_delivery URLs must use HTTPS")
    }
    return this
}

private fun JsonObject.toReturnDeliverySpec(): ReturnDeliverySpec = ReturnDeliverySpec(
    url = (string("url") ?: "").requireHttpsReturnDeliveryUrl(),
    method = string("method")?.uppercase() ?: "POST",
    deliveryMode = string("delivery_mode") ?: "best_effort",
    template = string("template"),
    from = string("from"),
    contentVar = string("content_var"),
    mediaType = string("media_type"),
)

private fun JsonObject.toInlineText(): InlineText = InlineText(
    encoding = string("encoding") ?: "utf-8",
    text = string("text") ?: "",
)

private fun JsonObject.toOutputs(): OutputSpec = OutputSpec(
    usb = this["usb"]?.jsonPrimitive?.booleanOrNull ?: true,
    iso = this["iso"]?.jsonPrimitive?.booleanOrNull ?: true,
)

private fun JsonElement.toConfigFieldSpec(): ConfigField {
    if (this !is JsonObject) throw ManifestValidationException("config_fields entries must be objects")
    return toConfigField()
}

private fun JsonObject.toConfigField(): ConfigField {
    val id = string("id") ?: string("key") ?: throw ManifestValidationException("config field is missing id")
    val where = "config field $id"
    val label = displayText("label", where)
    val description = displayText("description", where)
    val placeholder = displayText("placeholder", where)
    val options = array("options").map { element ->
        if (element is JsonObject) {
            val optionLabel = element.displayText("label", "$where option")
            ConfigOption(
                value = element["value"]?.scalarString().orEmpty(),
                label = optionLabel.default ?: element["value"]?.scalarString().orEmpty(),
                default = element.boolean("default"),
                placeholder = element.boolean("placeholder"),
                localizedText = localizedTextOf("label" to optionLabel),
            )
        } else {
            ConfigOption(element.scalarString(), element.scalarString())
        }
    }
    return ConfigField(
        id = id,
        label = label.default ?: id.toFieldLabel(),
        type = string("type") ?: "text",
        default = this["default"]?.scalarString()
            ?: options.firstOrNull { it.placeholder }?.value
            ?: options.firstOrNull { it.default }?.value
            ?: "",
        required = this["required"]?.jsonPrimitive?.booleanOrNull ?: false,
        maxLength = maxLength(id),
        pattern = string("pattern"),
        description = description.default,
        placeholder = placeholder.default,
        options = options,
        big = this["big"]?.jsonPrimitive?.booleanOrNull ?: false,
        advanced = this["advanced"]?.jsonPrimitive?.booleanOrNull ?: false,
        hidden = this["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
        immutable = this["immutable"]?.jsonPrimitive?.booleanOrNull ?: false,
        disabled = this["disabled"]?.jsonPrimitive?.booleanOrNull ?: false,
        standardField = string("standard_field"),
        storage = obj("storage")?.stringMap().orEmpty(),
        bindings = array("bindings").map { it.jsonObject.toConfigBinding() },
        fetch = obj("fetch")?.toConfigFetchSpec(),
        whenCondition = obj("when")?.toFieldCondition(),
        localizedText = localizedTextOf("label" to label, "description" to description, "placeholder" to placeholder),
    )
}

private fun JsonObject.maxLength(fieldId: String): Int? {
    val raw = this["max_length"] ?: return null
    val value = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toIntOrNull()
    if (value == null || value <= 0) {
        throw ManifestValidationException("config field $fieldId max_length must be a positive integer")
    }
    return value
}

private fun String.toFieldLabel(): String = split(Regex("[._ ]")).joinToString(" ") { token ->
    token.replaceFirstChar { first -> first.titlecase() }
}.ifBlank { this }

private fun JsonObject.toConfigFetchSpec(): ConfigFetchSpec {
    val url = string("url")?.ifBlank { null }
        ?: throw ManifestValidationException("config field fetch is missing url")
    val method = string("method")?.ifBlank { null } ?: "GET"
    if (!method.equals("GET", ignoreCase = true)) {
        throw ManifestValidationException("config field fetch supports only GET")
    }
    val uri = URI(url)
    if (uri.scheme != "https") {
        throw ManifestValidationException("config field fetch URLs must use HTTPS")
    }
    val maxBytes = this["max_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    if (maxBytes != null && maxBytes <= 0) {
        throw ManifestValidationException("config field fetch max_bytes must be positive")
    }
    return ConfigFetchSpec(
        url = url,
        method = "GET",
        mediaType = string("media_type"),
        maxBytes = maxBytes,
    )
}

private fun JsonObject.toFormSpec(): FormSpec = FormSpec(
    buttons = array("buttons").map {
        if (it !is JsonObject) throw ManifestValidationException("form.buttons entries must be objects")
        it.toFormPrefillButton()
    },
    hostProfiles = array("host_profiles").map {
        if (it !is JsonObject) throw ManifestValidationException("form.host_profiles entries must be objects")
        it.toHostProfile()
    },
)

private fun JsonObject.toFormPrefillButton(): FormPrefillButton {
    val label = displayText("label", "form button ${string("id").orEmpty()}")
    val description = displayText("description", "form button ${string("id").orEmpty()}")
    return FormPrefillButton(
        id = string("id") ?: "",
        label = label.default ?: string("id") ?: "",
        description = description.default,
        fetch = obj("fetch")?.toConfigFetchSpec()
            ?: throw ManifestValidationException("form prefill button is missing fetch"),
        fieldBindings = obj("field_bindings")?.stringMap().orEmpty(),
        immutableFields = array("immutable_fields").map { it.scalarString() },
        whenCondition = obj("when")?.toFieldCondition(),
        localizedText = localizedTextOf("label" to label, "description" to description),
    )
}

private fun JsonObject.toHostProfile(): HostProfile {
    val label = displayText("label", "host profile ${string("id").orEmpty()}")
    val description = displayText("description", "host profile ${string("id").orEmpty()}")
    return HostProfile(
        id = string("id") ?: "",
        label = label.default ?: string("id") ?: "",
        description = description.default,
        fields = obj("fields")?.mapValues { it.value.prefillScalarString() }.orEmpty(),
        immutableFields = array("immutable_fields").map { it.scalarString() },
        localizedText = localizedTextOf("label" to label, "description" to description),
    )
}

private fun JsonObject.toConfigBinding(): ConfigBinding = ConfigBinding(
    consumer = string("consumer") ?: "",
    target = string("target") ?: "",
    destination = string("destination"),
    encoding = string("encoding"),
)

private fun JsonObject.parseWizardPages(): List<WizardPage> {
    val wizard = this["wizard"] ?: return emptyList()
    if (wizard !is JsonObject) throw ManifestValidationException("wizard must be an object")
    val pages = wizard["pages"] ?: return emptyList()
    if (pages !is JsonArray) throw ManifestValidationException("wizard.pages must be an array")
    return pages.map {
        if (it !is JsonObject) throw ManifestValidationException("wizard.pages entries must be objects")
        it.toWizardPage()
    }
}

private fun JsonObject.toWizardPage(): WizardPage {
    val where = "wizard page ${string("id").orEmpty()}"
    val title = displayText("title", where)
    val description = displayText("description", where)
    return WizardPage(
        id = string("id") ?: "",
        title = title.default ?: "",
        description = description.default,
        fields = array("fields").map { it.scalarString() },
        localizedText = localizedTextOf("title" to title, "description" to description),
    )
}

/** Display text from a sparkplug: [default] is the plain text, [localized] is set when it was a translation map. */
private class DisplayText(val default: String?, val localized: LocalizedText?)

/**
 * Reads a display text key, which is either a plain string or a map of
 * language tag to text, optionally with `ref` naming a [SharedText] string.
 */
private fun JsonObject.displayText(key: String, where: String): DisplayText {
    val value = this[key] ?: return DisplayText(null, null)
    if (value is JsonPrimitive) return DisplayText(value.contentOrNull, null)
    if (value !is JsonObject) throw ManifestValidationException("$where $key must be text or a map of translations")
    if (value.isEmpty()) throw ManifestValidationException("$where $key must not be an empty map")
    var ref: String? = null
    val translations = linkedMapOf<String, String>()
    value.forEach { (entryKey, entry) ->
        val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw ManifestValidationException("$where $key.$entryKey must be non-empty text")
        when {
            entryKey == "ref" -> {
                if (text !in SharedText.ids) {
                    throw ManifestValidationException("$where $key.ref names unknown shared text: $text")
                }
                ref = text
            }
            LANGUAGE_TAG_PATTERN.matches(entryKey) -> translations[entryKey] = text
            else -> throw ManifestValidationException(
                "$where $key has key $entryKey, which is neither ref nor a language tag such as en or pt-BR",
            )
        }
    }
    val default = translations.entries.firstOrNull { it.key.equals("en", ignoreCase = true) }?.value
        ?: ref?.let(SharedText.english::get)
        ?: translations.values.first()
    return DisplayText(default, LocalizedText(translations = translations, ref = ref))
}

private fun localizedTextOf(vararg entries: Pair<String, DisplayText>): Map<String, LocalizedText> =
    entries.mapNotNull { (key, text) -> text.localized?.let { key to it } }.toMap()

private fun JsonObject.conditions(key: String): List<FieldCondition> {
    return when (val value = this[key]) {
        is JsonObject -> listOf(value.toFieldCondition())
        is JsonArray -> value.map { it.jsonObject.toFieldCondition() }
        else -> emptyList()
    }
}

private fun JsonObject.toFieldCondition(): FieldCondition = FieldCondition(
    field = string("field") ?: string("selector") ?: "",
    operator = string("operator") ?: string("op") ?: "equals",
    value = this["value"]?.scalarString().orEmpty(),
)

private fun JsonObject.stringMap(): Map<String, String> = mapValues { it.value.scalarString() }
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.jsonArray.orEmpty()
private fun JsonElement.scalarString(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: toString()
    else -> toString()
}
private fun JsonElement.prefillScalarString(): String = when (this) {
    is JsonNull -> ""
    is JsonPrimitive -> contentOrNull ?: toString()
    else -> throw ManifestValidationException("prefill field values must be scalar")
}
private fun JsonElement.templateString(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: toString()
    is JsonArray -> joinToString("\n") { it.scalarString() }
    else -> throw ManifestValidationException("templates entries must be a string or a list of lines")
}

private fun JsonElement.canonicalJson(): String = when (this) {
    is JsonObject -> buildString {
        append("{")
        entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
            if (index > 0) append(",")
            append(JsonPrimitive(key).toString())
            append(":")
            append(value.canonicalJson())
        }
        append("}")
    }
    is JsonArray -> buildString {
        append("[")
        this@canonicalJson.forEachIndexed { index, element ->
            if (index > 0) append(",")
            append(element.canonicalJson())
        }
        append("]")
    }
    is JsonNull -> "null"
    is JsonPrimitive -> this.toString()
    else -> toString()
}

private fun JsonObject.withoutManifestRootSha(): JsonObject = buildJsonObject {
    entries.forEach { (key, value) ->
        if (key == "metadata" && value is JsonObject) {
            put(
                key,
                buildJsonObject {
                    value.entries.forEach { (metadataKey, metadataValue) ->
                        if (metadataKey != "manifest_root_sha256") {
                            put(metadataKey, metadataValue)
                        }
                    }
                },
            )
        } else {
            put(key, value)
        }
    }
}

private fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8)).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}

private fun String.isSha256(): Boolean = matches(Regex("^[a-f0-9]{64}$"))

private fun String.isAscii(): Boolean = all { it.code in 0x20..0x7e || it == '\n' || it == '\r' || it == '\t' }

// Keys the v3.0 contract defines. Anything else fails the load instead of being ignored.
private sealed interface Shape
private object Leaf : Shape
private class Obj(val where: String, val keys: Map<String, Shape>) : Shape
private class Items(val item: Shape) : Shape
private class Values(val value: Shape) : Shape
private class ItemsByKey(val item: Shape) : Shape

private fun shape(where: String, leaves: String, vararg children: Pair<String, Shape>): Obj =
    Obj(where, leaves.split(' ').filter { it.isNotBlank() }.associateWith { Leaf } + children)

private fun requireKnownKeys(element: JsonElement, shape: Shape) {
    when (shape) {
        Leaf -> Unit
        is Obj -> if (element is JsonObject) {
            val unknown = element.keys - shape.keys.keys
            if (unknown.isNotEmpty()) {
                throw ManifestValidationException("Unsupported ${shape.where} keys: ${unknown.sorted().joinToString()}")
            }
            element.forEach { (key, value) -> requireKnownKeys(value, shape.keys.getValue(key)) }
        }
        // A single object is accepted wherever a list of them is.
        is Items -> if (element is JsonArray) {
            element.forEach { requireKnownKeys(it, shape.item) }
        } else {
            requireKnownKeys(element, shape.item)
        }
        is Values -> (element as? JsonObject)?.values?.forEach { requireKnownKeys(it, shape.value) }
        // Either a list of items, or a map whose values are one item or a list of them.
        is ItemsByKey -> if (element is JsonObject) {
            element.values.forEach { requireKnownKeys(it, Items(shape.item)) }
        } else {
            requireKnownKeys(element, Items(shape.item))
        }
    }
}

private val CONDITION_SHAPE = shape("condition", "field selector operator op value")
private val FETCH_SHAPE = shape("fetch", "url method media_type max_bytes")
private val OUTPUTS_SHAPE = shape("outputs", "usb iso")
private val METADATA_SHAPE = shape("metadata", "id name version author publisher description homepage manifest_root_sha256 language")
private val IMAGE_METADATA_SHAPE = shape("image metadata", "id name version author publisher description homepage")
private val INLINE_SHAPE = shape("inline", "encoding text")
private const val CANDIDATE_PATHS = "candidate_paths"
private val RETURN_DELIVERY_SHAPE = shape("return_delivery", "url method delivery_mode template from content_var media_type")
private val HOST_EVENT_SHAPE = shape("host_events entry", "event", "return_delivery" to RETURN_DELIVERY_SHAPE)

private val PACKAGE_ROOT_SHAPE = shape(
    "manifest.json", "version kind",
    "metadata" to METADATA_SHAPE,
    "documents" to Values(shape("manifest.json document", "role sha256 url media_type", "inline" to INLINE_SHAPE)),
    "files" to Values(shape("manifest.json file", "sha256 url role media_type size", "inline" to INLINE_SHAPE)),
)

private val WORKFLOW_SHAPE = shape(
    "workflow", "version kind image templates",
    "metadata" to METADATA_SHAPE,
    "fill" to Values(Leaf),
    "artifacts" to Values(shape("artifact", "artifact_id media_type template optional")),
    "mailroom" to shape(
        "mailroom", "",
        "compact_artifacts" to Items(shape("mailroom.compact_artifacts entry", "id read_protocol read_base_url write_base_url")),
    ),
    "actions" to Values(
        Items(
            shape(
                "action",
                "id type template output_var artifact_id logical_name media_type content_var input_var algorithm executable user_secret user_secret_label",
                "when" to CONDITION_SHAPE,
            ),
        ),
    ),
    "host_events" to ItemsByKey(HOST_EVENT_SHAPE),
    "config_fields" to Items(
        shape(
            "config field",
            "id key label type default required max_length pattern description placeholder big advanced hidden disabled immutable standard_field storage",
            "options" to Items(shape("config field option", "value label default placeholder")),
            "bindings" to Items(shape("config field binding", "consumer target destination encoding")),
            "fetch" to FETCH_SHAPE,
            "when" to CONDITION_SHAPE,
        ),
    ),
    "wizard" to shape("wizard", "", "pages" to Items(shape("wizard page", "id title description fields"))),
    "form" to shape(
        "form", "",
        "buttons" to Items(
            shape(
                "form button", "id label description field_bindings immutable_fields",
                "fetch" to FETCH_SHAPE,
                "when" to CONDITION_SHAPE,
            ),
        ),
        "host_profiles" to Items(shape("host profile", "id label description fields immutable_fields")),
    ),
    "ui_visibility" to shape("ui_visibility", "", "when" to Items(CONDITION_SHAPE)),
)

private val IMAGE_SHAPE = shape(
    "image", "version kind",
    "metadata" to IMAGE_METADATA_SHAPE,
    "source" to shape(
        "source",
        "id name family version url sha256 size_bytes first_64k_sha256 pvd_sha256 media_type user_supplied capabilities metadata",
        "adaptation" to shape("source.adaptation", "status satisfies human_requirements"),
        "acquire" to shape("source.acquire", "kind url artifact"),
        "outputs" to OUTPUTS_SHAPE,
    ),
    "outputs" to OUTPUTS_SHAPE,
    "installer" to shape(
        "installer", "scheme accepts_delivery base_image_requires",
        "auxiliary_partition" to shape("installer.auxiliary_partition", "label filesystem required_files"),
    ),
    "delivery" to shape(
        "delivery", "mode id after base_image_sha256",
        "partition" to shape(
            "delivery.partition", "label filesystem size_mb placement",
            "geometry" to shape(
                "delivery.partition.geometry",
                "base_image_sha256 partition_table partition_number start_lba sector_size byte_offset byte_length",
            ),
        ),
        "files" to Values(shape("delivery file", "media_type optional")),
        "patches" to Items(
            shape(
                "delivery patch", "id media_type logical_name byte_offset byte_length optional",
                "placeholder" to shape("delivery patch placeholder", "encoding sha256"),
                "padding" to shape("delivery patch padding", "strategy"),
            ),
        ),
    ),
    "boot_patch" to shape(
        "boot_patch", "filesystem encoding",
        "text_patches" to Items(shape("boot_patch.text_patches entry", "kind $CANDIDATE_PATHS find replace")),
        "kernel_arg_patches" to Items(
            shape(
                "boot_patch.kernel_arg_patches entry",
                "$CANDIDATE_PATHS config_family ensure_args remove_args remove_lines_containing insert_before",
                "selector" to shape("boot_patch selector", "menuentry label kernel args_contains"),
            ),
        ),
        "script_patches" to Items(shape("boot_patch.script_patches entry", "kind $CANDIDATE_PATHS esp_image_path esp_script_path script")),
        "placeholder_patches" to Items(
            shape("boot_patch.placeholder_patches entry", "$CANDIDATE_PATHS placeholder replacement encoding padding"),
        ),
    ),
)
