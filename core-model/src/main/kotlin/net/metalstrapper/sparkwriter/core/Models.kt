package net.metalstrapper.sparkwriter.core

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType { USB_STICK, SD_CARD, UNKNOWN }

@Serializable
data class DeviceRef(
    val id: String,
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val sizeHuman: String,
    val vendor: String? = null,
    val model: String? = null,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val isSystemDevice: Boolean = false,
    val removable: Boolean = true,
    val bus: String? = null,
    val supportsWipe: Boolean = true,
)

@Serializable
data class VerifiedImage(
    val uri: String,
    val sha256: String,
    val sizeBytes: Long,
    val mediaType: String,
    val provenance: String,
    val displayName: String,
    val first64KSha256: String? = null,
    val pvdSha256: String? = null,
    val isoVolumeId: String? = null,
    val isoApplicationId: String? = null,
)

@Serializable
data class Source(
    val id: String,
    val name: String,
    val family: String? = null,
    val version: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val first64KSha256: String? = null,
    val pvdSha256: String? = null,
    val mediaType: String? = null,
    val userSupplied: Boolean = false,
    val adaptation: SourceAdaptation? = null,
    val acquire: AcquireSpec? = null,
    val capabilities: List<String> = emptyList(),
    val outputs: OutputSpec? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class SourceAdaptation(
    val status: String,
    val satisfies: List<String> = emptyList(),
    val humanRequirements: List<String> = emptyList(),
)

@Serializable
data class AcquireSpec(
    val kind: String = "direct",
    val url: String,
    val artifact: String? = null,
)

@Serializable
data class OutputSpec(
    val usb: Boolean = true,
    val iso: Boolean = true,
)

@Serializable
data class ManifestMetadata(
    val id: String,
    val name: String,
    val version: String? = null,
    val author: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    val manifestRootSha256: String? = null,
    /** Language of the sparkplug's plain-string display text (BCP 47). */
    val language: String = "en",
)

@Serializable
data class ConfigOption(
    val value: String,
    val label: String,
    val default: Boolean = false,
    val placeholder: Boolean = false,
    /** Translations for this object's display text, keyed by sparkplug key (such as "label"). */
    val localizedText: Map<String, LocalizedText> = emptyMap(),
)

@Serializable
data class ConfigBinding(
    val consumer: String,
    val target: String,
    val destination: String? = null,
    val encoding: String? = null,
)

@Serializable
data class ConfigFetchSpec(
    val url: String,
    val method: String = "GET",
    val mediaType: String? = null,
    val maxBytes: Long? = null,
)

@Serializable
data class FormPrefillButton(
    val id: String,
    val label: String,
    val description: String? = null,
    val fetch: ConfigFetchSpec,
    val fieldBindings: Map<String, String> = emptyMap(),
    val immutableFields: List<String> = emptyList(),
    val whenCondition: FieldCondition? = null,
    /** Translations for this object's display text, keyed by sparkplug key (such as "label"). */
    val localizedText: Map<String, LocalizedText> = emptyMap(),
)

@Serializable
data class HostProfile(
    val id: String,
    val label: String,
    val description: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val immutableFields: List<String> = emptyList(),
    /** Translations for this object's display text, keyed by sparkplug key (such as "label"). */
    val localizedText: Map<String, LocalizedText> = emptyMap(),
)

@Serializable
data class FormSpec(
    val buttons: List<FormPrefillButton> = emptyList(),
    val hostProfiles: List<HostProfile> = emptyList(),
)

@Serializable
data class ConfigField(
    val id: String,
    val label: String,
    val type: String = "text",
    val default: String = "",
    val required: Boolean = false,
    val maxLength: Int? = null,
    /** Regular expression a non-empty value must match in full. */
    val pattern: String? = null,
    val description: String? = null,
    val placeholder: String? = null,
    val options: List<ConfigOption> = emptyList(),
    val big: Boolean = false,
    val advanced: Boolean = false,
    val hidden: Boolean = false,
    val immutable: Boolean = false,
    val disabled: Boolean = false,
    val standardField: String? = null,
    val storage: Map<String, String> = emptyMap(),
    val bindings: List<ConfigBinding> = emptyList(),
    val fetch: ConfigFetchSpec? = null,
    val whenCondition: FieldCondition? = null,
    /** Translations for this object's display text, keyed by sparkplug key (such as "label"). */
    val localizedText: Map<String, LocalizedText> = emptyMap(),
)

@Serializable
data class WizardPage(
    val id: String,
    val title: String,
    val description: String? = null,
    val fields: List<String>,
    /** Translations for this object's display text, keyed by sparkplug key (such as "label"). */
    val localizedText: Map<String, LocalizedText> = emptyMap(),
)

@Serializable
data class SparkManifest(
    val version: String,
    val metadata: ManifestMetadata,
    val source: Source? = null,
    val outputs: OutputSpec = OutputSpec(),
    val installer: InstallerSpec? = null,
    val artifacts: Map<String, ArtifactSpec> = emptyMap(),
    val delivery: DeliverySpec? = null,
    val mailroom: MailroomSpec? = null,
    val bootPatch: BootPatchSpec? = null,
    val actionPhases: Map<String, List<WorkflowAction>> = emptyMap(),
    val hostEvents: List<HostEventSubscription> = emptyList(),
    val configFields: List<ConfigField> = emptyList(),
    val wizardPages: List<WizardPage> = emptyList(),
    val form: FormSpec = FormSpec(),
    val uiVisibility: List<FieldCondition> = emptyList(),
    val templates: Map<String, String> = emptyMap(),
    val unsupportedReasons: List<String> = emptyList(),
    val installOrigin: String? = null,
    val packageRootSha256: String? = null,
) {
    val isAndroidRunnable: Boolean get() = unsupportedReasons.isEmpty()
}

@Serializable
data class MailroomSpec(
    val compactArtifacts: List<MailroomCompactArtifactSpec> = emptyList(),
)

@Serializable
data class MailroomCompactArtifactSpec(
    val id: String,
    val readProtocol: String? = null,
    val readBaseUrl: String? = null,
    val writeBaseUrl: String? = null,
)

@Serializable
data class InstallerSpec(
    val scheme: String,
    val acceptsDelivery: List<String> = emptyList(),
    val baseImageRequires: List<String> = emptyList(),
    val auxiliaryPartition: AuxiliaryPartitionSpec? = null,
)

@Serializable
data class HostEventSubscription(
    val event: String,
    val returnDelivery: ReturnDeliverySpec? = null,
)

@Serializable
data class ReturnDeliverySpec(
    val url: String,
    val method: String = "POST",
    val deliveryMode: String = "best_effort",
    val template: String? = null,
    val from: String? = null,
    val contentVar: String? = null,
    val mediaType: String? = null,
)

@Serializable
data class AuxiliaryPartitionSpec(
    val label: String,
    val filesystem: String = "vfat",
    val requiredFiles: List<String> = emptyList(),
)

@Serializable
data class ArtifactSpec(
    val artifactId: String,
    val mediaType: String? = null,
    val template: String? = null,
    val optional: Boolean = false,
)

@Serializable
data class DeliverySpec(
    val mode: String,
    val id: String? = null,
    val after: String? = null,
    val baseImageSha256: String? = null,
    val partition: DeliveryPartitionSpec? = null,
    val files: Map<String, DeliveryFileSpec> = emptyMap(),
    val patches: List<DeliveryPatchSpec> = emptyList(),
)

@Serializable
data class DeliveryPatchSpec(
    val id: String,
    val from: String? = null,
    val artifactId: String? = null,
    val mediaType: String? = null,
    val logicalName: String? = null,
    val byteOffset: Long,
    val byteLength: Long,
    val placeholder: PlaceholderSpec? = null,
    val padding: PaddingSpec? = null,
    val optional: Boolean = false,
)

@Serializable
data class PlaceholderSpec(
    val encoding: String? = null,
    val sha256: String? = null,
)

@Serializable
data class PaddingSpec(
    val strategy: String? = null,
)

@Serializable
data class DeliveryPartitionSpec(
    val label: String,
    val filesystem: String = "vfat",
    val sizeMb: Int = 64,
    val placement: String? = null,
    val geometry: PartitionGeometry? = null,
)

@Serializable
data class PartitionGeometry(
    val baseImageSha256: String,
    val partitionTable: String,
    val partitionNumber: Int,
    val startLba: Long,
    val sectorSize: Int,
    val byteOffset: Long,
    val byteLength: Long,
)

@Serializable
data class DeliveryFileSpec(
    val from: String? = null,
    val artifactId: String? = null,
    val mediaType: String? = null,
    val optional: Boolean = false,
)

@Serializable
data class BootPatchSpec(
    val filesystem: String = "iso9660",
    val candidatePaths: List<String> = emptyList(),
    val configFamily: String = "",
    val lineMatch: String = "",
    val appendArgs: List<String> = emptyList(),
    val textPatches: List<BootTextPatchSpec> = emptyList(),
    val kernelArgPatches: List<BootKernelArgPatchSpec> = emptyList(),
    val scriptPatches: List<BootScriptPatchSpec> = emptyList(),
    val placeholderPatches: List<BootPlaceholderPatchSpec> = emptyList(),
    val encoding: String = "utf-8",
)

@Serializable
data class BootTextPatchSpec(
    val kind: String,
    val candidatePaths: List<String> = emptyList(),
    val find: String,
    val replace: String,
)

@Serializable
data class BootKernelArgPatchSpec(
    val candidatePaths: List<String> = emptyList(),
    val configFamily: String,
    val selector: BootKernelArgSelector,
    val ensureArgs: List<String> = emptyList(),
    val removeArgs: List<String> = emptyList(),
    val removeLinesContaining: List<String> = emptyList(),
    val insertBefore: String = "---",
)

@Serializable
data class BootKernelArgSelector(
    val menuentry: String? = null,
    val label: String? = null,
    val kernel: String? = null,
    val argsContains: List<String> = emptyList(),
)

@Serializable
data class BootScriptPatchSpec(
    val kind: String,
    val candidatePaths: List<String> = emptyList(),
    val espImagePath: String? = null,
    val espScriptPath: String? = null,
    val script: String,
)

@Serializable
data class BootPlaceholderPatchSpec(
    val candidatePaths: List<String> = emptyList(),
    val placeholder: String,
    val replacement: String,
    val encoding: String = "ascii",
    val padding: String = "space",
)

@Serializable
data class WorkflowAction(
    val id: String,
    val type: String,
    val template: String? = null,
    val outputVar: String? = null,
    val artifactId: String? = null,
    val logicalName: String? = null,
    val mediaType: String? = null,
    val contentVar: String? = null,
    val inputVar: String? = null,
    val algorithm: String? = null,
    val executable: Boolean = false,
    val userSecret: Boolean = false,
    val userSecretLabel: String? = null,
    val whenCondition: FieldCondition? = null,
    /** Translations for this object's display text, keyed by sparkplug key (such as "label"). */
    val localizedText: Map<String, LocalizedText> = emptyMap(),
)

@Serializable
data class FieldCondition(
    val field: String,
    val operator: String,
    val value: String,
)

@Serializable
data class AuxiliaryDataPlan(
    val label: String,
    val filesystem: String,
    val placement: String,
    val sizeBytes: Long,
    val sourceSha256: String?,
    val geometry: PartitionGeometry? = null,
    val files: List<AuxiliaryDataFile>,
)

@Serializable
data class AuxiliaryDataFile(
    val path: String,
    val content: String,
    val mediaType: String? = null,
    val executable: Boolean = false,
)

@Serializable
data class FixedByteRangePlan(
    val baseImageSha256: String,
    val patches: List<FixedByteRangePatch>,
)

@Serializable
data class BootPatchPlan(
    val filesystem: String = "iso9660",
    val candidatePaths: List<String> = emptyList(),
    val configFamily: String = "",
    val lineMatch: String = "",
    val appendArgs: List<String> = emptyList(),
    val textPatches: List<BootTextPatchSpec> = emptyList(),
    val kernelArgPatches: List<BootKernelArgPatchSpec> = emptyList(),
    val scriptPatches: List<BootScriptPatchSpec> = emptyList(),
    val placeholderPatches: List<BootPlaceholderPatchSpec> = emptyList(),
    val encoding: String = "utf-8",
)

@Serializable
data class FixedByteRangePatch(
    val id: String,
    val logicalName: String? = null,
    val mediaType: String? = null,
    val byteOffset: Long,
    val byteLength: Long,
    val placeholder: PlaceholderSpec? = null,
    val padding: PaddingSpec? = null,
    val content: String,
    val contentSha256: String,
)

@Serializable
data class SparkPackageRoot(
    val version: String,
    val kind: String,
    val metadata: ManifestMetadata,
    val documents: Map<String, PackageDocumentRef>,
    val files: Map<String, PackageFileRef> = emptyMap(),
    val installOrigin: String? = null,
    val manifestRootSha256: String? = null,
) {
    /** The document with [role] ("workflow" or "image"); a validated package root has exactly one of each. */
    fun document(role: String): PackageDocumentRef =
        documents.values.singleOrNull { it.role == role }
            ?: throw IllegalStateException("Package root has no single $role document")
}

@Serializable
data class PackageDocumentRef(
    val role: String,
    val sha256: String,
    val url: String? = null,
    val inline: InlineText? = null,
)

@Serializable
data class PackageFileRef(
    val sha256: String,
    val url: String? = null,
    val inline: InlineText? = null,
    val role: String? = null,
    val mediaType: String? = null,
    val size: Long? = null,
)

@Serializable
data class InlineText(
    val encoding: String = "utf-8",
    val text: String,
)

@Serializable
data class ManifestUploadRequest(
    val publisherId: String,
    val packageRoot: SparkPackageRoot,
    val files: Map<String, String> = emptyMap(),
)

@Serializable
data class ManifestUploadResult(
    val registryUrl: String,
    val manifestUrl: String,
    val packageId: String,
)

@Serializable
enum class WipeMode { FULL, QUICK, NONE }

@Serializable
data class WriteIntent(
    val deviceId: String,
    val confirmedDeviceId: String,
    val image: VerifiedImage,
    val wipeMode: WipeMode = WipeMode.FULL,
    val verifyAfter: Boolean = true,
    val auxiliaryData: AuxiliaryDataPlan? = null,
    val fixedByteRanges: FixedByteRangePlan? = null,
    val bootPatch: BootPatchPlan? = null,
    val bootPatchTemplate: BootPatchTemplatePlan? = null,
    val returnPlan: WriteReturnPlan? = null,
    val tags: Map<String, String> = emptyMap(),
    /** Identity of the device the user confirmed; writes are refused without it. */
    val confirmedDevice: DeviceFingerprint? = null,
)

@Serializable
data class BootPatchTemplatePlan(
    val signature: String,
    val patches: List<BootPatchTemplatePatch> = emptyList(),
)

@Serializable
data class BootPatchTemplatePatch(
    val offset: Long,
    val byteLength: Int,
    val expectedSha256: String,
    val byteTemplateBase64: String,
    val variableSlots: List<BootPatchVariableSlot> = emptyList(),
    val description: String,
)

@Serializable
data class BootPatchVariableSlot(
    val name: String,
    val variableClass: String,
)

@Serializable
data class WriteReturnPlan(
    val variables: Map<String, String> = emptyMap(),
    val templates: Map<String, String> = emptyMap(),
    val artifacts: Map<String, String> = emptyMap(),
    val userSecrets: List<GeneratedUserSecret> = emptyList(),
    val actions: List<WorkflowAction> = emptyList(),
    val actionPhases: Map<String, List<WorkflowAction>> = emptyMap(),
    val hostEvents: List<HostEventSubscription> = emptyList(),
)

@Serializable
data class GeneratedUserSecret(
    val id: String,
    val label: String,
    val value: String,
)

@Serializable
enum class WriteStatus { PENDING, RUNNING, VERIFYING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class WriteSession(
    val sessionId: String,
    val status: WriteStatus,
    val progressPercent: Int = 0,
    val bytesWritten: Long = 0,
    val message: String = "",
    val deviceId: String? = null,
    val imageUri: String? = null,
)

@Serializable
data class ValidationReport(
    val isValid: Boolean,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val recommendedDevice: DeviceRef? = null,
)

@Serializable
data class Receipt(
    val id: String,
    val manifestId: String?,
    val sourceId: String?,
    val imageSha256: String,
    val deviceId: String,
    val status: WriteStatus,
    val completedAtEpochMillis: Long,
    val notes: List<String> = emptyList(),
)

@Serializable
data class BlockDeviceInfo(
    val device: DeviceRef,
    val blockSize: Int = 512,
    val blockCount: Long,
    val permissionGranted: Boolean,
    /** Physical identity of the device; null until USB permission allows reading it. */
    val fingerprint: DeviceFingerprint? = null,
)

/**
 * What identifies a physical USB drive across the time between the user's confirmation and the
 * write: Android's device ID is only a per-attachment handle that restarts after a reboot. Two
 * identical drives without serial numbers cannot be told apart, so the serial is compared
 * whenever the device reports one.
 */
@Serializable
data class DeviceFingerprint(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String? = null,
    val productName: String? = null,
    val serialNumber: String? = null,
    val blockSize: Int,
    val blockCount: Long,
)

sealed interface PermissionResult {
    data object Granted : PermissionResult
    data object Denied : PermissionResult
    data class NeedsUserAction(val deviceId: String) : PermissionResult
}

@Serializable
data class WriteProgress(
    val bytesWritten: Long,
    val totalBytes: Long,
    val message: String,
) {
    val percent: Int = if (totalBytes <= 0) 0 else ((bytesWritten * 100) / totalBytes).toInt().coerceIn(0, 100)
}

@Serializable
data class VerifyProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val message: String,
    val completed: Boolean = false,
    val verified: Boolean = false,
    /** Set on the completing emission of a final-image verification. */
    val finalImage: FinalImageDigest? = null,
)

/** A contiguous byte range on the target device. */
@Serializable
data class ByteExtent(
    val offset: Long,
    val length: Long,
)

/**
 * Digest of everything SparkWriter wrote to the device: the block-padded base image plus every
 * later patch, as merged [extents] in disk order. [sha256] covers the concatenated bytes of those
 * extents, so gaps that were never written (and still hold old data) are excluded.
 */
@Serializable
data class FinalImageDigest(
    val sha256: String,
    val totalBytes: Long,
    val extents: List<ByteExtent>,
)
