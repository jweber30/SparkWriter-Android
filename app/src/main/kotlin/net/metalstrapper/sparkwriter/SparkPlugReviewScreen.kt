package net.metalstrapper.sparkwriter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Colors for one severity: a container to sit on and the text that goes on it. */
private data class SeverityColors(val container: Color, val onContainer: Color)

@Composable
private fun ReviewSeverity.colors(): SeverityColors = when (this) {
    ReviewSeverity.Red -> SeverityColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    ReviewSeverity.Amber -> if (isSystemInDarkTheme()) {
        SeverityColors(Color(0xFF5C4300), Color(0xFFFFDF9E))
    } else {
        SeverityColors(Color(0xFFFFE08A), Color(0xFF3D2E00))
    }
}

/**
 * Full-screen review shown before a SparkPlug is imported. Red items must be
 * acknowledged before the import button turns on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SparkPlugReviewScreen(
    pending: PendingSparkPlugImport,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    val review = remember(pending) { reviewSparkPlug(pending.manifest) }
    var acknowledged by remember(pending) { mutableStateOf(false) }
    var openDocument by remember(pending) { mutableStateOf<ReviewDocument?>(null) }
    val canImport = review.red.isEmpty() || acknowledged

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.review_title)) },
                        navigationIcon = {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                            }
                        },
                    )
                },
                bottomBar = {
                    // A plain surface, not BottomAppBar: its fixed height clips the checkbox row.
                    Surface(tonalElevation = 3.dp) {
                        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                            if (review.red.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { acknowledged = !acknowledged },
                                ) {
                                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                                    Text(stringResource(R.string.review_acknowledge), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = onImport, enabled = canImport) { Text(stringResource(R.string.import_sparkplug)) }
                            }
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { ReviewHeader(pending, review) }
                        reviewSections(review)
                        if (review.documents.isNotEmpty()) {
                            item { SectionTitle(stringResource(R.string.review_section_files), null) }
                            items(review.documents, key = { "doc:" + it.path }) { document ->
                                DocumentRow(document) { openDocument = document }
                            }
                        }
                        item {
                            Text(
                                stringResource(R.string.review_disclaimer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    openDocument?.let { document -> ReviewDocumentViewer(document) { openDocument = null } }
}

@Composable
private fun ReviewHeader(pending: PendingSparkPlugImport, review: SparkPlugReview) {
    val metadata = pending.manifest.metadata
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            listOfNotNull(metadata.name, metadata.version).joinToString(" "),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(metadata.author ?: stringResource(R.string.unknown_author), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(
                when (pending.trust) {
                    SparkPlugTrust.Bundled -> R.string.review_trust_bundled
                    SparkPlugTrust.LocalZip -> R.string.review_trust_local_zip
                    SparkPlugTrust.RemoteHttps -> R.string.review_trust_remote
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        SelectionContainer {
            Text(pending.sourceLabel, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            CountBadge(ReviewSeverity.Red, stringResource(R.string.review_count_red, review.red.size))
            CountBadge(ReviewSeverity.Amber, stringResource(R.string.review_count_amber, review.amber.size))
        }
        if (review.items.isEmpty()) {
            Text(stringResource(R.string.review_no_findings), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CountBadge(severity: ReviewSeverity, text: String) {
    val colors = severity.colors()
    Text(
        text,
        color = colors.onContainer,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(colors.container, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun LazyListScope.reviewSections(review: SparkPlugReview) {
    fun of(vararg kinds: ReviewKind) = review.items.filter { it.kind in kinds }

    // Red first: what reaches out or lets someone in.
    of(ReviewKind.NetworkAddress).takeIf { it.isNotEmpty() }?.let { urls ->
        item { SectionTitle(stringResource(R.string.review_section_network), stringResource(R.string.review_section_network_detail)) }
        items(urls.groupBy { it.value }.entries.toList(), key = { "url:" + it.key }) { (value, found) ->
            FindingCard(ReviewSeverity.Red, value, found, roles = found.mapNotNull { it.urlRole }.distinct())
        }
    }
    of(ReviewKind.SshKey, ReviewKind.PrivateKey, ReviewKind.SshKeyImport).takeIf { it.isNotEmpty() }?.let { keys ->
        item { SectionTitle(stringResource(R.string.review_section_ssh), stringResource(R.string.review_section_ssh_detail)) }
        items(keys.groupBy { it.kind to (it.detail ?: it.value) }.entries.toList(), key = { "ssh:" + it.key }) { (_, found) ->
            val first = found.first()
            val heading = when (first.kind) {
                ReviewKind.PrivateKey -> stringResource(R.string.review_private_key)
                ReviewKind.SshKeyImport -> stringResource(R.string.review_key_import)
                else -> first.detail ?: first.value
            }
            FindingCard(ReviewSeverity.Red, heading, found)
        }
    }
    of(ReviewKind.HiddenText).takeIf { it.isNotEmpty() }?.let { hidden ->
        item { SectionTitle(stringResource(R.string.review_section_hidden), stringResource(R.string.review_section_hidden_detail)) }
        items(hidden.groupBy { it.detail.orEmpty() }.entries.toList(), key = { "hidden:" + it.key }) { (labels, found) ->
            FindingCard(ReviewSeverity.Red, labels, found)
        }
    }

    // Amber: worth reading.
    of(ReviewKind.RawPlaceholder).takeIf { it.isNotEmpty() }?.let { raw ->
        item { SectionTitle(stringResource(R.string.review_section_placeholders), stringResource(R.string.review_section_placeholders_detail)) }
        items(raw.groupBy { it.value }.entries.toList(), key = { "raw:" + it.key }) { (value, found) ->
            FindingCard(ReviewSeverity.Amber, value, found)
        }
    }
    of(ReviewKind.IpAddress).takeIf { it.isNotEmpty() }?.let { ips ->
        item { SectionTitle(stringResource(R.string.review_section_ip), stringResource(R.string.review_section_ip_detail)) }
        items(ips.groupBy { it.value }.entries.toList(), key = { "ip:" + it.key }) { (value, found) ->
            FindingCard(ReviewSeverity.Amber, value, found)
        }
    }
    of(ReviewKind.Behavior).takeIf { it.isNotEmpty() }?.let { behaviors ->
        item { SectionTitle(stringResource(R.string.review_section_behavior), null) }
        items(behaviors.groupBy { it.value }.entries.toList(), key = { "behavior:" + it.key }) { (category, found) ->
            FindingCard(ReviewSeverity.Amber, category, found, explanation = found.first().detail, codeHeading = false)
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String?) {
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun FindingCard(
    severity: ReviewSeverity,
    heading: String,
    found: List<ReviewItem>,
    roles: List<UrlRole> = emptyList(),
    explanation: String? = null,
    /** Monospace for headings that are SparkPlug text (a URL, key or placeholder), not a label. */
    codeHeading: Boolean = true,
) {
    val colors = severity.colors()
    Card(colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.onContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectionContainer {
                Text(
                    heading,
                    fontFamily = if (codeHeading) FontFamily.Monospace else null,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            roles.forEach { Text(stringResource(it.labelRes()), style = MaterialTheme.typography.bodyMedium) }
            explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            found.forEach { item ->
                HorizontalDivider(color = colors.onContainer.copy(alpha = 0.2f))
                Text(item.locationText(), style = MaterialTheme.typography.labelMedium)
                if (item.excerpt.isNotEmpty() && item.excerpt != heading) {
                    SelectionContainer {
                        Text(item.excerpt, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewItem.locationText(): String =
    line?.let { stringResource(R.string.review_location_line, path, it) } ?: path

private fun UrlRole.labelRes(): Int = when (this) {
    UrlRole.Download -> R.string.review_role_download
    UrlRole.FetchesData -> R.string.review_role_fetches
    UrlRole.SendsData -> R.string.review_role_sends
    UrlRole.ReadsData -> R.string.review_role_reads
    UrlRole.Homepage -> R.string.review_role_homepage
    UrlRole.InFile -> R.string.review_role_in_file
    UrlRole.InText -> R.string.review_role_in_text
}

@Composable
private fun DocumentRow(document: ReviewDocument, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val dot = document.severity?.colors()?.container ?: MaterialTheme.colorScheme.outlineVariant
            Box(modifier = Modifier.size(12.dp).background(dot, CircleShape))
            Column(modifier = Modifier.weight(1f)) {
                Text(document.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.review_file_lines, document.lineCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.review_open_file), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Shows a file with line numbers, flagged text highlighted and hidden characters spelled out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewDocumentViewer(document: ReviewDocument, onClose: () -> Unit) {
    val red = ReviewSeverity.Red.colors()
    val amber = ReviewSeverity.Amber.colors()
    val lines = remember(document) { document.highlightedLines() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(document.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall) },
                        navigationIcon = {
                            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ok)) }
                        },
                    )
                },
            ) { padding ->
                SelectionContainer(modifier = Modifier.padding(padding)) {
                    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                        items(lines.size) { index ->
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                Text(
                                    (index + 1).toString().padStart(lines.size.toString().length),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    lines[index].toAnnotated(red, amber),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One line of a document as runs of text, each with the strongest severity covering it. */
private data class HighlightRun(val text: String, val severity: ReviewSeverity?, val hidden: Boolean)

private fun ReviewDocument.highlightedLines(): List<List<HighlightRun>> {
    val severityAt = arrayOfNulls<ReviewSeverity>(text.length)
    spans.forEach { span ->
        for (index in span.start until minOf(span.end, text.length)) {
            val current = severityAt[index]
            if (current == null || span.severity.ordinal < current.ordinal) severityAt[index] = span.severity
        }
    }
    val result = mutableListOf<List<HighlightRun>>()
    var runs = mutableListOf<HighlightRun>()
    val buffer = StringBuilder()
    var bufferSeverity: ReviewSeverity? = null
    fun flush() {
        if (buffer.isNotEmpty()) runs += HighlightRun(buffer.toString(), bufferSeverity, hidden = false)
        buffer.clear()
    }
    text.forEachIndexed { index, char ->
        when {
            char == '\n' -> {
                flush()
                result += runs
                runs = mutableListOf()
            }
            char.isHiddenReviewChar() -> {
                flush()
                runs += HighlightRun(char.hiddenCharLabel(), ReviewSeverity.Red, hidden = true)
            }
            else -> {
                if (severityAt[index] != bufferSeverity) flush()
                bufferSeverity = severityAt[index]
                buffer.append(char)
            }
        }
    }
    flush()
    if (!text.endsWith('\n')) result += runs
    return result
}

private fun List<HighlightRun>.toAnnotated(red: SeverityColors, amber: SeverityColors): AnnotatedString = buildAnnotatedString {
    forEach { run ->
        val colors = when (run.severity) {
            ReviewSeverity.Red -> red
            ReviewSeverity.Amber -> amber
            null -> null
        }
        if (colors == null) {
            append(run.text)
        } else {
            withStyle(SpanStyle(background = colors.container, color = colors.onContainer, fontWeight = if (run.hidden) FontWeight.Bold else null)) {
                append(run.text)
            }
        }
    }
}
