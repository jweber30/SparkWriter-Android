package net.metalstrapper.sparkwriter

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.FieldCondition
import net.metalstrapper.sparkwriter.core.SparkManifest
import java.util.Locale
import java.util.TimeZone

private const val DEFAULT_FIELD_PREFIX = "default."

private fun String.toCanonicalFieldId(): String = removePrefix(DEFAULT_FIELD_PREFIX).replace('-', '_')

internal fun ConfigField.formFieldId(): String = id.toCanonicalFieldId()

private fun Map<String, String>.lookupFieldValue(fieldId: String): String? {
    val canonicalFieldId = fieldId.toCanonicalFieldId()
    this[canonicalFieldId]?.let { return it }
    return entries.firstOrNull { (key) -> key.toCanonicalFieldId() == canonicalFieldId }?.value
}

private fun Map<String, String>.lookupFieldValue(field: ConfigField): String? = lookupFieldValue(field.formFieldId())

internal fun Map<String, String>.valueFor(field: ConfigField): String = lookupFieldValue(field).orEmpty()

internal fun ConfigField.exceedsMaxLength(value: String): Boolean = maxLength?.let { value.length > it } == true

/** An empty value never violates the pattern; `required` covers blanks. */
internal fun ConfigField.violatesPattern(value: String): Boolean =
    pattern?.let { value.isNotEmpty() && !Regex(it).matches(value) } == true

/**
 * Render-time backstop for max_length and pattern; prefill and saved values can
 * bypass the editor. Only fields present in [values] are checked, so a field the
 * wizard hid is not held to its pattern.
 */
internal fun SparkManifest.requireConfigFieldLengths(values: Map<String, String>) {
    configFields.forEach { field ->
        val value = values.valueFor(field)
        if (field.exceedsMaxLength(value)) {
            error("${field.label} is ${value.length} characters; the limit is ${field.maxLength}")
        }
        if (field.violatesPattern(value)) {
            error("${field.label} is not in the expected format: $value")
        }
    }
}

internal fun MutableMap<String, String>.setFieldValue(field: ConfigField, value: String) {
    this[field.formFieldId()] = value
}

internal fun applyDetectedDefaultsToField(
    context: Context,
    values: MutableMap<String, String>,
    field: ConfigField,
) {
    when (field.id) {
        "locale.locale" -> detectDeviceLocale()?.let { values.putIfAbsent("locale_locale", it) }
        "locale.keyboard" -> detectDeviceKeyboardLayout(context)?.let { values.putIfAbsent("locale_keyboard", it) }
        "locale.timezone" -> detectDeviceTimezone()?.let { values.putIfAbsent("locale_timezone", it) }
    }
}

private fun detectDeviceLocale(): String? = runCatching {
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        android.os.LocaleList.getDefault().get(0)
    } else {
        Locale.getDefault()
    } ?: Locale.getDefault()
    val localeCode = locale.country.takeIf { it.isNotBlank() }
    if (localeCode.isNullOrBlank()) "${locale.language}.UTF-8" else "${locale.language}_${localeCode}.UTF-8"
}.getOrNull()

private fun detectDeviceTimezone(): String? = runCatching {
    TimeZone.getDefault().id
}.getOrNull()

private fun detectDeviceKeyboardLayout(context: Context): String? = runCatching {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val subtype = imm?.currentInputMethodSubtype
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        subtype?.languageTag.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        subtype?.locale.orEmpty()
    }
    val inputLocale = locale.takeIf { it.isNotBlank() }?.let {
        Locale.forLanguageTag(it.replace('_', '-'))
    } ?: Locale.getDefault()
    inputLocale.country.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
        ?: inputLocale.language.lowercase(Locale.ROOT)
}.getOrNull()

internal fun SparkManifest.activeConfigValues(values: Map<String, String>): Map<String, String> {
    val valuesWithDefaults = buildMap {
        configFields
            .filter { it.default.isNotEmpty() }
            .forEach { put(it.formFieldId(), it.default) }
        values.forEach { (key, value) ->
            put(key.toCanonicalFieldId(), value)
            put(key, value)
        }
    }
    val activeFields = activeConfigFields(valuesWithDefaults)
    return buildMap {
        putAll(source?.metadata.orEmpty())
        activeFields.forEach { field ->
            val value = valuesWithDefaults.lookupFieldValue(field) ?: return@forEach
            val formFieldId = field.formFieldId()
            put(formFieldId, value)
            if (field.id != formFieldId) {
                put(field.id, value)
            }
        }
        activeFields.derivedTemplateValues(valuesWithDefaults).forEach { (key, value) ->
            putIfAbsent(key, value)
        }
    }
}

private fun List<ConfigField>.derivedTemplateValues(values: Map<String, String>): Map<String, String> = buildMap {
    for (field in this@derivedTemplateValues) {
        val value = values.lookupFieldValue(field.formFieldId()) ?: continue
        val canonicalFieldId = field.formFieldId()
        if (field.type == "array") {
            val tomlValue = value.toTomlStringArray()
            put("${canonicalFieldId}_toml", tomlValue)
        }
    }
}

private fun String.toTomlStringArray(): String {
    val trimmed = trim()
    val values = when {
        trimmed.startsWith("[") && trimmed.endsWith("]") -> {
            trimmed.removeSurrounding("[", "]")
                .split(',')
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotBlank() }
        }
        trimmed.contains('\n') -> lineSequence().map { it.trim().trimEnd(',') }.filter { it.isNotBlank() }.toList()
        trimmed.contains(',') -> split(',').map { it.trim() }.filter { it.isNotBlank() }
        trimmed.isBlank() -> emptyList()
        else -> listOf(trimmed)
    }
    return values.joinToString(prefix = "[", postfix = "]") { item ->
        "\"" + item.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/**
 * Fields whose `when` condition holds. A condition on a field that is itself
 * inactive does not hold, so a field nested under a hidden choice stays hidden.
 */
internal fun SparkManifest.activeConfigFields(values: Map<String, String>): List<ConfigField> {
    val byId = configFields.associateBy { it.formFieldId() }
    val memo = mutableMapOf<String, Boolean>()
    fun isActive(field: ConfigField, seen: Set<String>): Boolean = memo.getOrPut(field.formFieldId()) {
        val condition = field.whenCondition ?: return@getOrPut true
        if (!condition.matches(values)) return@getOrPut false
        val parent = byId[condition.field.toCanonicalFieldId()] ?: return@getOrPut true
        parent.formFieldId() !in seen && isActive(parent, seen + field.formFieldId())
    }
    return configFields.filter { isActive(it, emptySet()) }
}

internal fun FieldCondition?.matches(values: Map<String, String>): Boolean {
    this ?: return true
    val actual = values.lookupFieldValue(field).orEmpty()
    return when (operator) {
        "equals" -> actual == value
        "not_equals" -> actual != value
        "contains" -> actual.lineSequence().any { it == value } || actual.contains(value)
        "not_contains" -> actual.lineSequence().none { it == value } && !actual.contains(value)
        "empty" -> actual.isEmpty()
        "not_empty" -> actual.isNotEmpty()
        else -> true
    }
}
