package net.metalstrapper.sparkwriter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.core.ConfigField
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.FormPrefillButton
import net.metalstrapper.sparkwriter.core.GeneratedUserSecret
import net.metalstrapper.sparkwriter.core.HostProfile
import net.metalstrapper.sparkwriter.core.HostEventSubscription
import net.metalstrapper.sparkwriter.core.PermissionResult
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.localized
import net.metalstrapper.sparkwriter.storage.IsoIndexStore
import net.metalstrapper.sparkwriter.storage.ProfileStore
import net.metalstrapper.sparkwriter.usb.UsbBlockWriter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkflowPane(
    sourceManifest: SparkManifest,
    importedImage: VerifiedImage?,
    generatedSecrets: Map<String, String>,
    onGeneratedSecrets: (Map<String, String>) -> Unit,
    draft: AppliedConfigDraft?,
    usbWriter: UsbBlockWriter,
    isoIndexStore: IsoIndexStore,
    status: String,
    manifestInstallInProgress: Boolean,
    activeWriteInProgress: Boolean,
    appScope: CoroutineScope,
    onPickImage: () -> Unit,
    onWritingChange: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onWriteProgress: (Float, String) -> Unit,
    onWriteError: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Same sparkplug with its display text in the user's language.
    val manifest = remember(sourceManifest, configuration) {
        sourceManifest.localized(context.sparkPlugLocalizer())
    }
    val clipboard = LocalClipboard.current
    val profileStore = remember(context) { ProfileStore(context.applicationContext) }
    val values = remember(manifest.metadata.id) { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceRef>>(emptyList()) }
    var devicePermissionById by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var confirmedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var verifyBaseImage by rememberSaveable { mutableStateOf(true) }
    var writing by remember { mutableStateOf(false) }
    var savingAssembledImage by remember { mutableStateOf(false) }
    var pendingAssembledImageSave by remember { mutableStateOf<PendingAssembledImageSave?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var configPageIndex by rememberSaveable(manifest.metadata.id) { mutableStateOf(0) }
    // Passwords shown on the review step that the next write or save will use.
    var pendingSecrets by remember(manifest.metadata.id) { mutableStateOf<List<GeneratedUserSecret>?>(null) }
    val manifestAcquisition = remember(manifest.metadata.id, manifest.source) {
        runCatching { manifest.imageAcquisitionPlan() }.getOrNull()
    }
    val localUnavailableReasons = remember(manifest.metadata.id, manifest.source, manifest.installer) {
        manifest.localAndroidUnavailableReasons()
    }
    val writeActive = writing || savingAssembledImage || activeWriteInProgress
    val hasTorrentDeclaration = manifest.source?.acquire?.kind?.equals("torrent", ignoreCase = true) == true
    val hasImportedImage = importedImage != null
    var showSourceDetails by remember(manifest.metadata.id) { mutableStateOf(false) }
    val showOpenDownload = !hasImportedImage && manifestAcquisition is ImageAcquisitionPlan.Direct
    val showOpenTorrent = !hasImportedImage && hasTorrentDeclaration && manifest.source?.acquire != null
    val saveAsIsoPreferred = manifest.prefersAssembledIsoSave(importedImage)
    val saveAssembledImageLauncher = rememberLauncherForActivityResult(
        AssembledImageDocumentCreator(),
    ) { uri ->
        val request = pendingAssembledImageSave
        pendingAssembledImageSave = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult
        savingAssembledImage = true
        onWriteError(null)
        val startingText = context.getString(if (request.saveAsIso) R.string.preparing_assembled_iso else R.string.preparing_assembled_image)
        progress = 0f
        progressText = startingText
        onWriteProgress(0f, startingText)
        scope.launch {
            try {
                saveAssembledImage(
                    context = context,
                    request = request,
                    destination = uri,
                    onProgress = { assembledProgress ->
                        val fraction = assembledProgress.fraction ?: 0f
                        progress = fraction
                        progressText = assembledProgress.message
                        onWriteProgress(fraction, assembledProgress.message)
                    },
                )
                val complete = context.getString(if (request.saveAsIso) R.string.assembled_iso_saved else R.string.assembled_image_saved)
                progress = 1f
                progressText = complete
                onWriteProgress(1f, complete)
                onStatus(complete)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e("SparkWriter", "Assembled image save failed", error)
                val failure = context.getString(R.string.save_failed, error.visibleWriteFailureMessage())
                progress = 1f
                progressText = failure
                onWriteProgress(1f, failure)
                onStatus(failure)
                onWriteError(failure)
            } finally {
                savingAssembledImage = false
            }
        }
    }

    fun refreshUsbTargets() {
        if (writeActive) {
            onStatus(context.getString(R.string.usb_target_scan_paused))
            return
        }
        scope.launch {
            onStatus(context.getString(R.string.scanning_usb_targets))
            runCatching {
                usbWriter.listDevices().map { device ->
                    runCatching { usbWriter.probe(device.id).let { ProbedUsbDevice(it.device, it.permissionGranted) } }
                        .getOrNull()
                        ?: ProbedUsbDevice(device, false)
                }
            }
                .onSuccess { refreshed ->
                    val refreshedDevices = refreshed.map { it.device }
                    devicePermissionById = refreshed.associate { it.device.id to it.permissionGranted }
                    devices = refreshedDevices
                    selectedDeviceId = selectedDeviceId?.takeIf { id -> refreshedDevices.any { it.id == id } }
                        ?: refreshedDevices.firstOrNull()?.id
                    confirmedDeviceId = confirmedDeviceId?.takeIf { id -> refreshedDevices.any { it.id == id } }
                    onStatus(context.getString(if (refreshedDevices.isEmpty()) R.string.no_usb_target else R.string.usb_targets_refreshed))
                }
                .onFailure { onStatus(context.getString(R.string.usb_scan_failed, it.message.orEmpty())) }
        }
    }

    LaunchedEffect(manifest.metadata.id) {
        manifest.configFields.forEach { field ->
            applyDetectedDefaultsToField(context, values, field)
        }
        manifest.configFields.forEach { values.putIfAbsent(it.formFieldId(), it.default) }
    }
    LaunchedEffect(writeActive) {
        if (!writeActive) {
            refreshUsbTargets()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, writeActive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !writeActive) {
                refreshUsbTargets()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context, writeActive) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshUsbTargets()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val activeConfigFields = manifest.activeConfigFields(values)
    val displayConfigFields = activeConfigFields.filterNot { it.hidden }
    val configPages = manifest.displayWizardPages(displayConfigFields)
    val hasEditableConfigFields = displayConfigFields.any(ConfigField::isWizardEditable)
    val hasReviewStep = manifest.requiresReviewStep(displayConfigFields)
    val selectedDevicePermissionGranted = selectedDeviceId?.let { devicePermissionById[it] } == true
    val selectedDevice = selectedDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
    val selectedDeviceHasKnownSize = selectedDevice?.sizeBytes?.let { it > 0L } == true
    val totalConfigSteps = configPages.size + if (hasReviewStep) 1 else 0
    val reviewStepIndex = if (hasReviewStep) configPages.size else -1
    val onFinalConfigStep = !hasReviewStep || configPageIndex == reviewStepIndex
    val currentConfigPage = configPages.getOrNull(configPageIndex)
        ?.takeIf { configPageIndex < configPages.size }
    val currentPageFields = currentConfigPage?.fields.orEmpty()
    val primaryPageFields = currentPageFields.filter { !it.advanced || it.whenCondition != null }
    val advancedPageFields = currentPageFields.filter { it.advanced && it.whenCondition == null }
    val currentPageComplete = currentPageFields.all { field -> field.isCompleteFor(values.valueFor(field)) }
    val requiredConfigComplete = activeConfigFields.all { field -> field.isCompleteFor(values.valueFor(field)) }
    val startWriteEnabled = !writeActive &&
        confirmedDeviceId == selectedDeviceId && selectedDeviceId != null &&
        selectedDeviceId != null &&
        importedImage != null &&
        manifest.isAndroidRunnable &&
        localUnavailableReasons.isEmpty() &&
        requiredConfigComplete &&
        onFinalConfigStep
    val saveAssembledImageEnabled = !writeActive &&
        !savingAssembledImage &&
        importedImage != null &&
        manifest.outputs.iso &&
        manifest.isAndroidRunnable &&
        localUnavailableReasons.isEmpty() &&
        requiredConfigComplete &&
        onFinalConfigStep
    LaunchedEffect(manifest.metadata.id, onFinalConfigStep) {
        if (onFinalConfigStep) {
            // Without a review step this runs as soon as the pane opens, so keep
            // credentials from an earlier write visible instead of replacing them.
            if (hasReviewStep || generatedSecrets.isEmpty()) {
                val secrets = manifest.generateUserSecrets(manifest.activeConfigValues(values))
                pendingSecrets = secrets
                onGeneratedSecrets(secrets.toDisplayMap())
            }
        } else if (pendingSecrets != null) {
            // Left the review step before writing: these were never used.
            pendingSecrets = null
            onGeneratedSecrets(emptyMap())
        }
    }
    fun takePendingSecrets(activeValues: Map<String, String>): List<GeneratedUserSecret> {
        val secrets = pendingSecrets ?: manifest.generateUserSecrets(activeValues)
        pendingSecrets = null
        return secrets
    }
    var advancedExpanded by rememberSaveable(manifest.metadata.id, currentConfigPage?.id) { mutableStateOf(false) }
    val pageHeadingFocusRequester = remember(manifest.metadata.id, configPageIndex) { FocusRequester() }
    var focusPageHeading by remember(manifest.metadata.id) { mutableStateOf(false) }
    val profileFillValues = remember(manifest.metadata.id) { mutableStateMapOf<String, String>() }
    val fetchingFields = remember(manifest.metadata.id) { mutableStateMapOf<String, Boolean>() }
    val prefillProvenance = remember(manifest.metadata.id) { mutableStateMapOf<String, PrefillProvenance>() }
    val prefillReadOnlyFields = remember(manifest.metadata.id) { mutableStateMapOf<String, Boolean>() }
    val fetchingPrefillButtons = remember(manifest.metadata.id) { mutableStateMapOf<String, Boolean>() }

    // Runs after the defaults effect above (effects launch in composition order), so a
    // draft value replaces a default. Profile suggestions stay suggestions.
    LaunchedEffect(manifest.metadata.id, draft?.nonce) {
        val applied = draft?.takeIf { it.manifestId == manifest.metadata.id } ?: return@LaunchedEffect
        val draftProvenance = PrefillProvenance(
            kind = PrefillSourceKind.Draft,
            id = "draft",
            label = context.getString(R.string.draft_source_label),
        )
        applied.values.forEach { (formFieldId, value) ->
            values[formFieldId] = value
            prefillProvenance[formFieldId] = draftProvenance
            prefillReadOnlyFields.remove(formFieldId)
        }
        configPageIndex = manifest.draftLandingStep(values)
        focusPageHeading = true
    }
    LaunchedEffect(totalConfigSteps) {
        if (configPageIndex >= totalConfigSteps) {
            configPageIndex = (totalConfigSteps - 1).coerceAtLeast(0)
        }
    }
    LaunchedEffect(configPageIndex, focusPageHeading) {
        if (focusPageHeading) {
            pageHeadingFocusRequester.requestFocus()
            focusPageHeading = false
        }
    }
    LaunchedEffect(manifest.metadata.id) {
        val profileFields = manifest.configFields.profileParticipatingFields()
        val storedValues = profileStore.loadAll(profileFields.mapNotNull { it.standardField })
        profileFillValues.clear()
        profileFields.forEach { field ->
            val standardField = field.standardField ?: return@forEach
            val storedValue = storedValues[standardField].orEmpty()
            if (storedValue.isNotBlank() && values.valueFor(field) != storedValue) {
                profileFillValues[field.id] = storedValue
            }
        }
    }

    suspend fun saveProfileFields(fields: List<ConfigField>) {
        val profileValues = fields.profileValues(values)
        if (profileValues.isNotEmpty()) {
            profileStore.saveAll(profileValues)
        }
    }

    fun applyPrefillValues(
        source: PrefillProvenance,
        fieldValues: Map<String, String>,
        immutableFields: Collection<String>,
    ) {
        val fieldsById = activeConfigFields.associateBy { it.id }
        fieldValues.forEach { (fieldId, prefillValue) ->
            val field = fieldsById[fieldId]?.takeUnless { it.hidden } ?: return@forEach
            values.setFieldValue(field, prefillValue)
            prefillProvenance[field.formFieldId()] = source
            if (fieldId in immutableFields) {
                prefillReadOnlyFields[field.formFieldId()] = true
            } else {
                prefillReadOnlyFields.remove(field.formFieldId())
            }
        }
    }

    val currentPageHostProfiles = currentPageFields.prefillHostProfiles(manifest.form.hostProfiles)
    val currentPagePrefillButtons = if (ExternalServiceGate.enabled) {
        currentPageFields.prefillButtons(manifest.form.buttons, values)
    } else {
        emptyList()
    }
    if (showSourceDetails) {
        SourceDetailsDialog(
            manifest = manifest,
            importedImage = importedImage,
            downloadUrl = manifestAcquisition?.downloadUrl,
            onDismiss = { showSourceDetails = false },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = manifest.metadata.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                IconButton(
                    onClick = { showSourceDetails = true }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.source_details),
                    )
                }
            }

            
            if (manifestInstallInProgress) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator()
            }
            if (importedImage != null) {
                SelectionContainer {
                    Text(stringResource(R.string.local_iso_selected, importedImage.sizeBytes.toHumanBytes()))
                }
            } else if (!manifest.isAndroidRunnable) {
                SelectionContainer {
                    Text(stringResource(R.string.unavailable_on_android, manifest.unsupportedReasons.joinToString()))
                }
            } else if (localUnavailableReasons.isNotEmpty()) {
                SelectionContainer {
                    Text(stringResource(R.string.unavailable_on_android, localUnavailableReasons.joinToString()))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    if (importedImage == null) {
                        Text(
                            if (manifest.source?.userSupplied == true) {
                                stringResource(R.string.pick_source_iso)
                            } else {
                                stringResource(R.string.download_then_pick_iso)
                            },
                        )
                    } else {
                        Text(stringResource(R.string.iso_selected), fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(stringResource(R.string.sha256_value, importedImage.sha256))
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showOpenDownload) {
                            Button(
                                onClick = { onStatus(openDownloadHandler(context, manifest)) },
                                enabled = !writeActive,
                            ) {
                                Text(stringResource(R.string.open_download))
                            }
                        }
                        Button(onClick = onPickImage, enabled = !writeActive) {
                            Text(stringResource(R.string.pick_iso))
                        }
                        if (showOpenTorrent) {
                            Button(
                                onClick = {
                                    onStatus(openTorrentHandler(context, manifest))
                                },
                                enabled = !writeActive,
                            ) {
                                Text(stringResource(R.string.open_torrent))
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentConfigPage == null) {
                        val hasReturnDeliveryReview = manifest.hostEvents.any { it.returnDelivery != null }
                        val reviewTitle = when {
                                hasReturnDeliveryReview || hasEditableConfigFields -> stringResource(R.string.review)
                                displayConfigFields.isEmpty() -> stringResource(R.string.no_configuration_required)
                                else -> stringResource(R.string.no_configuration_required)
                            }
                        val reviewStepDescription = if (totalConfigSteps > 0) {
                            stringResource(R.string.step_of, reviewTitle, configPageIndex + 1, totalConfigSteps)
                        } else {
                            reviewTitle
                        }
                        Text(
                            reviewTitle,
                            modifier = Modifier
                                .focusRequester(pageHeadingFocusRequester)
                                .semantics {
                                    heading()
                                    contentDescription = reviewStepDescription
                                }
                                .focusable(),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (displayConfigFields.isNotEmpty() || hasReturnDeliveryReview) {
                            ReviewSummary(
                                fields = displayConfigFields,
                                values = values,
                                provenance = prefillProvenance,
                                hostEvents = manifest.hostEvents,
                            )
                            if (totalConfigSteps > 1) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            focusPageHeading = true
                                            configPageIndex = (configPageIndex - 1).coerceAtLeast(0)
                                        },
                                        enabled = configPageIndex > 0 && !writeActive,
                                    ) {
                                        Text(stringResource(R.string.back))
                                    }
                                }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.configuration), fontWeight = FontWeight.SemiBold)
                        val pageStepDescription = stringResource(
                            R.string.step_of,
                            currentConfigPage.title,
                            configPageIndex + 1,
                            totalConfigSteps,
                        )
                        Text(
                            currentConfigPage.title,
                            modifier = Modifier
                                .focusRequester(pageHeadingFocusRequester)
                                .semantics {
                                    heading()
                                    contentDescription = pageStepDescription
                                }
                                .focusable(),
                            fontWeight = FontWeight.SemiBold,
                        )
                        currentConfigPage.description?.ifBlank { null }?.let { Text(it) }
                        if (currentPageHostProfiles.isNotEmpty()) {
                            Text(stringResource(R.string.host_profiles), fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                currentPageHostProfiles.forEach { profile ->
                                    Button(
                                        onClick = {
                                            applyPrefillValues(
                                                source = PrefillProvenance(
                                                    kind = PrefillSourceKind.HostProfile,
                                                    id = profile.id,
                                                    label = profile.label,
                                                ),
                                                fieldValues = profile.fields.filterKeys { fieldId ->
                                                    currentPageFields.any { it.id == fieldId }
                                                },
                                                immutableFields = profile.immutableFields,
                                            )
                                            onStatus("Applied host profile ${profile.label}")
                                        },
                                        enabled = !writeActive,
                                    ) {
                                        Text(profile.label)
                                    }
                                }
                            }
                        }
                        if (currentPagePrefillButtons.isNotEmpty()) {
                            Text(stringResource(R.string.prefill_services), fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                currentPagePrefillButtons.forEach { button ->
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                fetchingPrefillButtons[button.id] = true
                                                onStatus("Fetching ${button.label}")
                                                runCatching { fetchRemotePrefillValues(button) }
                                                    .onSuccess { fetched ->
                                                        applyPrefillValues(
                                                            source = PrefillProvenance(
                                                                kind = PrefillSourceKind.RemoteButton,
                                                                id = button.id,
                                                                label = button.label,
                                                            ),
                                                            fieldValues = fetched.filterKeys { fieldId ->
                                                                currentPageFields.any { it.id == fieldId }
                                                            },
                                                            immutableFields = button.immutableFields,
                                                        )
                                                        onStatus("Applied ${button.label}")
                                                    }
                                                    .onFailure {
                                                        onStatus("Prefill failed for ${button.label}: ${it.message}")
                                                    }
                                                fetchingPrefillButtons[button.id] = false
                                            }
                                        },
                                        enabled = !writeActive && fetchingPrefillButtons[button.id] != true,
                                    ) {
                                        Text(if (fetchingPrefillButtons[button.id] == true) stringResource(R.string.fetching) else button.label)
                                    }
                                }
                            }
                        }
                        if (profileFillValues.isNotEmpty()) {
                            Button(
                                onClick = {
                                    profileFillValues.forEach { (fieldId, storedValue) ->
                                        manifest.configFields
                                            .firstOrNull { it.id == fieldId }
                                            ?.let { values.setFieldValue(it, storedValue) }
                                    }
                                    profileFillValues.clear()
                                    onStatus("Applied saved profile values")
                                },
                                enabled = !writeActive,
                            ) {
                                Text(stringResource(R.string.use_saved_profile_values))
                            }
                        }
                        primaryPageFields.forEach { field ->
                            ConfigFieldEditor(
                                field = field,
                                value = values.valueFor(field),
                                onValueChange = {
                                    values.setFieldValue(field, it)
                                    prefillProvenance.remove(field.formFieldId())
                                    prefillReadOnlyFields.remove(field.formFieldId())
                                },
                                provenance = prefillProvenance[field.formFieldId()],
                                readOnly = prefillReadOnlyFields[field.formFieldId()] == true,
                                fetching = fetchingFields[field.id] == true,
                                fetchEnabled = !writeActive,
                                onFetch = {
                                    scope.launch {
                                        fetchingFields[field.id] = true
                                        onStatus("Fetching ${field.label}")
                                        runCatching { fetchConfigFieldText(field.fetch ?: error("Field has no fetch source")) }
                                            .onSuccess { fetched ->
                                                values.setFieldValue(field, fetched)
                                                onStatus("Fetched ${field.label}")
                                            }
                                            .onFailure {
                                                onStatus("Fetch failed for ${field.label}: ${it.message}")
                                            }
                                        fetchingFields[field.id] = false
                                    }
                                },
                            )
                        }
                        if (advancedPageFields.isNotEmpty()) {
                            Button(
                                onClick = { advancedExpanded = !advancedExpanded },
                                enabled = !writeActive,
                            ) {
                                Text(if (advancedExpanded) stringResource(R.string.hide_advanced) else stringResource(R.string.advanced))
                            }
                            if (advancedExpanded) {
                                advancedPageFields.forEach { field ->
                                    ConfigFieldEditor(
                                        field = field,
                                        value = values.valueFor(field),
                                        onValueChange = {
                                            values.setFieldValue(field, it)
                                            prefillProvenance.remove(field.formFieldId())
                                            prefillReadOnlyFields.remove(field.formFieldId())
                                        },
                                        provenance = prefillProvenance[field.formFieldId()],
                                        readOnly = prefillReadOnlyFields[field.formFieldId()] == true,
                                        fetching = fetchingFields[field.id] == true,
                                        fetchEnabled = !writeActive,
                                        onFetch = {
                                            scope.launch {
                                                fetchingFields[field.id] = true
                                                onStatus("Fetching ${field.label}")
                                                runCatching { fetchConfigFieldText(field.fetch ?: error("Field has no fetch source")) }
                                                    .onSuccess { fetched ->
                                                        values.setFieldValue(field, fetched)
                                                        onStatus("Fetched ${field.label}")
                                                    }
                                                    .onFailure {
                                                        onStatus("Fetch failed for ${field.label}: ${it.message}")
                                                    }
                                                fetchingFields[field.id] = false
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        if (totalConfigSteps > 1) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        focusPageHeading = true
                                        configPageIndex = (configPageIndex - 1).coerceAtLeast(0)
                                    },
                                    enabled = configPageIndex > 0 && !writeActive,
                                ) {
                                    Text(stringResource(R.string.back))
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            saveProfileFields(currentPageFields)
                                            focusPageHeading = true
                                            configPageIndex = (configPageIndex + 1).coerceAtMost(totalConfigSteps - 1)
                                        }
                                    },
                                    enabled = configPageIndex < totalConfigSteps - 1 &&
                                        currentPageComplete &&
                                        !writeActive,
                                ) {
                                    Text(if (configPageIndex == reviewStepIndex - 1) stringResource(R.string.review) else stringResource(R.string.continue_label))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            if (generatedSecrets.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.generated_credentials), fontWeight = FontWeight.SemiBold)
                        generatedSecrets.forEach { (key, value) ->
                            GeneratedSecretRow(
                                label = key,
                                value = value,
                                onCopy = {
                                    scope.launch { clipboard.copyText(context, value) }
                                    onStatus("Copied ${key}")
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.usb_target), fontWeight = FontWeight.SemiBold)
                    if (devices.isEmpty()) {
                        Text(stringResource(R.string.device_none))
                    }
                    devices.forEach { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = selectedDeviceId == device.id,
                                onClick = {
                                    if (!writeActive) {
                                        selectedDeviceId = device.id
                                        confirmedDeviceId = null
                                    }
                                },
                                enabled = !writeActive,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(device.displayName, fontWeight = FontWeight.SemiBold)
                                Text(device.sizeHuman)
                            }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (selectedDevice == null || !selectedDeviceHasKnownSize) {
                            Button(onClick = { refreshUsbTargets() }, enabled = !writeActive) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                        if (selectedDeviceId != null && !selectedDevicePermissionGranted) {
                            Button(
                                enabled = selectedDeviceId != null && !writeActive,
                                onClick = {
                                    val deviceId = selectedDeviceId ?: return@Button
                                    scope.launch {
                                        when (usbWriter.requestPermission(deviceId)) {
                                            PermissionResult.Granted -> {
                                                onStatus(context.getString(R.string.usb_permission_already_granted))
                                                refreshUsbTargets()
                                            }
                                            PermissionResult.Denied -> onStatus(context.getString(R.string.usb_permission_denied))
                                            is PermissionResult.NeedsUserAction -> onStatus(context.getString(R.string.usb_permission_requested_short))
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.allow_usb))
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = confirmedDeviceId == selectedDeviceId && selectedDeviceId != null,
                            onCheckedChange = { checked -> confirmedDeviceId = if (checked) selectedDeviceId else null },
                            enabled = selectedDeviceId != null && !writeActive,
                        )
                        Text(
                            stringResource(R.string.erase_selected_usb_confirmation),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = verifyBaseImage,
                            onCheckedChange = { verifyBaseImage = it },
                            enabled = !writeActive,
                        )
                        Text(stringResource(R.string.verify_base_image_after_write), modifier = Modifier.weight(1f))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = startWriteEnabled,
                            onClick = {
                                val deviceId = selectedDeviceId ?: return@Button
                                val selectedImage = importedImage ?: run {
                                    onStatus(context.getString(if (manifest.source?.userSupplied == true) R.string.pick_source_iso_before_writing else R.string.pick_downloaded_iso_before_writing))
                                    return@Button
                                }

                                writing = true
                                onWriteError(null)
                                onWritingChange(true)
                                progress = 0f
                                val preparingText = context.getString(R.string.preparing_usb_write)
                                progressText = preparingText
                                onWriteProgress(0f, preparingText)
                                appScope.launch {
                                    try {
                                        when (usbWriter.requestPermission(deviceId)) {
                                            PermissionResult.Granted -> Unit
                                            PermissionResult.Denied -> {
                                                onStatus(context.getString(R.string.usb_permission_denied))
                                                return@launch
                                            }
                                            is PermissionResult.NeedsUserAction -> {
                                                onStatus(context.getString(R.string.usb_permission_requested))
                                                return@launch
                                            }
                                        }

                                        val probed = usbWriter.probe(deviceId)
                                        devices = devices.map { if (it.id == probed.device.id) probed.device else it }
                                        // Probed right after the user confirmed: the worker refuses to
                                        // write unless the device it claims still has this identity.
                                        val confirmedDevice = probed.fingerprint ?: run {
                                            onStatus(context.getString(R.string.usb_permission_denied))
                                            return@launch
                                        }
                                        onStatus(context.getString(R.string.preparing_local_image))

                                        val image = selectedImage.also { manifest.requireDeclaredImageHash(it) }
                                        saveProfileFields(activeConfigFields)
                                        val activeValues = manifest.activeConfigValues(values)
                                        val preparation = manifest.buildLocalDeliveryPreparation(
                                            activeValues,
                                            image,
                                            presetSecrets = takePendingSecrets(activeValues),
                                        )
                                        onGeneratedSecrets(preparation.userSecrets.toDisplayMap())
                                        val deliveryPlan = preparation.deliveryPlan
                                        val auxiliaryData = (deliveryPlan as? LocalDeliveryPlan.Auxiliary)?.plan
                                        val fixedByteRanges = (deliveryPlan as? LocalDeliveryPlan.FixedByteRanges)?.plan
                                        val bootPatchTemplate = prepareBootPatchTemplate(
                                            context = context,
                                            isoIndexStore = isoIndexStore,
                                            manifest = manifest,
                                            image = image,
                                            bootPatch = preparation.bootPatchPlan,
                                        )
                                        if (manifest.isPatchableImportSparkPlug()) {
                                            withContext(Dispatchers.IO) {
                                                isoIndexStore.learn(manifest, image)
                                            }
                                        }
                                        logDeliveryPlan(deliveryPlan)
                                        val writeIntent = WriteIntent(
                                            deviceId = deviceId,
                                            confirmedDeviceId = deviceId,
                                            confirmedDevice = confirmedDevice,
                                            image = image,
                                            verifyAfter = verifyBaseImage,
                                            auxiliaryData = auxiliaryData,
                                            fixedByteRanges = fixedByteRanges,
                                            bootPatch = preparation.bootPatchPlan,
                                            bootPatchTemplate = bootPatchTemplate,
                                            returnPlan = preparation.returnPlan,
                                            tags = manifest.writeTags(),
                                        )
                                        onStatus(context.getString(R.string.writing_image_to_usb, image.displayName))
                                        val terminalInfo = writeUsbWithWorkManager(
                                            context = context,
                                            deviceId = deviceId,
                                            confirmedDevice = confirmedDevice,
                                            image = image,
                                            verifyAfter = verifyBaseImage,
                                            auxiliaryData = auxiliaryData,
                                            fixedByteRanges = fixedByteRanges,
                                            bootPatch = preparation.bootPatchPlan,
                                            bootPatchTemplate = bootPatchTemplate,
                                            returnPlan = preparation.returnPlan,
                                            tags = writeIntent.tags,
                                            onProgress = { percent, text ->
                                                progress = percent
                                                progressText = text
                                                onWriteProgress(percent, text)
                                            },
                                        )

                                        when (terminalInfo.state) {
                                            WorkInfo.State.SUCCEEDED -> {
                                                progress = 1f
                                                val statusText = terminalInfo.outputData.getString(UsbWriteData.KEY_MESSAGE)
                                                    ?: context.getString(R.string.usb_write_complete)
                                                progressText = statusText
                                                onWriteProgress(1f, statusText)
                                                onStatus(context.getString(R.string.usb_write_complete))
                                            }
                                            WorkInfo.State.FAILED -> {
                                                val message = terminalInfo.usbWriteFailureMessage(progressText.orEmpty())
                                                val failure = context.getString(R.string.write_failed_detail, message)
                                                progressText = failure
                                                onWriteProgress(1f, failure)
                                                onStatus(failure)
                                                onWriteError(failure)
                                            }
                                            WorkInfo.State.CANCELLED -> {
                                                val statusText = context.getString(R.string.usb_write_cancelled)
                                                progressText = statusText
                                                onWriteProgress(1f, statusText)
                                                onStatus(context.getString(R.string.usb_write_cancelled))
                                            }
                                            else -> {
                                                val failure = context.getString(R.string.usb_write_ended_unexpectedly, terminalInfo.state)
                                                progressText = failure
                                                onWriteProgress(1f, failure)
                                                onStatus(failure)
                                                onWriteError(failure)
                                            }
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        Log.e("SparkWriter", "USB write flow failed", error)
                                        val message = error.visibleWriteFailureMessage()
                                        val failure = context.getString(R.string.write_failed_detail, message)
                                        progressText = failure
                                        onWriteProgress(1f, failure)
                                        onStatus(failure)
                                        onWriteError(failure)
                                    } finally {
                                        writing = false
                                        onWritingChange(false)
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.start_write))
                        }
                        Button(
                            enabled = saveAssembledImageEnabled,
                            onClick = {
                                val selectedImage = importedImage ?: run {
                                    onStatus(if (manifest.source?.userSupplied == true) "Pick the source ISO before saving" else "Pick the downloaded ISO before saving")
                                    return@Button
                                }
                                scope.launch {
                                    try {
                                        onWriteError(null)
                                        onStatus(if (saveAsIsoPreferred) "Preparing assembled ISO" else "Preparing assembled image")
                                        val image = selectedImage.also { manifest.requireDeclaredImageHash(it) }
                                        saveProfileFields(activeConfigFields)
                                        val activeValues = manifest.activeConfigValues(values)
                                        val preparation = manifest.buildLocalDeliveryPreparation(
                                            activeValues,
                                            image,
                                            presetSecrets = takePendingSecrets(activeValues),
                                        )
                                        onGeneratedSecrets(preparation.userSecrets.toDisplayMap())
                                        val deliveryPlan = preparation.deliveryPlan
                                        val saveAsIso = manifest.prefersAssembledIsoSave(image) &&
                                            deliveryPlan !is LocalDeliveryPlan.Auxiliary
                                        logDeliveryPlan(deliveryPlan)
                                        pendingAssembledImageSave = PendingAssembledImageSave(
                                            image = image,
                                            auxiliaryData = (deliveryPlan as? LocalDeliveryPlan.Auxiliary)?.plan,
                                            fixedByteRanges = (deliveryPlan as? LocalDeliveryPlan.FixedByteRanges)?.plan,
                                            bootPatch = preparation.bootPatchPlan,
                                            returnPlan = preparation.returnPlan,
                                            saveAsIso = saveAsIso,
                                            tags = manifest.writeTags(),
                                        )
                                        saveAssembledImageLauncher.launch(
                                            manifest.assembledImageSaveTarget(
                                                image = image,
                                                saveAsIso = saveAsIso,
                                            ),
                                        )
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        Log.e("SparkWriter", "Assembled image preparation failed", error)
                                        val failure = context.getString(R.string.save_failed, error.visibleWriteFailureMessage())
                                        onStatus(failure)
                                        onWriteError(failure)
                                    }
                                }
                            },
                        ) {
                            Text(
                                when {
                                    savingAssembledImage -> stringResource(R.string.saving_ellipsis)
                                    saveAsIsoPreferred -> stringResource(R.string.save_assembled_iso)
                                    else -> stringResource(R.string.save_assembled_image)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun ConfigField.requiredLabel(): String = label + if (required) " *" else ""
internal fun ConfigField.isUserEditable(): Boolean = !disabled && !immutable
private fun ConfigField.isWizardEditable(): Boolean = isUserEditable() && type != "info"

internal enum class PrefillSourceKind { HostProfile, RemoteButton, Draft }

internal data class PrefillProvenance(
    val kind: PrefillSourceKind,
    val id: String,
    val label: String,
)

internal fun List<ConfigField>.prefillHostProfiles(hostProfiles: List<HostProfile>): List<HostProfile> {
    val pageFieldIds = map { it.id }.toSet()
    return hostProfiles.filter { profile -> profile.fields.keys.any { it in pageFieldIds } }
}

internal fun List<ConfigField>.prefillButtons(
    buttons: List<FormPrefillButton>,
    values: Map<String, String>,
): List<FormPrefillButton> {
    val pageFieldIds = map { it.id }.toSet()
    return buttons.filter { button ->
        button.whenCondition.matches(values) && button.fieldBindings.keys.any { it in pageFieldIds }
    }
}

internal data class ConfigPage(
    val id: String,
    val title: String,
    val description: String?,
    val fields: List<ConfigField>,
)

internal fun SparkManifest.displayWizardPages(visibleFields: List<ConfigField>): List<ConfigPage> {
    val visibleById = visibleFields.associateBy { it.id }
    val pages = wizardPages.mapNotNull { page ->
        val pageFields = page.fields.mapNotNull(visibleById::get)
        if (pageFields.none(ConfigField::isWizardEditable)) {
            null
        } else {
            ConfigPage(
                id = page.id,
                title = page.title,
                description = page.description,
                fields = pageFields,
            )
        }
    }
    return pages
}

@Composable
private fun ReviewSummary(
    fields: List<ConfigField>,
    values: Map<String, String>,
    provenance: Map<String, PrefillProvenance> = emptyMap(),
    hostEvents: List<HostEventSubscription>,
) {
    val summaryFields = fields.filterNot { it.hidden }
    val deliveryEvents = hostEvents.filter { it.returnDelivery != null }
    if (summaryFields.isEmpty() && deliveryEvents.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        summaryFields.forEach { field ->
            val fieldProvenance = provenance[field.formFieldId()]
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                Text(field.label, fontWeight = FontWeight.SemiBold)
                ReviewFieldValue(field, values.valueFor(field))
                fieldProvenance?.let {
                    Text(stringResource(R.string.prefilled_from, it.label), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        deliveryEvents.forEach { subscription ->
            val delivery = subscription.returnDelivery ?: return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.return_endpoint), fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(
                        listOf(
                            stringResource(R.string.event_value, subscription.event),
                            stringResource(R.string.method_value, delivery.method),
                            stringResource(R.string.url_value, delivery.url),
                            stringResource(R.string.mode_value, delivery.deliveryMode),
                            delivery.deliveryModeExplanation(subscription.event),
                            stringResource(R.string.body_value, delivery.reviewBodySource()),
                            stringResource(R.string.media_type_value, delivery.mediaType ?: "application/json"),
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun net.metalstrapper.sparkwriter.core.ReturnDeliverySpec.deliveryModeExplanation(event: String): String {
    return when (deliveryMode.lowercase(Locale.ROOT)) {
        "required" -> stringResource(
            if (event == "write_ready") R.string.return_required_write_ready
            else R.string.return_required_write_complete,
        )
        else -> stringResource(R.string.return_best_effort)
    }
}

@Composable
private fun ReviewFieldValue(
    field: ConfigField,
    value: String,
) {
    val notSet = stringResource(R.string.not_set)
    val summaryValue = field.summaryValue(value).ifBlank { notSet }
    if (!field.isReviewSecret() || summaryValue == notSet) {
        SelectionContainer {
            Text(summaryValue, style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    var visible by rememberSaveable(field.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                if (visible) summaryValue else summaryValue.maskSecret(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { visible = !visible }) {
            Text(if (visible) stringResource(R.string.hide) else stringResource(R.string.reveal))
        }
    }
}

internal fun SparkManifest.requiresReviewStep(displayFields: List<ConfigField>): Boolean {
    val hasEditableConfigFields = displayFields.any(ConfigField::isWizardEditable)
    val hasReturnDelivery = hostEvents.any { it.returnDelivery != null }
    return hasReturnDelivery || (hasEditableConfigFields && displayFields.isNotEmpty())
}

private fun net.metalstrapper.sparkwriter.core.ReturnDeliverySpec.reviewBodySource(): String {
    return when {
        from != null -> "artifact $from"
        contentVar != null -> "variable $contentVar"
        template != null -> "template $template"
        else -> "default event body"
    }
}

private fun ConfigField.summaryValue(value: String): String {
    return when (type) {
        "select" -> options.firstOrNull { it.value == value }?.label ?: value
        else -> value
    }
}

private fun ConfigField.isReviewSecret(): Boolean = type == "password"

private fun List<GeneratedUserSecret>.toDisplayMap(): Map<String, String> =
    associate { it.label to it.value }.toSortedMap().toMap()

private fun String.maskSecret(): String {
    return "*".repeat(length.coerceIn(8, 32))
}

internal val StandardProfileFields = setOf(
    "user.name",
    "user.username",
    "user.email",
    "user.ssh_public_keys",
    "network.suffix",
    "network.wifi.ssid",
    "apt.proxy",
    "locale.timezone",
    "locale.keyboard",
)

internal fun List<ConfigField>.profileParticipatingFields(): List<ConfigField> {
    return filter { it.profileKey() != null }
}

internal fun List<ConfigField>.profileValues(values: Map<String, String>): Map<String, String> {
    return mapNotNull { field ->
        val key = field.profileKey() ?: return@mapNotNull null
        val value = values.valueFor(field).orEmpty()
        if (value.isBlank()) null else key to value
    }.toMap()
}

internal fun ConfigField.profileKey(): String? {
    if (type == "password") return null
    val standardField = standardField?.ifBlank { null } ?: return null
    if (standardField !in StandardProfileFields) return null
    val persist = storage["persist"]?.equals("true", ignoreCase = true) == true
    val profileScoped = storage["scope"] == "profile"
    return standardField.takeIf { persist || profileScoped }
}
