package net.metalstrapper.sparkwriter.agent

import android.app.PendingIntent
import androidx.appfunctions.AppFunctionSerializable
import net.metalstrapper.sparkwriter.ConfigDraftRequest
import net.metalstrapper.sparkwriter.DraftValidation
import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.formFieldId
import net.metalstrapper.sparkwriter.isDraftFillable
import net.metalstrapper.sparkwriter.isUserEditable
import net.metalstrapper.sparkwriter.validateDraft

/*
 * What SparkWriter shows an assistant. The KDoc on these classes becomes the
 * schema description the assistant reads, so it is written for that reader.
 */

/** An installed SparkPlug: a recipe that turns an operating-system image into an unattended installer. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class SparkPlugSummary(
    /** Pass this to describeSparkPlug and prepareSparkPlugDraft. */
    val id: String,
    /** Display name, such as "Proxmox VE". */
    val name: String,
    /** What the SparkPlug installs and how. */
    val description: String?,
    /** SparkPlug version. */
    val version: String?,
    /** Whether the result can be written to a USB drive. */
    val canWriteUsb: Boolean,
    /** Whether the result can be saved as an ISO file. */
    val canSaveIso: Boolean,
)

/** One allowed value of a choice field. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class SparkPlugFieldOption(
    /** The value to send. */
    val value: String,
    /** What the user sees. */
    val label: String,
)

/** A setting of a SparkPlug that the user can change. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class SparkPlugField(
    /** Pass this as fieldId in prepareSparkPlugDraft. */
    val id: String,
    /** What the user sees. */
    val label: String,
    /** text, multiline, select or password. */
    val type: String,
    /** Help text from the SparkPlug author, often with an example value. */
    val description: String?,
    /** Whether the installer cannot be built while this is blank. */
    val required: Boolean,
    /** The value used if none is given. Empty when there is none. */
    val defaultValue: String,
    /** For select fields, the only values accepted. */
    val options: List<SparkPlugFieldOption>,
    /** A regular expression the whole value must match, when set. */
    val pattern: String?,
    /** The longest value accepted, or 0 for no limit. */
    val maxLength: Int,
    /** The wizard page the field is on. */
    val page: String?,
    /** When set, the field only applies while this condition holds, such as "pve_network_source equals from-answer". */
    val appliesWhen: String?,
    /**
     * True for secrets such as passwords. Never send a value for these: the user types
     * them in SparkWriter, and prepareSparkPlugDraft refuses them.
     */
    val userEntersThis: Boolean,
)

/** A SparkPlug and every setting the user can change. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class SparkPlugDetail(
    /** The SparkPlug. */
    val sparkPlug: SparkPlugSummary,
    /** Settings in wizard order. */
    val fields: List<SparkPlugField>,
)

/** A value for one setting. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class SparkPlugFieldValue(
    /** The field id from describeSparkPlug. */
    val fieldId: String,
    /** The value, as text. */
    val value: String,
)

/** A value that was not used, and why. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class SparkPlugDraftIssue(
    /** The field id as sent. */
    val fieldId: String,
    /** UnknownField, NotEditable, SecretField, NotAnOption, TooLong, BadFormat or Inactive. */
    val code: String,
    /** A sentence explaining the problem. */
    val message: String,
)

/**
 * The result of preparing a draft. Nothing has been written yet: the user reviews
 * the settings in SparkWriter and starts the USB write or ISO export themselves.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
class SparkPlugDraft(
    /** True when every required setting has a value, so SparkWriter opens on its Review step. */
    val complete: Boolean,
    /** Field ids whose values were applied. */
    val appliedFieldIds: List<String>,
    /** Values that were not applied. Fix and call again, or tell the user. */
    val issues: List<SparkPlugDraftIssue>,
    /** Required field ids still blank, including secrets. The user fills these in. */
    val userMustFill: List<String>,
    /**
     * Opens SparkWriter on this draft. Launch it once the user is ready to review;
     * it does not start writing anything.
     */
    val openInSparkWriter: PendingIntent,
)

internal fun SparkManifest.toAgentSummary(): SparkPlugSummary = SparkPlugSummary(
    id = metadata.id,
    name = metadata.name,
    description = metadata.description,
    version = metadata.version,
    canWriteUsb = outputs.usb,
    canSaveIso = outputs.iso,
)

internal fun SparkManifest.toAgentDetail(): SparkPlugDetail {
    val pageTitleByField = wizardPages
        .flatMap { page -> page.fields.map { it to page.title } }
        .toMap()
    val fields = configFields
        .filter { it.isAgentVisible() }
        .map { it.toAgentField(pageTitleByField[it.id]) }
    return SparkPlugDetail(toAgentSummary(), fields)
}

/** Secrets are listed (so an assistant can tell the user what they will be asked) but never fillable. */
private fun ConfigField.isAgentVisible(): Boolean =
    isDraftFillable() || (type == "password" && !hidden && isUserEditable())

private fun ConfigField.toAgentField(page: String?): SparkPlugField = SparkPlugField(
    id = id,
    label = label,
    type = type,
    description = description?.ifBlank { null },
    required = required,
    defaultValue = default,
    options = options.filterNot { it.placeholder }.map { SparkPlugFieldOption(it.value, it.label) },
    pattern = pattern,
    maxLength = maxLength ?: 0,
    page = page,
    appliesWhen = whenCondition?.let { condition ->
        listOf(condition.field, condition.operator, condition.value).filter { it.isNotEmpty() }.joinToString(" ")
    },
    userEntersThis = type == "password",
)

internal fun List<SparkPlugFieldValue>.toDraftRequest(manifestId: String): ConfigDraftRequest =
    ConfigDraftRequest(manifestId, associate { it.fieldId to it.value })

/**
 * The validation an assistant sees. The values themselves travel to the
 * activity in the PendingIntent and are checked again there.
 */
internal fun SparkManifest.validateAgentDraft(values: List<SparkPlugFieldValue>): DraftValidation =
    validateDraft(values.associate { it.fieldId to it.value })

internal fun DraftValidation.toAgentDraft(
    manifest: SparkManifest,
    openInSparkWriter: PendingIntent,
): SparkPlugDraft {
    val idByFormId = manifest.configFields.associate { field -> field.formFieldId() to field.id }
    return SparkPlugDraft(
        complete = complete,
        appliedFieldIds = accepted.keys.map { idByFormId[it] ?: it },
        issues = issues.map { SparkPlugDraftIssue(it.fieldId, it.code.name, it.message) },
        userMustFill = missingRequired,
        openInSparkWriter = openInSparkWriter,
    )
}
