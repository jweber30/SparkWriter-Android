package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.PackageFileRef
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.manifest.ManifestValidationException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal const val MAX_SPARKPLUG_BYTES = 100 * 1024
private const val MAX_SPARKPLUG_ENTRIES = 1024

internal enum class SparkPlugTrust { Bundled, LocalZip, RemoteHttps }

internal data class InspectableScript(val label: String, val text: String, val inline: Boolean)
internal data class NotableBehavior(
    val category: String,
    val explanation: String,
    val source: String,
    val line: Int,
    val excerpt: String,
)
internal data class PendingSparkPlugImport(
    val manifest: SparkManifest,
    val trust: SparkPlugTrust,
    val sourceLabel: String,
    val transferredBytes: Int,
    val expandedBytes: Int,
    val declaredFileCount: Int,
    val scripts: List<InspectableScript>,
    val findings: List<NotableBehavior>,
)

internal fun copyBoundedSparkPlug(input: InputStream, target: File): Int {
    var total = 0
    target.outputStream().use { output ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_SPARKPLUG_BYTES) throw ManifestValidationException("SparkPlug ZIP exceeds 100 KiB")
            output.write(buffer, 0, count)
        }
    }
    return total
}

internal fun inspectLocalSparkPlugZip(
    runtime: ManifestRuntime,
    zipFile: File,
    displayName: String,
    transferredBytes: Int = zipFile.length().toInt(),
): PendingSparkPlugImport = ZipFile(zipFile).use { zip ->
    val entries = zip.entries().asSequence().toList()
    if (entries.size > MAX_SPARKPLUG_ENTRIES) throw ManifestValidationException("SparkPlug ZIP has too many entries")
    val normalized = entries.map { validateEntryName(it) }
    if (normalized.size != normalized.toSet().size) throw ManifestValidationException("SparkPlug ZIP contains duplicate paths")
    val manifestEntry = entries.singleOrNull { !it.isDirectory && it.name == "manifest.json" }
        ?: throw ManifestValidationException("SparkPlug ZIP must contain one manifest.json at its root")

    var expanded = 0
    fun read(entry: ZipEntry): String {
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            throw ManifestValidationException("Unsupported or encrypted ZIP entry: ${entry.name}")
        }
        val bytes = zip.getInputStream(entry).use { stream ->
            val out = ArrayList<Byte>()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                expanded += count
                if (expanded > MAX_SPARKPLUG_BYTES) throw ManifestValidationException("Expanded SparkPlug exceeds 100 KiB")
                repeat(count) { out += buffer[it] }
            }
            out.toByteArray()
        }
        return strictUtf8(bytes, entry.name)
    }

    val root = runtime.parsePackageRoot(read(manifestEntry), "local-zip:$displayName", requireIntegrity = false)
    val declaredPaths = buildSet {
        add("manifest.json")
        root.documents.values.mapNotNullTo(this) { it.url?.localZipPath() }
        root.files.values.mapNotNullTo(this) { it.url?.localZipPath() }
    }
    val actualFiles = entries.filterNot { it.isDirectory }.map { it.name }.toSet()
    val undeclared = actualFiles - declaredPaths
    if (undeclared.isNotEmpty()) throw ManifestValidationException("ZIP contains undeclared files: ${undeclared.sorted().joinToString()}")
    val missing = declaredPaths - actualFiles
    if (missing.isNotEmpty()) throw ManifestValidationException("ZIP is missing declared files: ${missing.sorted().joinToString()}")

    val textByPath = actualFiles.associateWith { path ->
        if (path == "manifest.json") "" else read(zip.getEntry(path))
    }
    fun documentText(role: String): String {
        val document = root.document(role)
        return document.inline?.text ?: textByPath.getValue(document.url!!.localZipPath())
    }
    var manifest = runtime.parse(documentText("workflow"), documentText("image"), "local-zip:$displayName")
    val externalTemplates = root.files.mapNotNull { (key, ref) ->
        val templateId = ref.templateId(key) ?: return@mapNotNull null
        val text = ref.inline?.text ?: textByPath.getValue(ref.url!!.localZipPath())
        templateId to text
    }.toMap()
    manifest = runtime.withTemplates(manifest, externalTemplates)
    val scripts = inspectableScripts(manifest, root.files, textByPath)
    PendingSparkPlugImport(
        manifest, SparkPlugTrust.LocalZip, displayName, transferredBytes, expanded,
        actualFiles.size - 1, scripts, analyzeNotableBehavior(scripts),
    )
}

private fun validateEntryName(entry: ZipEntry): String {
    val name = entry.name
    if (name.isBlank() || '\\' in name || name.startsWith('/') || name.startsWith("~") ||
        name.split('/').any { it == ".." || it == "." || it.isBlank() && !entry.isDirectory }
    ) throw ManifestValidationException("Unsafe ZIP path: $name")
    if (!entry.isDirectory && name.substringAfterLast('.').lowercase() in setOf("zip", "jar", "apk")) {
        throw ManifestValidationException("Nested archives are not supported: $name")
    }
    return name.removeSuffix("/")
}

private fun String.localZipPath(): String {
    val value = removePrefix("./")
    if (value.contains("://") || value.startsWith("data:")) throw ManifestValidationException("Local ZIP references must be relative files")
    validateEntryName(ZipEntry(value))
    return value
}

internal fun strictUtf8(bytes: ByteArray, name: String): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) {
    throw ManifestValidationException("SparkPlug text file is not valid UTF-8: $name")
}

internal fun inspectableScripts(manifest: SparkManifest): List<InspectableScript> = inspectableScripts(manifest, emptyMap(), emptyMap())

private fun inspectableScripts(
    manifest: SparkManifest,
    files: Map<String, PackageFileRef>,
    textByPath: Map<String, String>,
): List<InspectableScript> = manifest.templates.mapNotNull { (id, text) ->
    val artifact = manifest.artifacts.values.firstOrNull { it.template == id }
    val scriptLike = artifact?.mediaType?.contains("shell", true) == true || id.contains("script", true) || text.startsWith("#!")
    if (!scriptLike) return@mapNotNull null
    val external = files.entries.firstOrNull { it.value.templateId(it.key) == id }?.value?.url?.localZipPath()
    InspectableScript(external ?: "inline template: $id", external?.let(textByPath::getValue) ?: text, external == null)
}

internal fun analyzeNotableBehavior(scripts: List<InspectableScript>): List<NotableBehavior> {
    val rules = listOf(
        Triple(Regex("(?i)(curl|wget).*(\\||>)\\s*(sh|bash)\\b"), "Download and execute", "Downloads content and passes it to a command interpreter."),
        Triple(Regex("(?i)(base64|openssl|gzip|xz|unzip).*([|].*(sh|bash)|eval)"), "Decode and execute", "Decodes or expands content for execution."),
        Triple(Regex("(?i)\\beval\\b"), "Dynamic execution", "Evaluates generated command text."),
        Triple(Regex("(?i)(sources\\.list|apt/sources|repository|\\brepo\\b)"), "Repository changes", "Changes operating-system software repositories."),
        Triple(Regex("(?i)\\b(apt-get|apt|dnf|yum|apk)\\s+(update|install|upgrade)\\b"), "Package management", "Updates package indexes or installs software."),
        Triple(Regex("(?i)(first.?boot|^#!.*sh|\\bsudo\\b)"), "Privileged startup", "Runs during startup or in a privileged shell context."),
        Triple(Regex("(?i)(tailscale\\s+up.*--ssh|sshd|systemctl.*ssh)"), "Remote access", "Enables or configures remote access."),
        Triple(Regex("(?i)(auth.?key|token|password|secret)"), "Credential use", "Uses a credential or secret value."),
        Triple(Regex("(?i)https?://[^\\s'\"<>]+"), "Script network endpoint", "Contacts a network endpoint from executable script content."),
    )
    return scripts.flatMap { script ->
        script.text.lineSequence().flatMapIndexed { index, line ->
            rules.asSequence().filter { it.first.containsMatchIn(line) }.map { rule ->
                NotableBehavior(rule.second, rule.third, script.label, index + 1, line.trim())
            }
        }.toList()
    }
}
