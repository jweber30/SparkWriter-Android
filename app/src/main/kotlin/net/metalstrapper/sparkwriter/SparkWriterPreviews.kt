package net.metalstrapper.sparkwriter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.ConfigOption
import net.metalstrapper.sparkwriter.core.HostEventSubscription
import net.metalstrapper.sparkwriter.core.ReturnDeliverySpec
import net.metalstrapper.sparkwriter.storage.IsoRetention
import net.metalstrapper.sparkwriter.ui.theme.SparkWriterTheme

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Preview(name = "English", locale = "en", showBackground = true)
@Preview(name = "Spanish", locale = "es", showBackground = true)
@Preview(name = "French", locale = "fr", showBackground = true)
@Preview(name = "Japanese", locale = "ja", showBackground = true)
@Preview(name = "Korean", locale = "ko", showBackground = true)
@Preview(name = "Chinese", locale = "zh-rCN", showBackground = true)
@Preview(name = "Arabic", locale = "ar", showBackground = true)
@Preview(name = "Hindi", locale = "hi", showBackground = true)
annotation class AllLocalesPreview

@AllLocalesPreview
@Composable
fun NavigationBarPreview() {
    SparkWriterTheme {
        val routes = listOf(
            SparkWriterRoute.Sources,
            SparkWriterRoute.Workflow,
            SparkWriterRoute.Device,
            SparkWriterRoute.Profile,
            SparkWriterRoute.Settings,
        )
        NavigationBar {
            routes.forEachIndexed { index, route ->
                NavigationBarItem(
                    selected = index == 1,
                    onClick = {},
                    icon = {
                        Icon(
                            imageVector = when (route) {
                                SparkWriterRoute.Sources -> Icons.AutoMirrored.Filled.List
                                SparkWriterRoute.Workflow -> Icons.Filled.Build
                                SparkWriterRoute.Device -> Icons.Filled.Info
                                SparkWriterRoute.Profile -> Icons.Filled.Person
                                SparkWriterRoute.Settings -> Icons.Filled.Settings
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(route.titleRes)) },
                )
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun ManifestImportDialogPreview() {
    SparkWriterTheme {
        Surface {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.manifest_import),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.inspect_sparkplug_package),
                        fontWeight = FontWeight.SemiBold,
                    )
                    SelectionContainer {
                        Text("Valid package: 4 declared files, 18,432 bytes transferred, 42,100 bytes expanded.")
                    }
                    SelectionContainer {
                        Text(stringResource(R.string.source_value, "https://example.com/proxmox-autoinstall.zip"))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {}) { Text(stringResource(R.string.cancel)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {}) {
                            Text(stringResource(R.string.review_contents))
                        }
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun DeviceScreenPreview() {
    SparkWriterTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.nav_device),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "SanDisk Ultra USB 3.0",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Column {
                            Text(stringResource(R.string.capacity), style = MaterialTheme.typography.labelMedium)
                            Text("32.0 GB")
                        }
                        Column {
                            Text(stringResource(R.string.manufacturer), style = MaterialTheme.typography.labelMedium)
                            Text("SanDisk")
                        }
                        Column {
                            Text(stringResource(R.string.model), style = MaterialTheme.typography.labelMedium)
                            Text("Ultra")
                        }
                        Column {
                            Text(stringResource(R.string.usb_id_path), style = MaterialTheme.typography.labelMedium)
                            Text("001:004")
                        }
                        Column {
                            Text(stringResource(R.string.permission), style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.not_allowed))
                        }
                        Button(onClick = {}) {
                            Text(stringResource(R.string.allow_usb))
                        }
                    }
                }
                Button(onClick = {}) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@AllLocalesPreview
@Composable
fun ProfileScreenPreview() {
    SparkWriterTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.nav_profile),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.profile_description))

                val fields = listOf(
                    R.string.username to "admin",
                    R.string.email to "admin@example.org",
                    R.string.network_suffix to "lan",
                    R.string.wifi_ssid to "HomeNet",
                    R.string.timezone to "UTC",
                    R.string.keyboard_layout to "us",
                )

                fields.forEach { (labelRes, initialValue) ->
                    val label = stringResource(labelRes)
                    OutlinedTextField(
                        value = initialValue,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(label) },
                    )
                    TextButton(onClick = {}) {
                        Text(stringResource(R.string.clear_field, label))
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}) {
                        Text(stringResource(R.string.save))
                    }
                    Button(onClick = {}) {
                        Text(stringResource(R.string.clear_all))
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun SettingsScreenPreview() {
    SparkWriterTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.settings_description))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.allow_local_zip_import), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.allow_local_zip_import_description))
                    }
                    Switch(checked = true, onCheckedChange = {})
                }

                Button(onClick = {}) {
                    Text(stringResource(R.string.import_sparkplug_zip))
                }

                Text(stringResource(R.string.iso_storage), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                IsoRetention.entries.forEach { option ->
                    val label = when (option) {
                        IsoRetention.DeleteAfterWrite -> stringResource(R.string.iso_retention_delete_after_write)
                        IsoRetention.KeepRecent -> stringResource(R.string.iso_retention_keep_recent)
                        IsoRetention.KeepUntilDeleted -> stringResource(R.string.iso_retention_keep_until_deleted)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == IsoRetention.KeepRecent,
                                role = Role.RadioButton,
                                onClick = {},
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == IsoRetention.KeepRecent, onClick = {})
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun ConfigFieldsPreview() {
    SparkWriterTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val selectField = ConfigField(
                    id = "filesystem",
                    label = stringResource(R.string.shared_text_filesystem),
                    type = "select",
                    options = listOf(
                        ConfigOption(value = "ext4", label = "ext4"),
                        ConfigOption(value = "xfs", label = "XFS"),
                        ConfigOption(value = "zfs", label = "ZFS (RAID 0)"),
                    ),
                )
                ConfigFieldEditor(
                    field = selectField,
                    value = "ext4",
                    onValueChange = {},
                    provenance = PrefillProvenance(PrefillSourceKind.HostProfile, "filesystem", "Profile: filesystem"),
                )

                RevealablePasswordField(
                    value = "s3cr3tP@ss!",
                    onValueChange = {},
                    label = stringResource(R.string.shared_text_root_password) + " *",
                )

                GeneratedSecretRow(
                    label = stringResource(R.string.shared_text_admin_password),
                    value = "W9#mK2\$pQ8@xL1&z",
                    onCopy = {},
                )

                val hostnameField = ConfigField(
                    id = "hostname",
                    label = stringResource(R.string.shared_text_hostname),
                    maxLength = 63,
                )
                ConfigFieldEditor(
                    field = hostnameField,
                    value = "pve-node-01",
                    onValueChange = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@AllLocalesPreview
@Composable
fun SparkPlugReviewPreview() {
    SparkWriterTheme {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.review_title)) },
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.review_disclaimer),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Proxmox VE 8.2 Autoinstall",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("net.metalstrapper.proxmox.autoinstall")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {},
                    ) {
                        Checkbox(checked = true, onCheckedChange = {})
                        Text(
                            stringResource(R.string.review_acknowledge),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = {}) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {}) {
                            Text(stringResource(R.string.import_sparkplug))
                        }
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun SourcesCatalogPreview() {
    SparkWriterTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.nav_sources),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}) { Text(stringResource(R.string.local_zip)) }
                    Button(onClick = {}) { Text(stringResource(R.string.new_group)) }
                }

                // Group 1: Virtualization
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {},
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.collapse_group))
                    Text("Virtualization (2)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                }

                Card(Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        IconButton(onClick = {}) {
                            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.drag_to_move_group))
                        }
                        Text("Proxmox VE 8.2 Autoinstall", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }

                // Group 2: Linux Distributions (collapsed)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {},
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.expand_group))
                    Text("Linux Distributions (3)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@AllLocalesPreview
@Composable
fun WorkflowWizardPreview() {
    SparkWriterTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Proxmox VE 8.2 Autoinstall",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.source_details))
                    }
                }

                // ISO Acquisition Card
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.download_then_pick_iso))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = {}) { Text(stringResource(R.string.open_download)) }
                            Button(onClick = {}) { Text(stringResource(R.string.pick_iso)) }
                            Button(onClick = {}) { Text(stringResource(R.string.open_torrent)) }
                        }
                    }
                }

                // Configuration Step Card
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.configuration), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.step_of, "Host & Network", 1, 3),
                            fontWeight = FontWeight.SemiBold,
                        )

                        Text(stringResource(R.string.host_profiles), fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = {}) { Text("Lab Profile") }
                            Button(onClick = {}) { Text("Production Node") }
                        }

                        val hostField = ConfigField(
                            id = "fqdn",
                            label = stringResource(R.string.shared_text_hostname) + " *",
                            maxLength = 63,
                        )
                        ConfigFieldEditor(
                            field = hostField,
                            value = "pve-node-01.lan",
                            onValueChange = {},
                            provenance = PrefillProvenance(PrefillSourceKind.HostProfile, "pve", "Profile: Lab"),
                        )

                        Button(onClick = {}) {
                            Text(stringResource(R.string.advanced))
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = {}, enabled = false) {
                                Text(stringResource(R.string.back))
                            }
                            Button(onClick = {}) {
                                Text(stringResource(R.string.continue_label))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@AllLocalesPreview
@Composable
fun WorkflowReviewStepPreview() {
    SparkWriterTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.review), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.step_of, stringResource(R.string.review), 3, 3),
                            fontWeight = FontWeight.SemiBold,
                        )

                        val reviewFields = listOf(
                            ConfigField(id = "hostname", label = stringResource(R.string.shared_text_hostname)),
                            ConfigField(id = "admin_user", label = stringResource(R.string.username)),
                            ConfigField(id = "root_pwd", label = stringResource(R.string.shared_text_root_password), type = "password"),
                        )
                        val reviewValues = mapOf(
                            "hostname" to "pve-01.internal",
                            "admin_user" to "sysadmin",
                            "root_pwd" to "super-secret-pass",
                        )
                        val provenance = mapOf(
                            "hostname" to PrefillProvenance(PrefillSourceKind.HostProfile, "id", "Profile: System"),
                        )
                        val returnDelivery = HostEventSubscription(
                            event = "write_complete",
                            returnDelivery = ReturnDeliverySpec(
                                url = "https://hook.example.com/events",
                                method = "POST",
                                deliveryMode = "required",
                            ),
                        )

                        ReviewSummary(
                            fields = reviewFields,
                            values = reviewValues,
                            provenance = provenance,
                            hostEvents = listOf(returnDelivery),
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = {}) {
                                Text(stringResource(R.string.back))
                            }
                        }
                    }
                }

                // Generated Credentials Card
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.generated_credentials), fontWeight = FontWeight.SemiBold)
                        GeneratedSecretRow(
                            label = stringResource(R.string.shared_text_admin_password),
                            value = "tX7#kL9\$vP2@mN4*",
                            onCopy = {},
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@AllLocalesPreview
@Composable
fun WorkflowUsbTargetPreview() {
    SparkWriterTheme {
        Surface {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.usb_target), fontWeight = FontWeight.SemiBold)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(selected = true, onClick = {})
                        Column(Modifier.weight(1f)) {
                            Text("SanDisk Extreme PRO USB 3.2", fontWeight = FontWeight.SemiBold)
                            Text("64.0 GB")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(selected = false, onClick = {})
                        Column(Modifier.weight(1f)) {
                            Text("Kingston DataTraveler 3.0", fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.size_unknown_allow_usb))
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(checked = true, onCheckedChange = {})
                        Text(
                            stringResource(R.string.erase_selected_usb_confirmation),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(checked = true, onCheckedChange = {})
                        Text(
                            stringResource(R.string.verify_usb_after_write),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = {}) {
                            Text(stringResource(R.string.start_write))
                        }
                        Button(onClick = {}) {
                            Text(stringResource(R.string.save_assembled_iso))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@AllLocalesPreview
@Composable
fun WriteProgressTopAppBarPreview() {
    SparkWriterTheme {
        Surface {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Column(
                            modifier = Modifier.width(320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                            append(stringResource(R.string.write_in_progress))
                                        }
                                        append(" 45% - 1.2 GB / 2.8 GB (35 MB/s)")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { 0.45f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}

@AllLocalesPreview
@Composable
fun IsoImportProgressCardPreview() {
    SparkWriterTheme {
        Surface {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.importing_iso), fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { 0.52f }, modifier = Modifier.fillMaxWidth())
                    SelectionContainer {
                        Text(
                            "Starting ISO import (1.4 GB / 2.7 GB)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun WriteErrorDialogPreview() {
    SparkWriterTheme {
        Surface {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.error),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("USB bulk write stalled on endpoint 0x02 during block 142080.")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {}) { Text(stringResource(R.string.copy)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {}) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun NotificationPermissionDialogPreview() {
    SparkWriterTheme {
        Surface {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.notification_permission_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.notification_permission_explanation))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {}) { Text(stringResource(R.string.not_now)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {}) { Text(stringResource(R.string.continue_label)) }
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun CreateGroupDialogPreview() {
    SparkWriterTheme {
        Surface {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.create_source_group),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = "Custom Clusters",
                        onValueChange = {},
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {}) { Text(stringResource(R.string.cancel)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {}) { Text(stringResource(R.string.create)) }
                    }
                }
            }
        }
    }
}

@AllLocalesPreview
@Composable
fun SourceDetailsDialogPreview() {
    SparkWriterTheme {
        Surface {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.source_details),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val details = listOf(
                        stringResource(R.string.iso_name) to "proxmox-ve_8.2-1.iso",
                        stringResource(R.string.source_iso) to "Proxmox VE 8.2 ISO Installer",
                        stringResource(R.string.url) to "https://enterprise.proxmox.com/iso/proxmox-ve_8.2-1.iso",
                        stringResource(R.string.sparkplug_description) to "Automated installation template for Proxmox VE 8.2",
                    )
                    details.forEach { (label, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {}) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }
    }
}
