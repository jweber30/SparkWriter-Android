package net.metalstrapper.sparkwriter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.metalstrapper.sparkwriter.core.ConfigField

@Composable
internal fun RevealablePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        minLines = minLines,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                onClick = { visible = !visible },
                enabled = value.isNotBlank(),
            ) {
                Text(if (visible) stringResource(R.string.hide) else stringResource(R.string.show))
            }
        },
    )
}

@Composable
internal fun GeneratedSecretRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(if (visible) value else "************************")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { visible = !visible }) {
                Text(if (visible) stringResource(R.string.hide) else stringResource(R.string.reveal))
            }
            Button(onClick = onCopy) {
                Text(stringResource(R.string.copy))
            }
        }
    }
}

@Composable
internal fun ConfigFieldEditor(
    field: ConfigField,
    value: String,
    onValueChange: (String) -> Unit,
    provenance: PrefillProvenance? = null,
    readOnly: Boolean = false,
    fetching: Boolean = false,
    fetchEnabled: Boolean = true,
    onFetch: (() -> Unit)? = null,
) {
    val editable = field.isUserEditable() && !readOnly
    // An over-limit edit (usually a paste) is refused rather than truncated, so a
    // pasted command never silently loses its tail. Shrinking is always allowed.
    var rejectedOverLimit by rememberSaveable(field.id) { mutableStateOf(false) }
    val limitedValueChange: (String) -> Unit = { next ->
        if (field.exceedsMaxLength(next) && next.length > value.length) {
            rejectedOverLimit = true
        } else {
            rejectedOverLimit = false
            onValueChange(next)
        }
    }
    val overLimit = rejectedOverLimit || field.exceedsMaxLength(value)
    val lengthSupportingText: (@Composable () -> Unit)? = field.maxLength?.let { maxLength ->
        {
            Text(
                if (overLimit) {
                    stringResource(R.string.field_too_long, maxLength)
                } else {
                    stringResource(R.string.field_length_counter, value.length, maxLength)
                },
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        when (field.type) {
            "select" -> ConfigSelectField(
                field = field,
                value = value,
                onValueChange = onValueChange,
                enabled = editable,
            )
            "info" -> {
                Text(field.label, fontWeight = FontWeight.SemiBold)
                if (value.isNotBlank()) {
                    SelectionContainer {
                        Text(value)
                    }
                }
            }
            "password" -> RevealablePasswordField(
                value = value,
                onValueChange = limitedValueChange,
                label = field.requiredLabel(),
                placeholder = field.placeholder,
                enabled = editable,
                minLines = if (field.big) 4 else 1,
                isError = overLimit,
                supportingText = lengthSupportingText,
            )
            else -> OutlinedTextField(
                value = value,
                onValueChange = limitedValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.requiredLabel()) },
                placeholder = field.placeholder?.let { { Text(it) } },
                minLines = if (field.type == "multiline" || field.big) 4 else 1,
                enabled = editable,
                isError = overLimit,
                supportingText = lengthSupportingText,
            )
        }
        provenance?.let {
            Text(stringResource(R.string.prefilled_from, it.label), style = MaterialTheme.typography.bodySmall)
        }
        if (field.fetch != null && onFetch != null && ExternalServiceGate.enabled) {
            Button(
                onClick = onFetch,
                enabled = editable && fetchEnabled && !fetching,
            ) {
                Text(if (fetching) stringResource(R.string.fetching) else stringResource(R.string.fetch))
            }
        }
        field.description?.ifBlank { null }?.let { description ->
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfigSelectField(
    field: ConfigField,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = field.isUserEditable(),
) {
    val editable = enabled
    val selected = field.options.firstOrNull { it.value == value }
    val actualOptions = field.options.filterNot { it.placeholder }
    val placeholder = field.options.firstOrNull { it.placeholder }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(field.requiredLabel(), fontWeight = FontWeight.SemiBold)
        if (selected == null || selected.placeholder) {
            placeholder?.label?.ifBlank { null }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        (actualOptions.ifEmpty { field.options }).forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = editable && !option.placeholder) { onValueChange(option.value) },
            ) {
                RadioButton(
                    selected = option.value == value,
                    onClick = { onValueChange(option.value) },
                    enabled = editable && !option.placeholder,
                )
                Text(
                    option.label,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
            }
        }
    }
}
