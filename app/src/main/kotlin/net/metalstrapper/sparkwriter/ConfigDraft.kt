package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.SparkManifest
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/*
 * Drafts let something outside the wizard (an assistant through AppFunctions, a
 * `spark://draft` link, a test harness) fill in a SparkPlug's config and open the
 * wizard for review. A draft only fills in values. It never starts a write or an
 * export: the user still confirms the target and taps Write or Save on the Review
 * step, exactly as when the values were typed by hand.
 *
 * Drafts never carry secrets. Password fields are refused, so a required password
 * leaves the draft incomplete and the user lands on that page to type it.
 */

/** A draft as it arrives, before it is checked against the SparkPlug. */
data class ConfigDraftRequest(
    val manifestId: String,
    val values: Map<String, String>,
)

/** A checked draft handed to the wizard. [values] are keyed by form field id. */
internal data class AppliedConfigDraft(
    val manifestId: String,
    val values: Map<String, String>,
    val nonce: Long,
)

internal enum class DraftIssueCode {
    UnknownField,
    NotEditable,
    SecretField,
    NotAnOption,
    TooLong,
    BadFormat,
    Inactive,
}

internal data class DraftIssue(
    val fieldId: String,
    val code: DraftIssueCode,
    val message: String,
)

internal data class DraftValidation(
    /** Values the wizard will apply, keyed by form field id. */
    val accepted: Map<String, String>,
    /** Values that were dropped, with the reason. */
    val issues: List<DraftIssue>,
    /** Ids of required fields still blank after the draft and defaults. The user fills these in. */
    val missingRequired: List<String>,
) {
    val complete: Boolean get() = missingRequired.isEmpty()
}

/** Whether a field may be blank before the user can move past its page. */
internal fun ConfigField.blocksUserProgressWhenBlank(): Boolean = required && isUserEditable()

/** The same completeness rule the wizard uses for its Next and Write buttons. */
internal fun ConfigField.isCompleteFor(value: String): Boolean =
    (!blocksUserProgressWhenBlank() || value.isNotBlank()) &&
        !exceedsMaxLength(value) &&
        !violatesPattern(value)

/** Fields a draft may fill: shown, editable, not informational, and not a secret. */
internal fun ConfigField.isDraftFillable(): Boolean =
    !hidden && isUserEditable() && type != "info" && type != "password"

/**
 * Checks [input] against this SparkPlug's fields. Field ids may be given as the
 * manifest id (`default.host-name`) or the form id (`host_name`). Every value that
 * is not applied is reported in [DraftValidation.issues], so a caller can correct
 * it and try again.
 */
internal fun SparkManifest.validateDraft(input: Map<String, String>): DraftValidation {
    val byFormId = configFields.associateBy { it.formFieldId() }
    val issues = mutableListOf<DraftIssue>()
    val candidates = linkedMapOf<String, String>()
    input.forEach { (rawId, value) ->
        val field = configFields.firstOrNull { it.id == rawId }
            ?: byFormId[rawId.toCanonicalFieldId()]
        when {
            field == null ->
                issues += DraftIssue(rawId, DraftIssueCode.UnknownField, "No field named $rawId")
            field.type == "password" ->
                issues += DraftIssue(field.id, DraftIssueCode.SecretField, "${field.label} is a secret; the user enters it")
            !field.isDraftFillable() ->
                issues += DraftIssue(field.id, DraftIssueCode.NotEditable, "${field.label} cannot be changed")
            field.type == "select" && field.options.none { it.value == value } ->
                issues += DraftIssue(
                    field.id,
                    DraftIssueCode.NotAnOption,
                    "${field.label} must be one of: ${field.options.joinToString { it.value }}",
                )
            field.exceedsMaxLength(value) ->
                issues += DraftIssue(field.id, DraftIssueCode.TooLong, "${field.label} is limited to ${field.maxLength} characters")
            field.violatesPattern(value) ->
                issues += DraftIssue(field.id, DraftIssueCode.BadFormat, "${field.label} does not match ${field.pattern}")
            else -> candidates[field.formFieldId()] = value
        }
    }

    val merged = defaultFormValues() + candidates
    val active = activeConfigFields(merged).map { it.formFieldId() }.toSet()
    val accepted = candidates.filterKeys { it in active }
    candidates.keys.filterNot { it in active }.forEach { formId ->
        val field = byFormId.getValue(formId)
        issues += DraftIssue(field.id, DraftIssueCode.Inactive, "${field.label} does not apply with the other values given")
    }

    val withAccepted = defaultFormValues() + accepted
    val missingRequired = activeConfigFields(withAccepted)
        .filter { !it.hidden && it.blocksUserProgressWhenBlank() && withAccepted.valueFor(it).isBlank() }
        .map { it.id }
    return DraftValidation(accepted, issues, missingRequired)
}

private fun SparkManifest.defaultFormValues(): Map<String, String> =
    configFields.filter { it.default.isNotEmpty() }.associate { it.formFieldId() to it.default }

/**
 * The wizard step a draft should open on, given the form's full [values]: the
 * first page with an incomplete field, otherwise the Review step (or the last
 * page when this SparkPlug has no Review step).
 */
internal fun SparkManifest.draftLandingStep(values: Map<String, String>): Int {
    val displayFields = activeConfigFields(values).filterNot { it.hidden }
    val pages = displayWizardPages(displayFields)
    val firstIncomplete = pages.indexOfFirst { page ->
        page.fields.any { !it.isCompleteFor(values.valueFor(it)) }
    }
    if (firstIncomplete >= 0) return firstIncomplete
    return if (requiresReviewStep(displayFields)) pages.size else (pages.size - 1).coerceAtLeast(0)
}

internal const val DRAFT_URI_SCHEME = "spark"
internal const val DRAFT_URI_HOST = "draft"
private const val DRAFT_PLUG_PARAM = "plug"
private const val DRAFT_FIELD_PREFIX = "f."

/** Longest draft URI accepted; well above any real config, well below Binder limits. */
internal const val MAX_DRAFT_URI_LENGTH = 64 * 1024

/**
 * `spark://draft?plug=<metadata.id>&f.<field id>=<value>&...`
 *
 * Values are URL-encoded. Unrecognised parameters are ignored. Returns null for
 * anything that is not a well-formed draft link.
 */
internal fun parseDraftUri(raw: String): ConfigDraftRequest? {
    if (raw.length > MAX_DRAFT_URI_LENGTH) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.scheme.equals(DRAFT_URI_SCHEME, ignoreCase = true)) return null
    if (!uri.host.equals(DRAFT_URI_HOST, ignoreCase = true)) return null
    if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
    var manifestId: String? = null
    val values = linkedMapOf<String, String>()
    uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { pair ->
        val key = decode(pair.substringBefore('=')) ?: return null
        val value = decode(pair.substringAfter('=', "")) ?: return null
        when {
            key == DRAFT_PLUG_PARAM -> manifestId = value
            key.startsWith(DRAFT_FIELD_PREFIX) && key.length > DRAFT_FIELD_PREFIX.length ->
                values[key.removePrefix(DRAFT_FIELD_PREFIX)] = value
        }
    }
    return ConfigDraftRequest(manifestId?.takeIf { it.isNotBlank() } ?: return null, values)
}

internal fun buildDraftUri(request: ConfigDraftRequest): String = buildString {
    append("$DRAFT_URI_SCHEME://$DRAFT_URI_HOST?$DRAFT_PLUG_PARAM=")
    append(encode(request.manifestId))
    request.values.forEach { (field, value) ->
        append('&').append(encode(DRAFT_FIELD_PREFIX + field)).append('=').append(encode(value))
    }
}

private fun decode(value: String): String? =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
