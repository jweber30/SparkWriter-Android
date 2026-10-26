package net.metalstrapper.sparkwriter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.List
import kotlinx.coroutines.launch
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.storage.SourceGroupStore
import net.metalstrapper.sparkwriter.storage.SourceGrouping

@Composable
internal fun SourceCatalog(
    manifests: List<SparkManifest>,
    visibleManifestIds: Set<String>? = null,
    selected: SparkManifest?,
    onSelect: (SparkManifest) -> Unit,
    onImportZip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groupStore = remember(context) { SourceGroupStore(context.applicationContext) }
    var grouping by remember { mutableStateOf(SourceGrouping()) }
    var creatingGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var draggedManifestId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Unspecified) }
    val dropTargets = remember { mutableStateMapOf<String?, Rect>() }
    var deletingGroupId by remember { mutableStateOf<String?>(null) }
    var movingManifestId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(groupStore) { groupStore.observe().collect { grouping = it } }
    val visibleManifests = visibleManifestIds?.let { ids ->
        manifests.filter { it.metadata.id in ids }
    } ?: manifests
    val validGroupIds = grouping.groups.mapTo(mutableSetOf()) { it.id }
    val groupedManifests = visibleManifests.groupBy { manifest ->
        grouping.manifestGroups[manifest.metadata.id]?.takeIf { it in validGroupIds }
    }

    if (creatingGroup) {
        AlertDialog(
            onDismissRequest = { creatingGroup = false },
            title = { Text(stringResource(R.string.create_source_group)) },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newGroupName.isNotBlank(),
                    onClick = {
                        val groupName = newGroupName.trim()
                        scope.launch { groupStore.create(groupName) }
                        newGroupName = ""
                        creatingGroup = false
                    },
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { creatingGroup = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deletingGroupId?.let { groupId ->
        val groupName = grouping.groups.firstOrNull { it.id == groupId }?.name ?: stringResource(R.string.default_group_name)
        AlertDialog(
            onDismissRequest = { deletingGroupId = null },
            title = { Text(stringResource(R.string.delete_group, groupName)) },
            text = { Text(stringResource(R.string.delete_group_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { groupStore.delete(groupId) }
                    deletingGroupId = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingGroupId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    movingManifestId?.let { manifestId ->
        val manifestName = manifests.firstOrNull { it.metadata.id == manifestId }?.metadata?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { movingManifestId = null },
            title = { Text(stringResource(R.string.move_to_group)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(manifestName)
                    TextButton(onClick = {
                        scope.launch { groupStore.assign(manifestId, null) }
                        movingManifestId = null
                    }) { Text(stringResource(R.string.ungrouped)) }
                    grouping.groups.forEach { group ->
                        TextButton(onClick = {
                            scope.launch { groupStore.assign(manifestId, group.id) }
                            movingManifestId = null
                        }) { Text(group.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingManifestId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.nav_sources), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onImportZip != null) {
                    Button(onClick = onImportZip) { Text(stringResource(R.string.local_zip)) }
                }
                Button(onClick = { creatingGroup = true }) { Text(stringResource(R.string.new_group)) }
            }
        }
        fun androidx.compose.foundation.lazy.LazyListScope.manifestCards(items: List<SparkManifest>) {
            items(items, key = { it.metadata.id }) { manifest ->
            var cardBounds by remember { mutableStateOf(Rect.Zero) }
            var cardDragOffset by remember { mutableStateOf(Offset.Zero) }
            Card(
                onClick = { onSelect(manifest) },
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (draggedManifestId == manifest.metadata.id) 1f else 0f)
                    .graphicsLayer {
                        translationX = cardDragOffset.x
                        translationY = cardDragOffset.y
                        alpha = if (draggedManifestId == manifest.metadata.id) 0.85f else 1f
                    }
                    .onGloballyPositioned { cardBounds = it.boundsInRoot() }
                    .pointerInput(manifest.metadata.id, dropTargets) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { localOffset ->
                                draggedManifestId = manifest.metadata.id
                                dragPosition = cardBounds.topLeft + localOffset
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragPosition += amount
                                cardDragOffset += amount
                            },
                            onDragCancel = {
                                draggedManifestId = null
                                dragPosition = Offset.Unspecified
                                cardDragOffset = Offset.Zero
                            },
                            onDragEnd = {
                                val targetId = dropTargets.entries
                                    .firstOrNull { (_, bounds) -> dragPosition in bounds }
                                    ?.key
                                if (targetId != null || dropTargets[null]?.contains(dragPosition) == true) {
                                    scope.launch { groupStore.assign(manifest.metadata.id, targetId) }
                                }
                                draggedManifestId = null
                                dragPosition = Offset.Unspecified
                                cardDragOffset = Offset.Zero
                            },
                        )
                    },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(manifest.metadata.name, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { movingManifestId = manifest.metadata.id }) {
                        Text(stringResource(R.string.more))
                    }
                }
            }
            }
        }
        grouping.groups.forEach { group ->
            val members = groupedManifests[group.id].orEmpty()
            item(key = "group-${group.id}") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.onGloballyPositioned { dropTargets[group.id] = it.boundsInRoot() },
                ) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { deletingGroupId = group.id }) { Text(stringResource(R.string.delete)) }
                }
            }
            manifestCards(members)
        }
        val ungrouped = groupedManifests[null].orEmpty()
        item(key = "ungrouped") {
            Text(
                stringResource(R.string.ungrouped),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { dropTargets[null] = it.boundsInRoot() },
            )
        }
        if (ungrouped.isNotEmpty()) {
            manifestCards(ungrouped)
        }
    }
}

@Composable
internal fun SourceDetailsDialog(
    manifest: SparkManifest,
    importedImage: VerifiedImage?,
    downloadUrl: String?,
    onDismiss: () -> Unit,
) {
    val isoNameLabel = stringResource(R.string.iso_name)
    val sourceIsoLabel = stringResource(R.string.source_iso)
    val urlLabel = stringResource(R.string.url)
    val sparkPlugDescriptionLabel = stringResource(R.string.sparkplug_description)
    val sparkPlugSourceUrlLabel = stringResource(R.string.sparkplug_source_url)
    val details = buildList {
        importedImage?.displayName?.ifBlank { null }?.let { add(isoNameLabel to it) }
        manifest.source?.name?.ifBlank { null }?.let { add(sourceIsoLabel to it) }
        downloadUrl?.ifBlank { null }?.let { add(urlLabel to it) }
        manifest.metadata.description?.ifBlank { null }?.let { add(sparkPlugDescriptionLabel to it) }
        manifest.installOrigin?.ifBlank { null }?.let { add(sparkPlugSourceUrlLabel to it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_details)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    details.forEach { (label, value) ->
                        Column {
                            Text(label, fontWeight = FontWeight.SemiBold)
                            Text(value)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}
