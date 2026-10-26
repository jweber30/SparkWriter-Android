package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.AuxiliaryDataFile
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.BootPlaceholderPatchSpec
import net.metalstrapper.sparkwriter.core.BootScriptPatchSpec
import net.metalstrapper.sparkwriter.core.BootTextPatchSpec
import net.metalstrapper.sparkwriter.core.DeliveryFileSpec
import net.metalstrapper.sparkwriter.core.DeliveryPatchSpec
import net.metalstrapper.sparkwriter.core.FieldCondition
import net.metalstrapper.sparkwriter.core.FixedByteRangePatch
import net.metalstrapper.sparkwriter.core.FixedByteRangePlan
import net.metalstrapper.sparkwriter.core.GeneratedUserSecret
import net.metalstrapper.sparkwriter.core.MailroomCompactArtifactSpec
import net.metalstrapper.sparkwriter.core.PartitionGeometry
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.SparkTemplateFilters
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WorkflowAction
import net.metalstrapper.sparkwriter.core.WriteReturnPlan
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed interface LocalDeliveryPlan {
    data class Auxiliary(val plan: AuxiliaryDataPlan) : LocalDeliveryPlan
    data class FixedByteRanges(val plan: FixedByteRangePlan) : LocalDeliveryPlan
}

data class LocalDeliveryPreparation(
    val deliveryPlan: LocalDeliveryPlan?,
    val bootPatchPlan: BootPatchPlan? = null,
    val preparedVariables: Map<String, String> = emptyMap(),
    val userSecrets: List<GeneratedUserSecret> = emptyList(),
    val returnPlan: WriteReturnPlan?,
)

fun SparkManifest.buildLocalDeliveryPlan(values: Map<String, String>, image: VerifiedImage? = null): LocalDeliveryPlan? {
    return buildLocalDeliveryPreparation(values, image).deliveryPlan
}

fun SparkManifest.buildLocalDeliveryPreparation(
    values: Map<String, String>,
    image: VerifiedImage? = null,
): LocalDeliveryPreparation {
    val runtimeVariables = generatedRuntimeVariables()
    val artifactValues = values + runtimeVariables
    val deliverySpec = delivery
    if (deliverySpec == null) {
        val rendered = renderArtifacts(artifactValues)
        return LocalDeliveryPreparation(
            deliveryPlan = null,
            bootPatchPlan = buildBootPatchPlan(runtimeVariables),
            preparedVariables = rendered.variables,
            userSecrets = rendered.userSecrets,
            returnPlan = writeCompleteReturnPlan(rendered.variables, rendered.artifacts),
        )
    }
    ensureLocalAdaptationAvailable()
    val rendered = renderArtifacts(artifactValues)
    val deliveryPlan = when (deliverySpec.mode) {
        "auxiliary_partition" -> buildAuxiliaryDataPlanFromArtifacts(rendered.artifacts, image)?.let(LocalDeliveryPlan::Auxiliary)
        "fixed_byte_ranges" -> buildFixedByteRangePlanFromArtifacts(rendered.artifacts, image)?.let(LocalDeliveryPlan::FixedByteRanges)
        else -> error("Unsupported Android delivery mode: ${deliverySpec.mode}")
    }
    return LocalDeliveryPreparation(
        deliveryPlan = deliveryPlan,
        bootPatchPlan = buildBootPatchPlan(runtimeVariables),
        preparedVariables = rendered.variables,
        userSecrets = rendered.userSecrets,
        returnPlan = writeCompleteReturnPlan(rendered.variables, rendered.artifacts),
    )
}

private fun SparkManifest.buildBootPatchPlan(bootPatchVariables: Map<String, String>): BootPatchPlan? {
    val bootPatch = bootPatch ?: return null
    return BootPatchPlan(
        filesystem = bootPatch.filesystem,
        candidatePaths = bootPatch.candidatePaths,
        configFamily = bootPatch.configFamily,
        lineMatch = bootPatch.lineMatch,
        appendArgs = bootPatch.appendArgs.map { it.renderBootPatchVariables(bootPatchVariables).trim() },
        textPatches = bootPatch.textPatches.map { it.renderBootPatchVariables(bootPatchVariables) },
        kernelArgPatches = bootPatch.kernelArgPatches.map { it.renderBootPatchVariables(bootPatchVariables) },
        scriptPatches = bootPatch.scriptPatches.map { it.renderBootPatchVariables(bootPatchVariables) },
        placeholderPatches = bootPatch.placeholderPatches.map { it.renderBootPatchVariables(bootPatchVariables) },
        encoding = bootPatch.encoding,
    )
}

private fun SparkManifest.generatedRuntimeVariables(): Map<String, String> {
    val bootPatch = bootPatch
    val variables = mutableMapOf<String, String>()
    if (requiresMailroomRendezvousId() || bootPatch?.requiresNetbootRendezvousId() == true) {
        variables.putNetbootRendezvous(generateNetbootRendezvousId())
    }
    compactMailroomArtifactSpecs().forEach { spec ->
        variables.putMailroomCompactArtifact(
            kind = spec.id,
            artifactId = generateMailroomCompactArtifactId(),
            readBaseUrl = spec.readBaseUrl
                ?: spec.readProtocol?.mailroomCompactReadBaseUrl()
                ?: MAILROOM_COMPACT_READ_BASE_URL,
            writeBaseUrl = spec.writeBaseUrl ?: MAILROOM_COMPACT_WRITE_BASE_URL,
        )
    }
    return variables
}

private fun MutableMap<String, String>.putNetbootRendezvous(rendezvousId: String) {
    this["rendezvous_id"] = rendezvousId
    this["rendezvous-id"] = rendezvousId
    this["netboot_rendezvous_id"] = rendezvousId
    this["netboot-rendezvous-id"] = rendezvousId
    this["netboot_rendezvous_label"] = rendezvousId
    this["netboot-rendezvous-label"] = rendezvousId
    this["label"] = rendezvousId
    this["Spark.mail.aux_data.rendezvous_id"] = rendezvousId
    NETBOOT_ARTIFACT_LABELS.forEach { kind ->
        putMailroomRendezvousArtifact(kind, rendezvousId)
    }
}

private fun MutableMap<String, String>.putMailroomRendezvousArtifact(kind: String, rendezvousId: String) {
    val payloadUrl = "$MAILROOM_RENDEZVOUS_BASE_URL/$rendezvousId/$kind"
    this["Spark.mail.aux_data.${kind}_label"] = rendezvousId
    this["Spark.mail.aux_data.${kind}_url"] = payloadUrl
}

private fun MutableMap<String, String>.putMailroomCompactArtifact(
    kind: String,
    artifactId: String,
    readBaseUrl: String,
    writeBaseUrl: String,
) {
    val readUrl = "$readBaseUrl/$artifactId"
    val writeUrl = if (writeBaseUrl.endsWith("/artifacts")) writeBaseUrl else "$writeBaseUrl/$artifactId"
    this["Spark.mail.compact.${kind}_artifact_id"] = artifactId
    this["Spark.mail.compact.${kind}_url"] = readUrl
    this["Spark.mail.compact.${kind}_write_url"] = writeUrl
}

private fun String.mailroomCompactReadBaseUrl(): String = when (this) {
    "https" -> "https://wri7e.net"
    else -> MAILROOM_COMPACT_READ_BASE_URL
}

private fun BootScriptPatchSpec.renderBootPatchVariables(variables: Map<String, String>): BootScriptPatchSpec = copy(
    script = script.renderBootPatchVariables(variables),
)

private fun BootTextPatchSpec.renderBootPatchVariables(variables: Map<String, String>): BootTextPatchSpec = copy(
    find = find.renderBootPatchVariables(variables),
    replace = replace.renderBootPatchVariables(variables),
)

private fun BootPlaceholderPatchSpec.renderBootPatchVariables(
    variables: Map<String, String>,
): BootPlaceholderPatchSpec = copy(
    placeholder = placeholder.renderBootPatchVariables(variables).trim(),
    replacement = replacement.renderBootPatchVariables(variables).trim(),
)

private fun net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec.renderBootPatchVariables(
    variables: Map<String, String>,
): net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec = copy(
    ensureArgs = ensureArgs.map { it.renderBootPatchVariables(variables).trim() },
    removeArgs = removeArgs.map { it.renderBootPatchVariables(variables).trim() },
)

private fun net.metalstrapper.sparkwriter.core.BootPatchSpec.requiresNetbootRendezvousId(): Boolean {
    return scriptPatches.any {
        it.script.contains("<label>") ||
            it.script.contains("<rendezvous-id>") ||
            it.script.referencesTemplateVariable("netboot_rendezvous_label") ||
            it.script.referencesTemplateVariable("netboot_rendezvous_id")
    } ||
        placeholderPatches.any {
            it.placeholder.contains("<label>") ||
                it.replacement.contains("<label>") ||
                it.placeholder.contains("<rendezvous-id>") ||
                it.replacement.contains("<rendezvous-id>") ||
                it.placeholder.referencesTemplateVariable("netboot_rendezvous_label") ||
                it.replacement.referencesTemplateVariable("netboot_rendezvous_label") ||
                it.placeholder.referencesTemplateVariable("netboot_rendezvous_id") ||
                it.replacement.referencesTemplateVariable("netboot_rendezvous_id")
        }
}

private fun SparkManifest.requiresMailroomRendezvousId(): Boolean {
    return requiresGeneratedVariable("rendezvous_id") ||
        requiresGeneratedVariable("netboot_rendezvous_id") ||
        requiresGeneratedVariable("netboot_rendezvous_label") ||
        requiresGeneratedVariable("Spark.mail.aux_data.rendezvous_id") ||
        requiresMailroomBootRendezvous() ||
        requiresMailroomAnswerRendezvous() ||
        requiresMailroomFirstbootRendezvous()
}

private fun SparkManifest.requiresMailroomBootRendezvous(): Boolean {
    return requiresMailroomArtifactRendezvous("boot")
}

private fun SparkManifest.requiresMailroomAnswerRendezvous(): Boolean {
    return requiresMailroomArtifactRendezvous("answer")
}

private fun SparkManifest.requiresMailroomFirstbootRendezvous(): Boolean {
    return requiresMailroomArtifactRendezvous("firstboot")
}

private fun SparkManifest.compactMailroomArtifactSpecs(): List<MailroomCompactArtifactSpec> {
    return mailroom?.compactArtifacts.orEmpty().distinctBy { it.id }
}

private fun SparkManifest.requiresMailroomArtifactRendezvous(kind: String): Boolean {
    return requiresGeneratedVariable("Spark.mail.aux_data.${kind}_label") ||
        requiresGeneratedVariable("Spark.mail.aux_data.${kind}_url")
}

private fun SparkManifest.requiresGeneratedVariable(name: String): Boolean {
    val hyphenName = name.replace('_', '-')
    fun String.referencesEither(): Boolean = referencesTemplateVariable(name) || referencesTemplateVariable(hyphenName)
    return templates.values.any { it.referencesEither() } ||
        bootPatch?.appendArgs.orEmpty().any { it.referencesEither() } ||
        bootPatch?.kernelArgPatches.orEmpty().any {
            it.ensureArgs.any { arg -> arg.referencesEither() } ||
                it.removeArgs.any { arg -> arg.referencesEither() }
        } ||
        bootPatch?.textPatches.orEmpty().any {
            it.find.referencesEither() || it.replace.referencesEither()
        } ||
        bootPatch?.scriptPatches.orEmpty().any { it.script.referencesEither() } ||
        bootPatch?.placeholderPatches.orEmpty().any {
            it.placeholder.referencesEither() || it.replacement.referencesEither()
        } ||
        hostEvents.any { event ->
            event.returnDelivery?.let { delivery ->
                delivery.url.referencesEither() ||
                    delivery.template.orEmpty().referencesEither() ||
                    delivery.contentVar.orEmpty().referencesEither()
            } == true
        }
}

private fun String.referencesTemplateVariable(name: String): Boolean {
    return contains("<$name>") || Regex("""\{\{\s*${Regex.escape(name)}\s*\}\}""").containsMatchIn(this)
}

private fun String.renderBootPatchVariables(variables: Map<String, String>): String {
    if (variables.isEmpty()) return this
    return variables.entries.fold(renderMailroomAnswerLabelTokens(variables)) { rendered, (key, value) ->
        rendered
            .replace("<$key>", value)
            .replace(Regex("""\{\{\s*${Regex.escape(key)}\s*\}\}"""), value)
    }
}

private fun String.renderMailroomAnswerLabelTokens(variables: Map<String, String>): String {
    if (!contains(RENDEZVOUS_PATH_MARKER)) return this
    return MAILROOM_ANSWER_LABEL_URL_TEMPLATE.replace(this) { match ->
        val token = match.groupValues[2].ifBlank { match.groupValues[3] }
        token.toMailroomArtifactPayloadVariable(variables) ?: match.value
    }
}

private fun String.toMailroomArtifactPayloadVariable(variables: Map<String, String>): String? {
    if (!endsWith("_label") && !endsWith("-label")) return null
    val artifact = NETBOOT_ARTIFACT_LABELS.firstOrNull { dropLast(6).endsWith(it) } ?: return null
    val payloadKey = if (endsWith("_label")) "${dropLast(6)}_url" else "${dropLast(6)}-url"
    return variables[payloadKey]
        ?: variables["Spark.mail.aux_data.${artifact}_url"]
}

fun SparkManifest.buildAuxiliaryDataPlan(values: Map<String, String>, image: VerifiedImage? = null): AuxiliaryDataPlan? {
    return buildAuxiliaryDataPlanFromArtifacts(renderArtifacts(values).artifacts, image)
}

private fun SparkManifest.buildAuxiliaryDataPlanFromArtifacts(
    generated: Map<String, RenderedArtifact>,
    image: VerifiedImage? = null,
): AuxiliaryDataPlan? {
    val delivery = delivery ?: return null
    if (delivery.mode != "auxiliary_partition") return null
    val partition = delivery.partition ?: error("Auxiliary delivery is missing partition metadata")
    validateAuxiliaryContract(partition.label, partition.filesystem)
    validateAuxiliaryPlacement(partition.placement, partition.filesystem, partition.geometry, partition.sizeMb, image)

    val files = delivery.files.map { (path, file) ->
        validateDeliveryPath(path)
        val artifactId = file.artifactId ?: file.from?.substringAfter("artifacts.")
            ?: error("Auxiliary delivery file $path is missing artifact_id")
        val artifact = generated[artifactId]
        if (artifact == null && file.optional) return@map null
        if (artifact == null) error("Auxiliary artifact $artifactId was not generated")
        AuxiliaryDataFile(path, artifact.content, file.mediaType ?: artifact.mediaType, artifact.executable)
    }.filterNotNull()

    val deliveredPaths = files.map { it.path }.toSet()
    installer?.auxiliaryPartition?.requiredFiles.orEmpty().forEach { required ->
        if (required !in deliveredPaths) error("Required auxiliary file $required was not generated")
    }

    return AuxiliaryDataPlan(
        label = partition.label,
        filesystem = partition.filesystem,
        placement = partition.placement ?: "",
        sizeBytes = partition.sizeMb.toLong() * 1024L * 1024L,
        sourceSha256 = source?.sha256,
        geometry = partition.geometry,
        files = files,
    )
}

fun SparkManifest.buildFixedByteRangePlan(values: Map<String, String>, image: VerifiedImage? = null): FixedByteRangePlan? {
    return buildFixedByteRangePlanFromArtifacts(renderArtifacts(values).artifacts, image)
}

private fun SparkManifest.buildFixedByteRangePlanFromArtifacts(
    generated: Map<String, RenderedArtifact>,
    image: VerifiedImage? = null,
): FixedByteRangePlan? {
    val delivery = delivery ?: return null
    if (delivery.mode != "fixed_byte_ranges") return null
    val sourceHash = source?.sha256?.ifBlank { null } ?: error("Fixed byte-range delivery requires source.sha256")
    val deliveryHash = delivery.baseImageSha256?.ifBlank { null }
        ?: error("Fixed byte-range delivery is missing base_image_sha256")
    if (!deliveryHash.equals(sourceHash, ignoreCase = true)) {
        error("Fixed byte-range delivery hash does not match source.sha256")
    }
    image?.let {
        if (!it.sha256.equals(sourceHash, ignoreCase = true)) {
            error("Selected image hash does not match fixed byte-range source hash")
        }
    }
    validateFixedRangeContract(delivery.patches, image)
    val patches = delivery.patches.map { patch ->
        val artifactId = patch.artifactId ?: patch.from?.substringAfter("artifacts.")
            ?: error("Fixed byte-range patch ${patch.id} is missing artifact_id")
        val artifact = generated[artifactId]
        if (artifact == null && patch.optional) return@map null
        if (artifact == null) error("Fixed byte-range artifact $artifactId was not generated")
        validateFixedRangeArtifact(patch, artifactId, artifact.content)
        FixedByteRangePatch(
            id = patch.id,
            logicalName = patch.logicalName,
            mediaType = patch.mediaType ?: artifact.mediaType,
            byteOffset = patch.byteOffset,
            byteLength = patch.byteLength,
            placeholder = patch.placeholder,
            padding = patch.padding,
            content = artifact.content,
            contentSha256 = artifact.content.toByteArray(Charsets.UTF_8).sha256(),
        )
    }.filterNotNull()
    if (patches.isEmpty()) error("Fixed byte-range delivery did not produce any patches")
    return FixedByteRangePlan(baseImageSha256 = deliveryHash, patches = patches)
}

private fun validateFixedRangeArtifact(patch: DeliveryPatchSpec, artifactId: String, content: String) {
    if (content.contains(PATCH_SLOT_MARKER)) {
        val logicalName = patch.logicalName?.let { " ($it)" }.orEmpty()
        error("Fixed byte-range artifact $artifactId for patch ${patch.id}$logicalName still contains patch-slot placeholder")
    }
}

private const val PATCH_SLOT_MARKER = "METALSTRAPPER_PATCH_SLOT"
private const val MAILROOM_RENDEZVOUS_BASE_URL = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous"
private const val MAILROOM_COMPACT_READ_BASE_URL = "http://wri7e.net"
private const val MAILROOM_COMPACT_WRITE_BASE_URL = "https://mailroom.metalstrapper.net/compact/v1/artifacts"
private const val RENDEZVOUS_PATH_MARKER = "/rendezvous/"
private val NETBOOT_ARTIFACT_LABELS = listOf("boot", "answer", "firstboot")
private val MAILROOM_ANSWER_LABEL_URL_TEMPLATE =
    Regex("""(https?://mailroom\.metalstrapper\.net/netboot/v1/rendezvous/)(?:\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}|<([A-Za-z0-9_.-]+)>)""")

internal fun generateMailroomCompactArtifactId(): String {
    val bytes = ByteArray(7)
    SecureRandom().nextBytes(bytes)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return bytes.joinToString(separator = "") { byte ->
        alphabet[(byte.toInt() and 0xff) % alphabet.length].toString()
    }
}

internal fun generateNetbootRendezvousId(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun SparkManifest.validateAuxiliaryContract(label: String, filesystem: String) {
    val installerSpec = installer ?: error("Auxiliary delivery requires an installer declaration")
    if ("auxiliary_partition" !in installerSpec.acceptsDelivery) {
        error("Installer ${installerSpec.scheme} does not accept auxiliary partition delivery")
    }
    val installerPartition = installerSpec.auxiliaryPartition
        ?: error("Installer ${installerSpec.scheme} is missing auxiliary partition metadata")
    if (installerPartition.label != label) {
        error("Auxiliary partition label mismatch: delivery $label, installer ${installerPartition.label}")
    }
    if (!installerPartition.filesystem.equals(filesystem, ignoreCase = true)) {
        error("Auxiliary partition filesystem mismatch: delivery $filesystem, installer ${installerPartition.filesystem}")
    }
}

private fun SparkManifest.validateAuxiliaryPlacement(
    placement: String?,
    filesystem: String,
    geometry: PartitionGeometry?,
    sizeMb: Int,
    image: VerifiedImage?,
) {
    val sourceHash = source?.sha256?.ifBlank { null }
    when (placement) {
        "preallocated_in_base_image" -> {
            val requiredSourceHash = sourceHash ?: error("Auxiliary delivery requires source.sha256")
            image?.let {
                if (!it.sha256.equals(requiredSourceHash, ignoreCase = true)) {
                    error("Selected image hash does not match auxiliary source hash")
                }
            }
            val resolvedGeometry = geometry ?: error("Auxiliary delivery is missing resolved partition geometry")
            validateGeometry(resolvedGeometry, sizeMb, requiredSourceHash, image)
        }
        "append_after_image" -> {
            if (!filesystem.equals("vfat", ignoreCase = true)) {
                error("Dynamic auxiliary delivery supports only vfat")
            }
        }
        else -> error("Unsupported auxiliary partition placement: $placement")
    }
}

private fun validateGeometry(geometry: PartitionGeometry, sizeMb: Int, sourceHash: String, image: VerifiedImage?) {
    if (geometry.partitionTable != "gpt") error("Android auxiliary delivery supports only GPT geometry")
    if (geometry.partitionNumber <= 0) error("Auxiliary partition geometry has invalid partition number")
    if (geometry.sectorSize <= 0) error("Auxiliary partition geometry has invalid sector size")
    if (geometry.startLba < 0 || geometry.byteOffset < 0 || geometry.byteLength <= 0) {
        error("Auxiliary partition geometry has invalid byte range")
    }
    val expectedByteOffset = geometry.startLba * geometry.sectorSize.toLong()
    if (geometry.byteOffset != expectedByteOffset) {
        error(
            "Auxiliary partition byte_offset ${geometry.byteOffset} does not equal " +
                "start_lba * sector_size $expectedByteOffset",
        )
    }
    val requiredBytes = sizeMb.toLong() * 1024L * 1024L
    if (geometry.byteLength < requiredBytes) {
        error("Auxiliary partition geometry is smaller than requested size")
    }
    if (!geometry.baseImageSha256.equals(sourceHash, ignoreCase = true)) {
        error("Auxiliary partition geometry hash does not match source.sha256")
    }
    image?.let {
        if (geometry.byteOffset + geometry.byteLength > it.sizeBytes) {
            error("Auxiliary partition geometry exceeds verified base image size")
        }
    }
}

private fun validateFixedRangeContract(patches: List<DeliveryPatchSpec>, image: VerifiedImage?) {
    if (patches.isEmpty()) error("Fixed byte-range delivery requires patches")
    patches.forEach { patch ->
        if (patch.byteOffset < 0 || patch.byteLength <= 0) {
            error("Fixed byte-range patch ${patch.id} has invalid byte range")
        }
        image?.let {
            if (patch.byteOffset + patch.byteLength > it.sizeBytes) {
                error("Fixed byte-range patch ${patch.id} exceeds verified base image size")
            }
        }
    }
    patches.sortedBy { it.byteOffset }.zipWithNext().forEach { (left, right) ->
        if (left.byteOffset + left.byteLength > right.byteOffset) {
            error("Fixed byte-range patches must not overlap")
        }
    }
}

fun SparkManifest.localAndroidUnavailableReasons(): List<String> {
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
    val reasons = mutableListOf<String>()
    val missing = required - adaptation.satisfies.toSet()
    if (missing.isNotEmpty()) reasons += "Source adaptation is missing required flags: ${missing.joinToString()}"
    return reasons
}

fun SparkManifest.ensureLocalAdaptationAvailable() {
    val unavailable = localAndroidUnavailableReasons()
    if (unsupportedReasons.isNotEmpty()) error(unsupportedReasons.joinToString())
    if (unavailable.isNotEmpty()) error(unavailable.joinToString())
}

private fun validateDeliveryPath(path: String) {
    if (!path.startsWith("/")) error("Auxiliary delivery file path must be absolute: $path")
    if (path.split('/').any { it == ".." }) error("Auxiliary delivery file path may not contain traversal: $path")
    if (path == "/") error("Auxiliary delivery file path must name a file")
}

private data class RenderedArtifact(
    val content: String,
    val mediaType: String?,
    val executable: Boolean,
)

private data class RenderArtifactsResult(
    val artifacts: Map<String, RenderedArtifact>,
    val variables: Map<String, String>,
    val userSecrets: List<GeneratedUserSecret>,
)

private fun SparkManifest.artifactFromStaticSpec(
    artifactId: String,
    values: Map<String, String>,
    deliveryFile: DeliveryFileSpec? = null,
): RenderedArtifact? {
    val spec = artifacts.values.firstOrNull { it.artifactId == artifactId } ?: return null
    val templateId = spec.template ?: return null
    return RenderedArtifact(
        content = renderSparkTemplate(templates[templateId] ?: return null, values),
        mediaType = deliveryFile?.mediaType ?: spec.mediaType,
        executable = false,
    )
}

private fun SparkManifest.renderArtifacts(values: Map<String, String>): RenderArtifactsResult {
    requireConfigFieldLengths(values)
    val generated = mutableMapOf<String, RenderedArtifact>()
    val variables = values.toMutableMap()
    val userSecrets = mutableListOf<GeneratedUserSecret>()

    runArtifactActions(actionPhases["on_iso_ready"].orEmpty(), values, variables, generated, userSecrets)

    artifacts.forEach { (key, spec) ->
        val specId = spec.artifactId ?: key
        val artifact = generated[specId]
            ?: generated[key]
            ?: artifactFromStaticSpec(specId, variables)
            ?: return@forEach
        generated.putIfAbsent(specId, artifact)
        generated.putIfAbsent(key, artifact)
    }
    return RenderArtifactsResult(artifacts = generated, variables = variables, userSecrets = userSecrets)
}

private fun SparkManifest.runArtifactActions(
    actions: List<WorkflowAction>,
    conditionValues: Map<String, String>,
    variables: MutableMap<String, String>,
    generated: MutableMap<String, RenderedArtifact>,
    userSecrets: MutableList<GeneratedUserSecret>,
) {
    actions.forEach { action ->
        if (!action.whenCondition.matches(conditionValues)) return@forEach
        when (action.type) {
            "generate_ephemeral_password" -> {
                action.outputVar?.let { outputVar ->
                    val secret = randomPassword()
                    variables[outputVar] = secret
                    if (action.userSecret) {
                        userSecrets += GeneratedUserSecret(
                            id = outputVar,
                            label = action.userSecretLabel?.ifBlank { null } ?: outputVar.humanizeSecretLabel(),
                            value = secret,
                        )
                    }
                }
            }
            "render_template" -> {
                val templateId = action.template ?: error("render_template ${action.id} is missing template")
                val rendered = renderSparkTemplate(templates[templateId] ?: error("Template $templateId not found"), variables)
                action.outputVar?.let { variables[it] = rendered }
            }
            "hash_password" -> {
                val outputVar = action.outputVar ?: error("hash_password ${action.id} is missing output_var")
                val inputVar = action.inputVar ?: error("hash_password ${action.id} is missing input_var")
                val password = variables[inputVar] ?: error("hash_password ${action.id} references missing input_var $inputVar")
                variables[outputVar] = hashPassword(password, action.algorithm)
            }
            "create_artifact" -> {
                val artifactId = action.artifactId ?: error("create_artifact ${action.id} is missing artifact_id")
                val contentVar = action.contentVar ?: error("create_artifact ${action.id} is missing content_var")
                generated[artifactId] = RenderedArtifact(
                    content = variables[contentVar].orEmpty(),
                    mediaType = action.mediaType,
                    executable = action.executable,
                )
            }
        }
    }
}

private fun SparkManifest.writeCompleteReturnPlan(
    variables: Map<String, String>,
    renderedArtifacts: Map<String, RenderedArtifact> = emptyMap(),
): WriteReturnPlan? {
    val subscriptions = hostEvents.filter {
        it.event in setOf("write_ready", "write_complete", "workflow.started") && it.returnDelivery != null
    }
    validateMailroomWriteReadyDeliveries(
        variables = variables,
        renderedArtifacts = renderedArtifacts,
        subscriptions = subscriptions,
        requireBoot = requiresMailroomBootRendezvous(),
        requireAnswer = requiresMailroomAnswerRendezvous(),
        requireFirstboot = requiresMailroomFirstbootRendezvous(),
        compactArtifacts = compactMailroomArtifactSpecs().map { it.id },
    )
    if (subscriptions.isEmpty()) return null
    return WriteReturnPlan(
        variables = variables,
        templates = templates,
        artifacts = renderedArtifacts.mapValues { it.value.content },
        userSecrets = renderedUserSecrets(variables),
        actions = actionPhases["on_write_complete"].orEmpty(),
        actionPhases = mapOf(
            "on_write_ready" to actionPhases["on_write_ready"].orEmpty(),
            "on_write_complete" to actionPhases["on_write_complete"].orEmpty(),
            "on_workflow_started" to actionPhases["on_workflow_started"].orEmpty(),
        ),
        hostEvents = subscriptions,
    )
}

private fun SparkManifest.renderedUserSecrets(variables: Map<String, String>): List<GeneratedUserSecret> {
    return actionPhases["on_iso_ready"].orEmpty().mapNotNull { action ->
        if (action.type != "generate_ephemeral_password" || !action.userSecret) return@mapNotNull null
        val outputVar = action.outputVar?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val value = variables[outputVar]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        GeneratedUserSecret(
            id = outputVar,
            label = action.userSecretLabel?.ifBlank { null } ?: outputVar.humanizeSecretLabel(),
            value = value,
        )
    }
}

private fun String.humanizeSecretLabel(): String {
    return removePrefix("generated_")
        .split('_', '-', '.')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        .ifBlank { this }
}

private fun validateMailroomWriteReadyDeliveries(
    variables: Map<String, String>,
    renderedArtifacts: Map<String, RenderedArtifact>,
    subscriptions: List<net.metalstrapper.sparkwriter.core.HostEventSubscription>,
    requireBoot: Boolean,
    requireAnswer: Boolean,
    requireFirstboot: Boolean,
    compactArtifacts: List<String>,
) {
    if (requireBoot || subscriptions.hasMailroomWriteReadyDelivery("boot", variables)) {
        validateMailroomWriteReadyDelivery("boot", variables["Spark.mail.aux_data.boot_url"], variables, renderedArtifacts, subscriptions)
    }
    if (requireAnswer) validateMailroomWriteReadyDelivery("answer", variables["Spark.mail.aux_data.answer_url"], variables, renderedArtifacts, subscriptions)
    if (requireFirstboot) validateMailroomWriteReadyDelivery("firstboot", variables["Spark.mail.aux_data.firstboot_url"], variables, renderedArtifacts, subscriptions)
    compactArtifacts.forEach { artifact ->
        validateMailroomWriteReadyDelivery(artifact, variables["Spark.mail.compact.${artifact}_write_url"], variables, renderedArtifacts, subscriptions)
    }
}

private fun List<net.metalstrapper.sparkwriter.core.HostEventSubscription>.hasMailroomWriteReadyDelivery(
    kind: String,
    variables: Map<String, String>,
): Boolean {
    val url = variables["Spark.mail.aux_data.${kind}_url"] ?: return false
    return any { subscription ->
        subscription.event == "write_ready" &&
            subscription.returnDelivery?.let { renderSparkTemplate(it.url, variables).trim() == url } == true
    }
}

private fun validateMailroomWriteReadyDelivery(
    kind: String,
    url: String?,
    variables: Map<String, String>,
    renderedArtifacts: Map<String, RenderedArtifact>,
    subscriptions: List<net.metalstrapper.sparkwriter.core.HostEventSubscription>,
) {
    url ?: return
    val matches = subscriptions.mapNotNull { subscription ->
        if (subscription.event != "write_ready") return@mapNotNull null
        val delivery = subscription.returnDelivery ?: return@mapNotNull null
        if (renderSparkTemplate(delivery.url, variables).trim() == url) delivery else null
    }
    if (matches.isEmpty()) {
        val method = if (url.endsWith("/compact/v1/artifacts")) "POST" else "PUT"
        error("Mailroom $kind payload requires a required write_ready $method to $url")
    }
    val expectedMethod = if (url.endsWith("/compact/v1/artifacts")) "POST" else "PUT"
    val deliverable = matches.any { delivery ->
        delivery.method == expectedMethod &&
            delivery.deliveryMode == "required" &&
            delivery.hasBodySource(variables, renderedArtifacts)
    }
    if (!deliverable) {
        error("Mailroom $kind payload write_ready delivery must use required $expectedMethod with a rendered body")
    }
}

private fun net.metalstrapper.sparkwriter.core.ReturnDeliverySpec.hasBodySource(
    variables: Map<String, String>,
    renderedArtifacts: Map<String, RenderedArtifact>,
): Boolean {
    val fromValue = from
    if (fromValue != null) {
        val artifactId = if (fromValue.startsWith("artifacts.")) fromValue.substringAfter("artifacts.") else fromValue
        return artifactId in renderedArtifacts
    }
    val contentVarValue = contentVar
    if (contentVarValue != null) return variables.containsKey(contentVarValue)
    return template != null
}

internal fun renderSparkTemplate(template: String, variables: Map<String, String>): String {
    val rendered = mutableListOf<String>()
    val activeStack = ArrayDeque<Boolean>()
    var currentActive = true

    template.lineSequence().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("{% if ") && trimmed.endsWith("%}") -> {
                val variable = trimmed.removePrefix("{% if ").removeSuffix("%}").trim()
                val condition = variables[variable].orEmpty().isNotBlank()
                activeStack.addLast(currentActive)
                currentActive = currentActive && condition
            }
            trimmed == "{% else %}" -> {
                val parentActive = activeStack.lastOrNull() ?: true
                currentActive = parentActive && !currentActive
            }
            trimmed == "{% endif %}" -> {
                currentActive = activeStack.removeLastOrNull() ?: true
            }
            currentActive -> rendered += replaceVariables(line, variables)
        }
    }

    return rendered.joinToString("\n").trimEnd() + "\n"
}

private fun replaceVariables(line: String, variables: Map<String, String>): String {
    val renderedLine = line.renderMailroomAnswerLabelTokens(variables)
    return SparkTemplateFilters.PLACEHOLDER.replace(renderedLine) { match ->
        val name = match.groupValues[1]
        val value = variables[name] ?: name.jinjaAliasFallback(variables)
            ?: error("Template references missing form field: $name")
        SparkTemplateFilters.apply(SparkTemplateFilters.parseChain(match.groupValues[2]), value)
    }
}

private fun String.jinjaAliasFallback(variables: Map<String, String>): String? {
    if ('_' !in this) return null
    return variables[this.replace('_', '-')]
}

private fun randomPassword(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789_-"
    val random = SecureRandom()
    return buildString {
        repeat(24) {
            append(alphabet[random.nextInt(alphabet.length)])
        }
    }
}

