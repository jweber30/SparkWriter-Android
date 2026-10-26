package net.metalstrapper.sparkwriter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import net.metalstrapper.sparkwriter.core.PermissionResult
import net.metalstrapper.sparkwriter.storage.AppSettingsStore
import net.metalstrapper.sparkwriter.storage.ProfileStore
import net.metalstrapper.sparkwriter.usb.UsbBlockWriter

private data class SettingsFieldSpec(
    val key: String,
    @StringRes val labelRes: Int,
    val multiline: Boolean = false,
    val isPassword: Boolean = false,
)

private val settingsProfileFields = listOf(
    SettingsFieldSpec("user.username", R.string.username),
    SettingsFieldSpec("user.email", R.string.email),
    SettingsFieldSpec("user.ssh_public_keys", R.string.ssh_public_keys, multiline = true),
    SettingsFieldSpec("network.suffix", R.string.network_suffix),
    SettingsFieldSpec("network.wifi.ssid", R.string.wifi_ssid),
    SettingsFieldSpec("locale.timezone", R.string.timezone),
    SettingsFieldSpec("locale.keyboard", R.string.keyboard_layout),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceScreen(
    usbWriter: UsbBlockWriter,
    writeActive: Boolean,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<ProbedUsbDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }

    fun refresh() {
        if (writeActive) return
        scope.launch {
            scanning = true
            runCatching {
                usbWriter.listDevices().map { device ->
                    runCatching {
                        usbWriter.probe(device.id).let { ProbedUsbDevice(it.device, it.permissionGranted) }
                    }.getOrNull() ?: ProbedUsbDevice(device, false)
                }
            }.onSuccess {
                devices = it
                onStatus(if (it.isEmpty()) "No USB mass-storage device found" else "USB device details refreshed")
            }.onFailure {
                onStatus("USB scan failed: ${it.message}")
            }
            scanning = false
        }
    }

    LaunchedEffect(writeActive) {
        if (!writeActive) refresh()
    }
    DisposableEffect(lifecycleOwner, writeActive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !writeActive) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context, writeActive) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                    intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED
                ) refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.nav_device), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (devices.isEmpty()) {
            item { Text(stringResource(if (scanning) R.string.device_scanning else R.string.device_none)) }
        }
        items(devices, key = { it.device.id }) { probed ->
            val device = probed.device
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    DeviceDetailRow(stringResource(R.string.capacity), device.sizeHuman)
                    DeviceDetailRow(stringResource(R.string.manufacturer), device.vendor ?: stringResource(R.string.unknown))
                    DeviceDetailRow(stringResource(R.string.model), device.model ?: stringResource(R.string.unknown))
                    DeviceDetailRow(stringResource(R.string.usb_id_path), device.path.removeUsbSchemePrefix())
                    DeviceDetailRow(stringResource(R.string.permission), stringResource(if (probed.permissionGranted) R.string.allowed else R.string.not_allowed))
                    if (!probed.permissionGranted) {
                        Button(
                            enabled = !writeActive,
                            onClick = {
                                scope.launch {
                                    when (usbWriter.requestPermission(device.id)) {
                                        PermissionResult.Granted -> refresh()
                                        PermissionResult.Denied -> onStatus("USB permission denied")
                                        is PermissionResult.NeedsUserAction -> onStatus("USB permission requested")
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.allow_usb)) }
                    }
                }
            }
        }
        item {
            Button(onClick = { refresh() }, enabled = !writeActive && !scanning) {
                Text(stringResource(if (scanning) R.string.refreshing else R.string.refresh))
            }
        }
    }
}

@Composable
private fun DeviceDetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileScreen(
    onSaveCompleted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profileStore = remember(context) { ProfileStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>() }
    var status by remember { mutableStateOf(context.getString(R.string.no_changes)) }
    var saving by remember { mutableStateOf(false) }
    var hasLoaded by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val storedValues = profileStore.loadAll(settingsProfileFields.map { it.key })
        values.clear()
        values.putAll(storedValues)
        hasLoaded = true
    }

    suspend fun saveCurrentValues(statusMessage: String) {
        val payload = settingsProfileFields.associate { it.key to values[it.key].orEmpty() }
        profileStore.saveAll(payload)
        onSaveCompleted(statusMessage)
        status = statusMessage
    }

    val allProfileKeys = remember { settingsProfileFields.map { it.key } }
    val showContent = hasLoaded

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(stringResource(R.string.nav_profile), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.profile_description))
            if (!showContent) {
                Text(stringResource(R.string.loading_profile))
            }
        }
        if (showContent) {
            items(settingsProfileFields) { field ->
                val fieldLabel = stringResource(field.labelRes)
                if (field.isPassword) {
                    RevealablePasswordField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { values[field.key] = it },
                        label = fieldLabel,
                        enabled = !saving,
                        minLines = if (field.multiline) 4 else 1,
                    )
                } else {
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { values[field.key] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(fieldLabel) },
                        minLines = if (field.multiline) 4 else 1,
                        enabled = !saving,
                    )
                }
                TextButton(
                    onClick = { values[field.key] = "" },
                    enabled = !saving,
                ) {
                    Text(stringResource(R.string.clear_field, fieldLabel))
                }
                Spacer(Modifier.height(4.dp))
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!saving) {
                                saving = true
                                scope.launch {
                                    runCatching { saveCurrentValues("Profile settings saved") }
                                        .onFailure { error ->
                                            val message = "Failed to save settings: ${error.message ?: error::class.simpleName}"
                                            status = message
                                            onSaveCompleted(message)
                                        }
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving,
                    ) {
                        Text(stringResource(if (saving) R.string.saving else R.string.save))
                    }
                    Button(
                        onClick = { confirmClearAll = true },
                        enabled = !saving,
                    ) {
                        Text(stringResource(R.string.clear_all))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(status)
            }
        }
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.clear_all_profile_settings_title)) },
            text = { Text(stringResource(R.string.clear_all_profile_settings_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    allProfileKeys.forEach { values[it] = "" }
                    saving = true
                    scope.launch {
                        runCatching { saveCurrentValues(context.getString(R.string.profile_settings_cleared)) }
                            .onFailure { error ->
                                val message = context.getString(R.string.profile_settings_clear_failed, error.message ?: error::class.simpleName.orEmpty())
                                status = message
                                onSaveCompleted(message)
                            }
                        saving = false
                    }
                }) { Text(stringResource(R.string.clear_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettingsStore = remember(context) { AppSettingsStore(context.applicationContext) }
    val localZipImportEnabled by appSettingsStore.localZipImportEnabled.collectAsState(initial = false)
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.settings_description))
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.allow_local_zip_import), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.allow_local_zip_import_description))
                }
                Switch(
                    checked = localZipImportEnabled,
                    onCheckedChange = { enabled -> scope.launch { appSettingsStore.setLocalZipImportEnabled(enabled) } },
                )
            }
        }
    }
}
