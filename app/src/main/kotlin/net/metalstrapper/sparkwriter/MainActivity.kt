package net.metalstrapper.sparkwriter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.annotation.StringRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.manifest.ManifestValidationException
import net.metalstrapper.sparkwriter.storage.AppSettingsStore
import net.metalstrapper.sparkwriter.storage.InstalledManifestStore
import net.metalstrapper.sparkwriter.storage.IsoIndexStore
import net.metalstrapper.sparkwriter.storage.ProfileStore
import net.metalstrapper.sparkwriter.ui.theme.SparkWriterTheme
import net.metalstrapper.sparkwriter.usb.AndroidUsbHostBlockWriter
import net.metalstrapper.sparkwriter.usb.UsbBlockWriter
import java.net.URI
import java.io.File

class MainActivity : ComponentActivity() {
    private val runtime = ManifestRuntime(externalServicesEnabled = ExternalServiceGate.enabled)
    private val usbWriter by lazy { AndroidUsbHostBlockWriter(this) }
    private val pendingManifestInstall = mutableStateOf<ManifestInstallRequest?>(null)
    private val pendingImageImport = mutableStateOf<PendingImageImport?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        handleIncomingIntent(intent)
        setContent {
            SparkWriterApp(
                runtime = runtime,
                usbWriter = usbWriter,
                manifestInstallRequest = pendingManifestInstall.value,
                pendingImageImport = pendingImageImport.value,
            )
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val rawUri = intent?.dataString
        Log.d("SparkWriter", "Received intent action=${intent?.action} data=$rawUri")
        val manifestUrl = rawUri?.let(runtime::parseSparkUri)
        if (manifestUrl != null) {
            Log.d("SparkWriter", "Parsed Spark manifest URL: $manifestUrl")
            pendingManifestInstall.value = ManifestInstallRequest(manifestUrl, System.nanoTime())
            return
        }

        val imageUri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.extraStreamUri()
            else -> null
        }
        if (imageUri != null && !isAcceptableExternalImageUri(imageUri.scheme, imageUri.authority, packageName)) {
            Log.w("SparkWriter", "Rejected external image URI with scheme=${imageUri.scheme}")
        } else if (imageUri != null) {
            Log.d("SparkWriter", "Received local image URI: $imageUri")
            pendingImageImport.value = PendingImageImport(imageUri, System.nanoTime())
        } else if (rawUri != null) {
            Log.w("SparkWriter", "Ignored unrecognized URI: $rawUri")
        }
    }
}

/**
 * Images from other apps must arrive as granted content:// URIs. A file:// URI would be opened with
 * SparkWriter's own permissions, letting the sender point at app-private files it cannot read itself.
 * Our own provider authorities are rejected for the same reason.
 */
internal fun isAcceptableExternalImageUri(scheme: String?, authority: String?, ownPackage: String): Boolean {
    if (!scheme.equals("content", ignoreCase = true)) return false
    val host = authority?.substringAfterLast('@')?.lowercase() ?: return false
    val own = ownPackage.lowercase()
    return host != own && !host.startsWith("$own.")
}

private fun Intent.extraStreamUri(): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
}

data class PendingImageImport(
    val uri: Uri,
    val nonce: Long,
    val manifestId: String? = null,
)

data class ManifestInstallRequest(
    val manifestUrl: String,
    val nonce: Long,
)

private enum class ManifestImportPhase {
    Received,
    Checking,
    AwaitingConfirmation,
    AwaitingPackageConfirmation,
    Downloading,
    Reviewing,
    Installed,
    Failed,
}

private data class ManifestImportStatus(
    val phase: ManifestImportPhase,
    val manifestUrl: String,
    val manifestName: String? = null,
    val detail: String? = null,
    val pending: PendingSparkPlugImport? = null,
) {
    val inProgress: Boolean
        get() = phase == ManifestImportPhase.Received ||
            phase == ManifestImportPhase.Checking ||
            phase == ManifestImportPhase.Downloading
}

private enum class SparkWriterRoute(@StringRes val titleRes: Int) {
    Sources(R.string.nav_sources),
    Workflow(R.string.nav_workflow),
    Device(R.string.nav_device),
    Profile(R.string.nav_profile),
    Settings(R.string.nav_settings),
}

internal const val ISO_IMAGE_MEDIA_TYPE = "application/x-iso9660-image"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkWriterApp(
    runtime: ManifestRuntime,
    usbWriter: UsbBlockWriter,
    manifestInstallRequest: ManifestInstallRequest?,
    pendingImageImport: PendingImageImport?,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val baseImageStore = remember(context) { BaseImageStore(context.applicationContext.filesDir) }
    val isoIndexStore = remember(context) { IsoIndexStore(context.applicationContext) }
    val profileStore = remember(context) { ProfileStore(context.applicationContext) }
    val appSettingsStore = remember(context) { AppSettingsStore(context.applicationContext) }
    val localZipImportEnabled by appSettingsStore.localZipImportEnabled.collectAsState(initial = false)
    val bundledManifests = remember(context) { context.loadBundledManifests(runtime) }
    val rawImageManifest = remember { runtime.rawImageManifest() }
    var manifests by remember { mutableStateOf(bundledManifests) }
    val installedManifestStore = remember(context) { InstalledManifestStore(context.applicationContext) }
    var selectedManifestId by rememberSaveable { mutableStateOf<String?>(null) }
    val offeredManifests = (manifests + rawImageManifest).distinctBy { it.metadata.id }.filter { it.isOfferedForOwnSource() }
    val selectedManifest = offeredManifests.firstOrNull { it.metadata.id == selectedManifestId }
    var importedImage by remember { mutableStateOf<VerifiedImage?>(null) }
    var importedImageMatches by remember { mutableStateOf<Set<String>?>(null) }
    var pickerImageImport by remember { mutableStateOf<PendingImageImport?>(null) }
    var pickerManifestId by remember { mutableStateOf<String?>(null) }
    var writeInProgress by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(manifestInstallRequest?.manifestUrl?.let { context.getString(R.string.install_requested, it) } ?: context.getString(R.string.ready)) }
    var writeProgress by remember { mutableStateOf<Float?>(null) }
    var writeProgressText by remember { mutableStateOf<String?>(null) }
    var writeError by remember { mutableStateOf<String?>(null) }
    var writeTerminalText by remember { mutableStateOf<String?>(null) }
    var imageImportProgress by remember { mutableStateOf<LocalImageImportProgress?>(null) }
    var manifestImportStatus by remember { mutableStateOf<ManifestImportStatus?>(null) }
    var pendingSparkPlugImport by remember { mutableStateOf<PendingSparkPlugImport?>(null) }
    var handledManifestInstallNonce by rememberSaveable { mutableStateOf<Long?>(null) }
    var approvedManifestInstallNonce by remember { mutableStateOf<Long?>(null) }
    var generatedSecrets by remember { mutableStateOf(emptyMap<String, String>()) }
    val manifestInstallInProgress = manifestInstallRequest != null &&
        handledManifestInstallNonce != manifestInstallRequest.nonce
    val scope = rememberCoroutineScope()
    val routeScreenItems = listOf(
        SparkWriterRoute.Sources,
        SparkWriterRoute.Workflow,
        SparkWriterRoute.Device,
        SparkWriterRoute.Profile,
    )
    var currentRoute by rememberSaveable { mutableStateOf(SparkWriterRoute.Workflow) }
    var showNotificationPermissionExplanation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) showNotificationPermissionExplanation = true
    }
    val imagePicker = rememberLauncherForActivityResult(IsoDocumentPicker()) { picked ->
        val uri = picked?.uri
        if (uri != null) {
            status = context.getString(R.string.importing_iso)
            imageImportProgress = LocalImageImportProgress(phase = context.getString(R.string.starting_iso_import))
            runCatching {
                val persistableFlags = picked.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                if (persistableFlags != 0) {
                    context.contentResolver.takePersistableUriPermission(uri, persistableFlags)
                }
            }
            importedImage = null
            pickerImageImport = PendingImageImport(uri, System.nanoTime(), pickerManifestId)
            pickerManifestId = null
        }
    }
    val sparkPlugPicker = rememberLauncherForActivityResult(SparkPlugZipPicker()) { uri ->
        if (uri == null || !localZipImportEnabled) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "SparkPlug.zip"
        manifestImportStatus = ManifestImportStatus(ManifestImportPhase.Downloading, name, detail = "Inspecting selected ZIP")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("sparkplug-", ".zip", context.cacheDir)
                    try {
                        val transferred = context.contentResolver.openInputStream(uri)?.use { copyBoundedSparkPlug(it, temp) }
                            ?: throw ManifestValidationException("Unable to open selected ZIP")
                        inspectLocalSparkPlugZip(runtime, temp, name, transferred)
                    } finally {
                        temp.delete()
                    }
                }
            }.onSuccess { pending ->
                pendingSparkPlugImport = pending
                manifestImportStatus = ManifestImportStatus(
                    ManifestImportPhase.AwaitingPackageConfirmation, name, pending.manifest.metadata.name,
                    "Valid package: ${pending.declaredFileCount} declared files, ${pending.transferredBytes} bytes transferred, ${pending.expandedBytes} bytes expanded.", pending,
                )
            }.onFailure { error ->
                manifestImportStatus = ManifestImportStatus(ManifestImportPhase.Failed, name, detail = error.message)
            }
        }
    }

    suspend fun importAndRouteImage(request: PendingImageImport) {
        status = context.getString(R.string.importing_iso)
        imageImportProgress = LocalImageImportProgress(phase = context.getString(R.string.starting_iso_import))
        runCatching {
            importLocalImage(context, request.uri) { progress ->
                imageImportProgress = progress
                status = progress.visibleImportStatus()
            }.also { selectedManifest?.requireDeclaredImageHash(it) }
        }.onSuccess { image ->
            importedImage = image
            imageImportProgress = LocalImageImportProgress(
                phase = context.getString(R.string.matching_iso),
                bytesRead = image.sizeBytes,
                totalBytes = image.sizeBytes,
            )
            status = imageImportProgress?.visibleImportStatus().orEmpty()
            val focusedManifestId = request.manifestId
                ?.takeIf { id -> offeredManifests.any { it.metadata.id == id } }
            Log.d(
                "SparkWriter",
                "Imported image route requestedManifest=${request.manifestId} focusedManifest=$focusedManifestId",
            )
            if (focusedManifestId != null) {
                importedImageMatches = null
                selectedManifestId = focusedManifestId
            } else {
                val route = isoIndexStore.routeImportedImage(image, offeredManifests, rawImageManifest)
                val needsSparkPlugChoice = route.selectedManifestId == null
                importedImageMatches = route.visibleManifestIds
                selectedManifestId = route.selectedManifestId
                if (needsSparkPlugChoice) {
                    currentRoute = SparkWriterRoute.Sources
                    status = context.getString(R.string.choose_sparkplug, image.displayName)
                } else {
                    status = context.getString(R.string.imported_image, image.displayName)
                }
            }
            if (focusedManifestId != null) {
                status = context.getString(R.string.imported_image, image.displayName)
            }
            imageImportProgress = null
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.e("SparkWriter", "ISO import failed for ${request.uri}", error)
            status = context.getString(R.string.iso_import_failed, error.message ?: error::class.simpleName)
            imageImportProgress = null
        }
    }

    LaunchedEffect(pendingImageImport?.nonce) {
        val request = pendingImageImport ?: return@LaunchedEffect
        importAndRouteImage(request)
    }

    LaunchedEffect(pickerImageImport?.nonce) {
        val request = pickerImageImport ?: return@LaunchedEffect
        importAndRouteImage(request)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            File(context.filesDir, "datastore/spark_writer_mailroom_client.preferences_pb").delete()
        }
        profileStore.remove("network.wifi.password")
        val installed = runCatching {
            withContext(Dispatchers.IO) {
                // Stored manifests skip parse, so re-apply the release policy here.
                installedManifestStore.loadInstalled().map { it.withReleaseExternalServicePolicy() }
            }
        }.getOrElse { emptyList() }
        if (installed.isNotEmpty()) {
            manifests = (bundledManifests + installed + manifests)
                .distinctBy { it.metadata.id }
        }
        runCatching {
            withContext(Dispatchers.IO) {
                isoIndexStore.refreshDeclared((bundledManifests + installed + manifests).distinctBy { it.metadata.id })
            }
        }.onFailure { error ->
            Log.w("SparkWriter", "ISO index refresh failed", error)
        }
    }

    LaunchedEffect(selectedManifest?.metadata?.id) {
        if (selectedManifest?.metadata?.id == null) return@LaunchedEffect
        generatedSecrets = emptyMap()
        val manifest = selectedManifest ?: return@LaunchedEffect
        if (importedImage != null) return@LaunchedEffect
        val cachedImage = runCatching { baseImageStore.cachedVerifiedImage(manifest) }
            .getOrElse { error ->
                Log.w("SparkWriter", "Cached image lookup failed for ${manifest.metadata.id}", error)
                null
            }
        if (cachedImage != null) {
            importedImage = cachedImage
            status = context.getString(R.string.using_cached_image, cachedImage.displayName)
            return@LaunchedEffect
        }
    }

    LaunchedEffect(manifestInstallRequest, approvedManifestInstallNonce) {
        val request = manifestInstallRequest ?: return@LaunchedEffect
        if (handledManifestInstallNonce == request.nonce) return@LaunchedEffect
        val installUrl = request.manifestUrl
        manifestImportStatus = ManifestImportStatus(
            phase = ManifestImportPhase.Received,
            manifestUrl = installUrl,
        )
        if (approvedManifestInstallNonce != request.nonce) {
            status = context.getString(R.string.checking_manifest_source)
            manifestImportStatus = ManifestImportStatus(
                phase = ManifestImportPhase.Checking,
                manifestUrl = installUrl,
                detail = context.getString(R.string.manifest_source_validation),
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    preflightManifestSource(runtime, installUrl)
                }
            }.onSuccess { detail ->
                status = context.getString(R.string.manifest_download_needs_confirmation)
                manifestImportStatus = ManifestImportStatus(
                    phase = ManifestImportPhase.AwaitingConfirmation,
                    manifestUrl = installUrl,
                    detail = detail,
                )
            }.onFailure { error ->
                handledManifestInstallNonce = request.nonce
                val message = error.message ?: error::class.simpleName.orEmpty()
                status = context.getString(R.string.manifest_source_check_failed, message)
                manifestImportStatus = ManifestImportStatus(
                    phase = ManifestImportPhase.Failed,
                    manifestUrl = installUrl,
                    detail = message.ifBlank { "Manifest source check failed" },
                )
            }
            return@LaunchedEffect
        }
        status = context.getString(R.string.install_requested, installUrl)
        status = context.getString(R.string.downloading_manifest_package)
        manifestImportStatus = ManifestImportStatus(
            phase = ManifestImportPhase.Downloading,
            manifestUrl = installUrl,
            detail = context.getString(R.string.downloading_manifest_detail),
        )
        runCatching {
            withContext(Dispatchers.IO) {
                installManifestFromUrl(runtime, installUrl)
            }
        }.onSuccess { manifest ->
            val scripts = inspectableScripts(manifest)
            val pending = PendingSparkPlugImport(
                manifest, SparkPlugTrust.RemoteHttps, installUrl, 0, 0, 0, scripts, analyzeNotableBehavior(scripts),
            )
            pendingSparkPlugImport = pending
            status = context.getString(R.string.review_manifest, manifest.metadata.name)
            manifestImportStatus = ManifestImportStatus(
                phase = ManifestImportPhase.Reviewing,
                manifestUrl = installUrl,
                manifestName = manifest.metadata.name,
                detail = context.getString(R.string.review_manifest_detail),
                pending = pending,
            )
        }.onFailure { error ->
            Log.e("SparkWriter", "Spark install failed for $installUrl", error)
            handledManifestInstallNonce = request.nonce
            val message = error.message ?: error::class.simpleName.orEmpty()
            status = context.getString(R.string.install_failed, message)
            manifestImportStatus = ManifestImportStatus(
                phase = ManifestImportPhase.Failed,
                manifestUrl = installUrl,
                detail = message.ifBlank { "Manifest import failed" },
            )
        }
    }

    LaunchedEffect(Unit) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        while (true) {
            val activeWork = runCatching { activeUsbWriteWorkInfo(workManager) }.getOrNull()
            if (activeWork == null) {
                delay(1000)
                continue
            }

            writeInProgress = true
            writeError = null
            writeProgress = null
            status = context.getString(R.string.usb_write_in_progress)
            runCatching {
                pollUsbWriteWork(
                    workManager = workManager,
                    id = activeWork.id,
                    onProgress = { percent, text ->
                        writeProgress = percent
                        writeProgressText = text
                        writeTerminalText = if (percent >= 1f) text else null
                    },
                )
            }.onSuccess { terminalInfo ->
                when (terminalInfo.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val statusText = terminalInfo.outputData.getString(UsbWriteData.KEY_MESSAGE)
                            ?: context.getString(R.string.usb_write_complete)
                        status = context.getString(R.string.usb_write_complete)
                        writeProgress = 1f
                        writeProgressText = statusText
                        writeTerminalText = statusText
                        status = statusText
                    }
                    WorkInfo.State.FAILED -> {
                        val failure = context.getString(R.string.write_failed_detail, terminalInfo.usbWriteFailureMessage(writeProgressText))
                        status = failure
                        writeProgress = 1f
                        writeProgressText = context.getString(R.string.write_failed)
                        writeTerminalText = failure
                        writeError = failure
                    }
                    WorkInfo.State.CANCELLED -> {
                        status = context.getString(R.string.usb_write_cancelled)
                        writeProgress = 1f
                        writeProgressText = context.getString(R.string.usb_write_cancelled)
                        writeTerminalText = context.getString(R.string.usb_write_cancelled)
                    }
                    else -> {
                        val failure = context.getString(R.string.usb_write_ended_unexpectedly, terminalInfo.state)
                        status = failure
                        writeProgress = 1f
                        writeProgressText = failure
                        writeTerminalText = failure
                        writeError = failure
                    }
                }
            }.onFailure { error ->
                Log.e("SparkWriter", "Failed to monitor USB write work", error)
                val failure = context.getString(R.string.write_failed_detail, error.visibleWriteFailureMessage())
                status = failure
                writeProgress = 1f
                writeProgressText = context.getString(R.string.write_failed)
                writeTerminalText = failure
                writeError = failure
            }
            writeInProgress = false
            delay(1000)
        }
    }

    SparkWriterTheme {
        fun selectManifest(manifest: SparkManifest, keepImportedImage: Boolean = false) {
            selectedManifestId = manifest.metadata.id
            if (!keepImportedImage) {
                importedImage = null
                importedImageMatches = null
            }
            status = if (manifest.isAndroidRunnable) {
                context.getString(R.string.manifest_selected)
            } else {
                manifest.unsupportedReasons.joinToString()
            }
            currentRoute = SparkWriterRoute.Workflow
        }
        val workflowDetail: @Composable (Modifier) -> Unit = { detailModifier ->
            WorkflowPane(
                manifest = selectedManifest ?: rawImageManifest,
                importedImage = importedImage,
                generatedSecrets = generatedSecrets,
                onGeneratedSecrets = { generatedSecrets = it },
                usbWriter = usbWriter,
                isoIndexStore = isoIndexStore,
                status = status,
                manifestInstallInProgress = manifestInstallInProgress,
                activeWriteInProgress = writeInProgress,
                appScope = scope,
                onPickImage = {
                    pickerManifestId = (selectedManifest ?: rawImageManifest).metadata.id
                    imagePicker.launch(
                        arrayOf(
                            "application/x-iso9660-image",
                            "application/octet-stream",
                        ),
                    )
                },
                onWritingChange = { writeInProgress = it },
                onStatus = { status = it },
                onWriteProgress = { percent, text ->
                    writeProgress = percent
                    writeProgressText = text
                    writeTerminalText = if (percent >= 1f) text else null
                },
                onWriteError = { message ->
                    writeError = message
                    writeTerminalText = message
                },
                modifier = detailModifier,
            )
        }
        val workflowScreen: @Composable (Boolean) -> Unit = { showSourceList ->
            if (showSourceList) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sourcePaneWidth = minOf(420.dp, maxWidth * 0.33f)
                    if (sourcePaneWidth >= 280.dp) {
                        Row(Modifier.fillMaxSize()) {
                            SourceCatalog(
                                manifests = offeredManifests,
                                visibleManifestIds = importedImageMatches,
                                selected = selectedManifest,
                                onSelect = { selectManifest(it, keepImportedImage = importedImageMatches != null) },
                                onImportZip = if (localZipImportEnabled) ({ sparkPlugPicker.launch(Unit) }) else null,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(sourcePaneWidth),
                            )
                            workflowDetail(Modifier.weight(1f).fillMaxSize())
                        }
                    } else {
                        workflowDetail(Modifier.fillMaxSize())
                    }
                }
            } else {
                workflowDetail(Modifier.fillMaxSize())
            }
        }
        val writeHeaderText = when {
            writeInProgress -> stringResource(R.string.write_in_progress)
            writeTerminalText != null -> stringResource(R.string.write_last)
            else -> null
        }
        val writeHeaderDetail = when {
            writeInProgress -> writeProgressText
            else -> writeTerminalText
        }?.orEmpty().orEmpty()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                            writeHeaderText?.let { header ->
                                if (imageImportProgress == null) {
                                    Column(
                                        modifier = Modifier.width(320.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                text = buildAnnotatedString {
                                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(header) }
                                                    if (writeHeaderDetail.isNotBlank()) append(" $writeHeaderDetail")
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                            )
                                        }
                                        if (writeProgress != null || writeInProgress) {
                                            LinearProgressIndicator(
                                                progress = { writeProgress ?: 1f },
                                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                            )
                                        } else {
                                            LinearProgressIndicator(
                                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val useBottomBar = maxWidth < 840.dp
                if (useBottomBar) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            when (currentRoute) {
                                SparkWriterRoute.Sources -> SourceCatalog(
                                    manifests = offeredManifests,
                                    visibleManifestIds = importedImageMatches,
                                    selected = selectedManifest,
                                    onSelect = { selectManifest(it, keepImportedImage = importedImageMatches != null) },
                                    onImportZip = if (localZipImportEnabled) ({ sparkPlugPicker.launch(Unit) }) else null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SparkWriterRoute.Workflow -> workflowScreen(false)
                                SparkWriterRoute.Device -> DeviceScreen(
                                    usbWriter = usbWriter,
                                    writeActive = writeInProgress,
                                    onStatus = { status = it },
                                )
                                SparkWriterRoute.Profile -> ProfileScreen(
                                    onSaveCompleted = { status = it },
                                )
                                SparkWriterRoute.Settings -> SettingsScreen()
                            }
                        }
                        NavigationBar {
                            routeScreenItems.forEach { route ->
                                NavigationBarItem(
                                    selected = currentRoute == route,
                                    onClick = { currentRoute = route },
                                    icon = {
                                        Icon(
                                            imageVector = when (route) {
                                                SparkWriterRoute.Sources -> Icons.AutoMirrored.Filled.List
                                                SparkWriterRoute.Workflow -> Icons.Filled.Build
                                                SparkWriterRoute.Device -> Icons.Filled.Info
                                                SparkWriterRoute.Profile -> Icons.Filled.Info
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
                } else {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail {
                            routeScreenItems.forEach { route ->
                                NavigationRailItem(
                                    selected = currentRoute == route,
                                    onClick = { currentRoute = route },
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
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            when (currentRoute) {
                                SparkWriterRoute.Sources -> SourceCatalog(
                                    manifests = offeredManifests,
                                    visibleManifestIds = importedImageMatches,
                                    selected = selectedManifest,
                                    onSelect = { selectManifest(it, keepImportedImage = importedImageMatches != null) },
                                    onImportZip = if (localZipImportEnabled) ({ sparkPlugPicker.launch(Unit) }) else null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SparkWriterRoute.Workflow -> workflowScreen(true)
                                SparkWriterRoute.Device -> DeviceScreen(
                                    usbWriter = usbWriter,
                                    writeActive = writeInProgress,
                                    onStatus = { status = it },
                                )
                                SparkWriterRoute.Profile -> ProfileScreen(
                                    onSaveCompleted = { status = it },
                                )
                                SparkWriterRoute.Settings -> SettingsScreen()
                            }
                        }
                    }
                }
                writeError?.let { message ->
                    AlertDialog(
                        onDismissRequest = { writeError = null },
                        title = { Text(stringResource(R.string.error)) },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(message)
                                TextButton(
                                    onClick = { scope.launch { clipboard.copyText(context, message) } },
                                ) {
                                    Text(stringResource(R.string.copy))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { writeError = null }) {
                                Text(stringResource(R.string.ok))
                            }
                        },
                    )
                }
                manifestImportStatus?.let { importStatus ->
                    ManifestImportDialog(
                        status = importStatus,
                        onDismiss = {
                            if (importStatus.phase == ManifestImportPhase.AwaitingConfirmation || importStatus.phase == ManifestImportPhase.AwaitingPackageConfirmation || importStatus.phase == ManifestImportPhase.Reviewing) {
                                manifestInstallRequest?.let { handledManifestInstallNonce = it.nonce }
                                pendingSparkPlugImport = null
                                status = "Manifest import cancelled"
                            }
                            manifestImportStatus = null
                        },
                        onApprove = {
                            if (importStatus.phase == ManifestImportPhase.AwaitingPackageConfirmation) {
                                val pending = pendingSparkPlugImport ?: return@ManifestImportDialog
                                manifestImportStatus = importStatus.copy(
                                    phase = ManifestImportPhase.Reviewing,
                                    detail = "Package structure accepted. Review system changes before importing.",
                                    pending = pending,
                                )
                            } else if (importStatus.phase == ManifestImportPhase.Reviewing) {
                                val pending = pendingSparkPlugImport ?: return@ManifestImportDialog
                                scope.launch(Dispatchers.IO) { installedManifestStore.upsertManifest(pending.manifest) }
                                manifests = listOf(pending.manifest) + manifests.filterNot { it.metadata.id == pending.manifest.metadata.id }
                                selectedManifestId = pending.manifest.metadata.id
                                importedImage = null
                                importedImageMatches = null
                                manifestInstallRequest?.let { handledManifestInstallNonce = it.nonce }
                                status = "Installed ${pending.manifest.metadata.name}"
                                pendingSparkPlugImport = null
                                manifestImportStatus = null
                                currentRoute = SparkWriterRoute.Workflow
                            } else manifestInstallRequest?.let { approvedManifestInstallNonce = it.nonce }
                        },
                        onCopy = { text -> scope.launch { clipboard.copyText(context, text) } },
                    )
                }
                imageImportProgress?.let { progress ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.importing_iso), fontWeight = FontWeight.SemiBold)
                            progress.progressFraction()?.let { value ->
                                LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
                            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            SelectionContainer {
                                Text(
                                    progress.visibleImportStatus(),
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        }
                    }
                }
                if (showNotificationPermissionExplanation) {
                    AlertDialog(
                        onDismissRequest = { showNotificationPermissionExplanation = false },
                        title = { Text(stringResource(R.string.notification_permission_title)) },
                        text = { Text(stringResource(R.string.notification_permission_explanation)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showNotificationPermissionExplanation = false
                                (context as? MainActivity)?.requestNotificationPermissionIfNeeded()
                            }) { Text(stringResource(R.string.continue_label)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNotificationPermissionExplanation = false }) { Text(stringResource(R.string.not_now)) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManifestImportDialog(
    status: ManifestImportStatus,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var selectedArtifact by remember { mutableStateOf<String?>(null) }
    val visibleMessage = status.visibleMessage()
    val sourceText = stringResource(R.string.source_value, status.manifestUrl)
    AlertDialog(
        onDismissRequest = {
            if (status.phase != ManifestImportPhase.Checking && status.phase != ManifestImportPhase.Downloading) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.manifest_import)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(visibleMessage, fontWeight = FontWeight.SemiBold)
                if (status.inProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                status.detail?.ifBlank { null }?.let { detail ->
                    SelectionContainer {
                        Text(detail)
                    }
                }
                status.pending?.takeIf { status.phase == ManifestImportPhase.Reviewing }?.let { pending ->
                    Text(stringResource(R.string.review_system_changes), fontWeight = FontWeight.SemiBold)
                    Text("${pending.manifest.metadata.name} ${pending.manifest.metadata.version.orEmpty()} — ${pending.manifest.metadata.author ?: stringResource(R.string.unknown_author)}")
                    if (pending.findings.isEmpty()) {
                        Text(stringResource(R.string.review_no_findings))
                    } else {
                        pending.findings.forEach { finding ->
                            TextButton(onClick = {
                                selectedArtifact = "${finding.category}: ${finding.explanation}\n${finding.source}:${finding.line}: ${finding.excerpt}"
                            }) { Text(stringResource(R.string.view_artifact, finding.category)) }
                        }
                    }
                    Text(stringResource(R.string.review_disclaimer))
                    pending.scripts.forEach { script ->
                        val scriptType = stringResource(if (script.inline) R.string.inline_script else R.string.script_file)
                        TextButton(onClick = {
                            selectedArtifact = "$scriptType: ${script.label}\n${script.text}"
                        }) { Text(stringResource(R.string.view_artifact, script.label)) }
                    }
                }
                SelectionContainer {
                    Text(sourceText)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (status.phase == ManifestImportPhase.AwaitingConfirmation || status.phase == ManifestImportPhase.AwaitingPackageConfirmation || status.phase == ManifestImportPhase.Reviewing) onApprove else onDismiss,
                enabled = !status.inProgress,
            ) {
                Text(when (status.phase) {
                    ManifestImportPhase.AwaitingConfirmation -> stringResource(R.string.download_for_inspection)
                    ManifestImportPhase.AwaitingPackageConfirmation -> stringResource(R.string.review_contents)
                    ManifestImportPhase.Reviewing -> stringResource(R.string.import_sparkplug)
                    else -> stringResource(R.string.ok)
                })
            }
        },
        dismissButton = {
            if (status.phase == ManifestImportPhase.AwaitingConfirmation || status.phase == ManifestImportPhase.AwaitingPackageConfirmation || status.phase == ManifestImportPhase.Reviewing) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            } else {
                TextButton(onClick = { onCopy(status.copyText(visibleMessage, sourceText)) }) { Text(stringResource(R.string.copy)) }
            }
        },
    )
    selectedArtifact?.let { artifact ->
        AlertDialog(
            onDismissRequest = { selectedArtifact = null },
            title = { Text(stringResource(R.string.artifact_details)) },
            text = { SelectionContainer { Text(artifact) } },
            confirmButton = { TextButton(onClick = { selectedArtifact = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun ManifestImportStatus.visibleMessage(): String {
    return when (phase) {
        ManifestImportPhase.Received -> stringResource(R.string.sparkplug_link_received)
        ManifestImportPhase.Checking -> stringResource(R.string.checking_manifest_source)
        ManifestImportPhase.AwaitingConfirmation -> stringResource(R.string.download_this_manifest)
        ManifestImportPhase.AwaitingPackageConfirmation -> stringResource(R.string.inspect_sparkplug_package)
        ManifestImportPhase.Downloading -> stringResource(R.string.importing_manifest)
        ManifestImportPhase.Reviewing -> stringResource(R.string.review_system_changes)
        ManifestImportPhase.Installed -> stringResource(R.string.imported_manifest, manifestName ?: "manifest")
        ManifestImportPhase.Failed -> stringResource(R.string.manifest_import_failed)
    }
}

private fun ManifestImportStatus.copyText(visibleMessage: String, sourceText: String): String {
    return buildString {
        append(visibleMessage)
        detail?.ifBlank { null }?.let { append('\n').append(it) }
        append('\n').append(sourceText)
    }
}

private data class PickedIsoDocument(
    val uri: Uri,
    val flags: Int,
)

private class IsoDocumentPicker : ActivityResultContract<Array<String>, PickedIsoDocument?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedIsoDocument? {
        val uri = intent?.data ?: return null
        if (resultCode != Activity.RESULT_OK) return null
        return PickedIsoDocument(uri, intent.flags)
    }
}

private class SparkPlugZipPicker : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
