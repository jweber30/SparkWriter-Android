package net.metalstrapper.sparkwriter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.SparkTemplateFilters
import java.security.MessageDigest
import java.util.Base64

/**
 * What a person should look at before importing a SparkPlug.
 *
 * The scan walks every string in the SparkPlug, not a list of known fields, so a
 * URL or key cannot hide in a field the scanner forgot. Red items are things
 * that reach out or let someone in: network addresses, SSH keys, hidden text.
 * Amber items are worth reading: raw placeholders, IP literals and the script
 * behaviors from [analyzeNotableBehavior].
 */
internal enum class ReviewSeverity { Red, Amber }

internal enum class ReviewKind(val severity: ReviewSeverity) {
    NetworkAddress(ReviewSeverity.Red),
    SshKey(ReviewSeverity.Red),
    PrivateKey(ReviewSeverity.Red),
    SshKeyImport(ReviewSeverity.Red),
    HiddenText(ReviewSeverity.Red),
    RawPlaceholder(ReviewSeverity.Amber),
    IpAddress(ReviewSeverity.Amber),
    Behavior(ReviewSeverity.Amber),
}

/** Where a network address is used, judged from its place in the SparkPlug. */
internal enum class UrlRole { Download, FetchesData, SendsData, ReadsData, Homepage, InFile, InText }

internal data class ReviewItem(
    val kind: ReviewKind,
    /** The URL, key, placeholder or behavior category, with hidden characters made visible. */
    val value: String,
    /** Where it sits in the SparkPlug, such as `templates.answer_toml` or `configFields[hostname].default`. */
    val path: String,
    /** 1-based line within a multi-line text, or null for a one-line value. */
    val line: Int?,
    /** The line it was found on, trimmed and with hidden characters made visible. */
    val excerpt: String,
    /** Key fingerprint, placeholder variable or behavior explanation. */
    val detail: String? = null,
    val urlRole: UrlRole? = null,
) {
    val severity: ReviewSeverity get() = kind.severity
}

/** A highlighted character range in a [ReviewDocument]'s text, end exclusive. */
internal data class ReviewSpan(val start: Int, val end: Int, val severity: ReviewSeverity)

/** A multi-line text from the SparkPlug (a template or script) for the file viewer. */
internal data class ReviewDocument(val path: String, val text: String, val spans: List<ReviewSpan>) {
    val severity: ReviewSeverity? get() = spans.minOfOrNull { it.severity }

    /** Lines as an editor counts them: a final line break does not start another line. */
    val lineCount: Int get() = text.removeSuffix("\n").lines().size
}

internal data class SparkPlugReview(val items: List<ReviewItem>, val documents: List<ReviewDocument>) {
    val red: List<ReviewItem> get() = items.filter { it.severity == ReviewSeverity.Red }
    val amber: List<ReviewItem> get() = items.filter { it.severity == ReviewSeverity.Amber }
}

internal fun reviewSparkPlug(manifest: SparkManifest): SparkPlugReview {
    val root = reviewJson.encodeToJsonElement(SparkManifest.serializer(), manifest)
    val strings = mutableListOf<FoundString>()
    collectStrings(root, path = "", key = null, out = strings)
    val scanner = ReviewScanner(manifest)
    strings.forEach(scanner::scan)
    val items = scanner.items.sortedWith(compareBy({ it.kind.ordinal }, { it.path }, { it.line ?: 0 }))
    return SparkPlugReview(items, scanner.documents)
}

/** Text that may hide or reorder what a reviewer sees: bidi controls, zero-width and other format characters. */
internal fun Char.isHiddenReviewChar(): Boolean =
    this in '​'..'‏' || this in '‪'..'‮' || this in '⁠'..'⁤' ||
        this in '⁦'..'⁩' || this == '﻿' || this == '­' ||
        (isISOControl() && this != '\n' && this != '\t' && this != '\r')

/** The text with every hidden character spelled out, such as `⟦U+202E⟧`. */
internal fun String.withHiddenCharsVisible(): String =
    if (none { it.isHiddenReviewChar() }) this else buildString {
        this@withHiddenCharsVisible.forEach { char ->
            if (char.isHiddenReviewChar()) append(char.hiddenCharLabel()) else append(char)
        }
    }

internal fun Char.hiddenCharLabel(): String = "⟦U+" + code.toString(16).uppercase().padStart(4, '0') + "⟧"

private val reviewJson = Json { encodeDefaults = false }

/** Provenance the app records, not SparkPlug content. */
private val EXCLUDED_PATHS = setOf("installOrigin", "packageRootSha256", "metadata.manifestRootSha256")

/** Fields that hold an address even when the value is a placeholder rather than a literal URL. */
private val URL_KEYS = setOf("url", "uri", "imageUri", "readBaseUrl", "writeBaseUrl", "homepage", "registryUrl", "manifestUrl")

private val URL_REGEX = Regex("""(?i)\b[a-z][a-z0-9+.-]{1,15}://[^\s'"<>`\\)\]}]+""")
private val SSH_KEY_REGEX = Regex(
    """(?:sk-)?(?:ssh-(?:ed25519|rsa|dss)|ecdsa-sha2-nistp(?:256|384|521))(?:@openssh\.com)?[ \t]+(AAAA[0-9A-Za-z+/]+={0,3})(?:[ \t]+([^\s"',\]\\]+))?""",
)
private val PRIVATE_KEY_REGEX = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")
private val SSH_IMPORT_REGEX = Regex("""(?i)\bssh[_-]import[_-]id\b""")
private val HIDDEN_RUN_REGEX = Regex("""[​-‏‪-‮⁠-⁤⁦-⁩﻿­\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]+""")
private val IPV4_REGEX = Regex("""(?<![\w.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\w.])""")

/** The red URL item covers these; repeating them as amber behaviors adds noise. */
private const val SCRIPT_ENDPOINT_CATEGORY = "Script network endpoint"

private data class FoundString(val path: String, val key: String?, val value: String)

private fun collectStrings(element: JsonElement, path: String, key: String?, out: MutableList<FoundString>) {
    when (element) {
        is JsonObject -> element.forEach { (childKey, child) ->
            val childPath = if (path.isEmpty()) childKey else "$path.$childKey"
            if (childPath !in EXCLUDED_PATHS) collectStrings(child, childPath, childKey, out)
        }
        is JsonArray -> element.forEachIndexed { index, child ->
            val id = ((child as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            collectStrings(child, "$path[${id ?: index}]", key, out)
        }
        is JsonPrimitive -> if (element.isString && element.content.isNotEmpty()) {
            out += FoundString(path, key, element.content)
        }
    }
}

private class ReviewScanner(manifest: SparkManifest) {
    val items = mutableListOf<ReviewItem>()
    val documents = mutableListOf<ReviewDocument>()

    /** Variables the SparkPlug fixes itself, so a raw placeholder for them cannot carry typed text. */
    private val fixedVariables: Set<String> = buildSet {
        manifest.actionPhases.values.flatten().mapNotNullTo(this) { it.outputVar }
        manifest.configFields.filter { it.type == "select" || it.type == "info" }.forEach { add(it.id); add(it.formFieldId()) }
    }

    fun scan(found: FoundString) {
        val text = found.value
        val lines = LineIndex(text)
        val multiLine = lines.count > 1
        val spans = mutableListOf<ReviewSpan>()
        val urlRanges = mutableListOf<IntRange>()

        fun add(kind: ReviewKind, range: IntRange, value: String, detail: String? = null, role: UrlRole? = null) {
            items += ReviewItem(
                kind = kind,
                value = value.withHiddenCharsVisible(),
                path = found.path,
                line = if (multiLine) lines.lineOf(range.first) else null,
                excerpt = lines.excerptAt(range.first),
                detail = detail,
                urlRole = role,
            )
            spans += ReviewSpan(range.first, range.last + 1, kind.severity)
        }

        if (found.key in URL_KEYS) {
            add(ReviewKind.NetworkAddress, text.indices, text.trim(), role = urlRole(found.path))
            urlRanges += text.indices
        } else {
            URL_REGEX.findAll(text).forEach { match ->
                add(ReviewKind.NetworkAddress, match.range, match.value, role = urlRole(found.path))
                urlRanges += match.range
            }
        }
        SSH_KEY_REGEX.findAll(text).forEach { match ->
            val type = match.value.substringBefore(' ').substringBefore('\t')
            val comment = match.groups[2]?.value
            val detail = listOfNotNull(type, sshFingerprint(match.groupValues[1]), comment).joinToString("  ")
            add(ReviewKind.SshKey, match.range, match.value, detail)
        }
        PRIVATE_KEY_REGEX.findAll(text).forEach { add(ReviewKind.PrivateKey, it.range, it.value) }
        SSH_IMPORT_REGEX.findAll(text).forEach { add(ReviewKind.SshKeyImport, it.range, it.value) }
        HIDDEN_RUN_REGEX.findAll(text).forEach { match ->
            add(ReviewKind.HiddenText, match.range, match.value, match.value.map { it.hiddenCharLabel() }.distinct().joinToString(" "))
        }

        if (found.path.startsWith("templates.") || (found.key == "url" && found.path.startsWith("hostEvents"))) {
            SparkTemplateFilters.PLACEHOLDER.findAll(text).forEach { match ->
                val variable = match.groupValues[1]
                val filters = SparkTemplateFilters.parseChain(match.groupValues[2])
                if (filters.isEmpty() && variable !in fixedVariables && !variable.startsWith("Spark.")) {
                    add(ReviewKind.RawPlaceholder, match.range, match.value, variable)
                }
            }
        }

        if (multiLine) {
            IPV4_REGEX.findAll(text)
                .filter { ip -> urlRanges.none { ip.range.first in it } }
                .forEach { add(ReviewKind.IpAddress, it.range, it.value) }
            analyzeNotableBehavior(listOf(InspectableScript(found.path, text, inline = true)))
                .filter { it.category != SCRIPT_ENDPOINT_CATEGORY }
                .forEach { finding ->
                    val range = lines.lineRange(finding.line)
                    items += ReviewItem(ReviewKind.Behavior, finding.category, found.path, finding.line, finding.excerpt.take(EXCERPT_LIMIT).withHiddenCharsVisible(), finding.explanation)
                    spans += ReviewSpan(range.first, range.last + 1, ReviewSeverity.Amber)
                }
            documents += ReviewDocument(found.path, text, spans.sortedBy { it.start })
        }
    }

    private fun urlRole(path: String): UrlRole = when {
        path.startsWith("templates.") || path.startsWith("bootPatch") -> UrlRole.InFile
        path.startsWith("source.") -> UrlRole.Download
        path.endsWith(".fetch.url") -> UrlRole.FetchesData
        path.startsWith("hostEvents") || path.endsWith(".writeBaseUrl") -> UrlRole.SendsData
        path.endsWith(".readBaseUrl") -> UrlRole.ReadsData
        path == "metadata.homepage" -> UrlRole.Homepage
        else -> UrlRole.InText
    }
}

/** `SHA256:…` as `ssh-keygen -l` prints it, so a person can match the key to one they know. */
private fun sshFingerprint(blob: String): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(blob))
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}.getOrNull()

private const val EXCERPT_LIMIT = 240

private class LineIndex(private val text: String) {
    private val starts: List<Int> = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    val count: Int get() = starts.size

    fun lineOf(offset: Int): Int {
        val found = starts.binarySearch(offset)
        return (if (found >= 0) found else -found - 2) + 1
    }

    /** The line's characters, without its line break. */
    fun lineRange(line: Int): IntRange {
        val start = starts[line - 1]
        val end = starts.getOrNull(line)?.minus(1) ?: text.length
        return start until end
    }

    fun excerptAt(offset: Int): String {
        val line = text.substring(lineRange(lineOf(offset))).trim()
        val clipped = if (line.length > EXCERPT_LIMIT) line.take(EXCERPT_LIMIT) + "…" else line
        return clipped.withHiddenCharsVisible()
    }
}
