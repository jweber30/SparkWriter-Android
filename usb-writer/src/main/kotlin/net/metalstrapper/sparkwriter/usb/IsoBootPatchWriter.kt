package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.BootPatchTemplatePatch
import net.metalstrapper.sparkwriter.core.BootPatchTemplatePlan
import net.metalstrapper.sparkwriter.core.BootPlaceholderPatchSpec
import net.metalstrapper.sparkwriter.core.BootScriptPatchSpec
import net.metalstrapper.sparkwriter.core.BootTextPatchSpec
import java.security.MessageDigest
import java.util.Base64

private const val MAILROOM_RENDEZVOUS_PREFIX_HTTP =
    "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/"
private const val MAILROOM_RENDEZVOUS_PREFIX_HTTPS =
    "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/"
private const val RENDEZVOUS_PATH = "netboot/v1/rendezvous/"

internal data class IsoFileExtent(
    val path: String,
    val byteOffset: Long,
    val byteLength: Int,
    val storageByteLength: Int = byteLength.roundUpToIsoSector(),
    val directoryRecordOffsets: List<Long> = emptyList(),
)

private data class PlaceholderMatch(
    val offset: Int,
    val size: Int,
)

private data class BootConfigFilePatchGroup(
    val extent: IsoFileExtent,
    val textPatches: MutableList<BootTextPatchSpec> = mutableListOf(),
    val kernelArgPatches: MutableList<BootKernelArgPatchSpec> = mutableListOf(),
)

data class IsoByteRangePatch(
    val offset: Long,
    val bytes: ByteArray,
    val description: String,
)

object IsoBootPatchWriter {
    private const val SECTOR_SIZE = 2048
    private const val PRIMARY_VOLUME_DESCRIPTOR = 1
    private const val SUPPLEMENTARY_VOLUME_DESCRIPTOR = 2
    private const val VOLUME_DESCRIPTOR_SET_TERMINATOR = 255
    internal fun findCandidateExtent(
        plan: BootPatchPlan,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): IsoFileExtent {
        if (plan.filesystem != "iso9660") {
            throw WriteRefusedException("Unsupported boot patch filesystem: ${plan.filesystem}")
        }
        val roots = readRootDirectoryRecords(readBytes)
        return findRequiredCandidateExtent(plan.candidatePaths, roots, readBytes)
    }

    fun buildPatches(
        plan: BootPatchPlan,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): List<IsoByteRangePatch> {
        if (plan.filesystem != "iso9660") {
            throw WriteRefusedException("Unsupported boot patch filesystem: ${plan.filesystem}")
        }
        if (plan.encoding != "utf-8") {
            throw WriteRefusedException("Unsupported boot patch encoding: ${plan.encoding}")
        }
        if (
            plan.textPatches.isEmpty() &&
            plan.kernelArgPatches.isEmpty() &&
            plan.scriptPatches.isEmpty() &&
            plan.placeholderPatches.isEmpty()
        ) {
            val extent = findCandidateExtent(plan, readBytes)
            val existing = readBytes(extent.byteOffset, extent.byteLength)
            return listOf(
                IsoByteRangePatch(
                    offset = extent.byteOffset,
                    bytes = replacementBytes(plan, extent, existing),
                    description = extent.path,
                ),
            )
        }

        val roots = readRootDirectoryRecords(readBytes)
        val scriptPatches = plan.scriptPatches.flatMap { patch ->
            buildScriptPatches(patch, roots, readBytes)
        }
        val placeholderPatches = plan.placeholderPatches.map { patch ->
            buildPlaceholderPatch(patch, roots, readBytes)
        }.filterNotNull()
        val filePatchGroups = linkedMapOf<String, BootConfigFilePatchGroup>()
        plan.textPatches.forEach { patch ->
            val extent = findRequiredCandidateExtent(patch.candidatePaths, roots, readBytes)
            filePatchGroups.getOrPut(extent.path) { BootConfigFilePatchGroup(extent) }.textPatches += patch
        }
        plan.kernelArgPatches.forEach { patch ->
            val extent = findRequiredCandidateExtent(patch.candidatePaths, roots, readBytes)
            filePatchGroups.getOrPut(extent.path) { BootConfigFilePatchGroup(extent) }.kernelArgPatches += patch
        }

        val configPatches = filePatchGroups.values.flatMap { group ->
            val extent = group.extent
            val existing = readBytes(extent.byteOffset, extent.storageByteLength)
            val originalText = existing.copyOfRange(0, extent.byteLength).toString(Charsets.UTF_8)
            val textPatched = group.textPatches.fold(originalText) { text, patch ->
                applyTextPatch(text, patch, extent.path)
            }
            val patchedText = group.kernelArgPatches.fold(textPatched) { text, patch ->
                applyKernelArgPatch(text, patch, extent.path)
            }
            val patched = patchedText.toByteArray(Charsets.UTF_8)
            if (patched.size > extent.storageByteLength) {
                throw WriteRefusedException("Boot patch replacement is larger than allocated space for ${extent.path}")
            }
            val content = existing.copyOf()
            patched.copyInto(content)
            content.fill(0, patched.size, content.size)

            buildList {
                add(IsoByteRangePatch(extent.byteOffset, content, extent.path))
                if (patched.size != extent.byteLength) {
                    addDirectoryLengthPatches(extent, patched.size)
                }
            }
        }
        return scriptPatches + placeholderPatches + configPatches
    }

    fun buildTemplatePlan(
        signature: String,
        plan: BootPatchPlan,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): BootPatchTemplatePlan {
        val patches = buildPatches(plan, readBytes).map { patch ->
            val existing = readBytes(patch.offset, patch.bytes.size)
            BootPatchTemplatePatch(
                offset = patch.offset,
                byteLength = patch.bytes.size,
                expectedSha256 = existing.sha256(),
                byteTemplateBase64 = Base64.getEncoder().encodeToString(patch.bytes),
                variableSlots = emptyList(),
                description = patch.description,
            )
        }
        return BootPatchTemplatePlan(signature = signature, patches = patches)
    }

    fun materializeTemplatePatches(
        template: BootPatchTemplatePlan,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): List<IsoByteRangePatch> {
        return template.patches.map { patch ->
            val existing = readBytes(patch.offset, patch.byteLength)
            if (existing.sha256() != patch.expectedSha256) {
                throw WriteRefusedException("Cached boot patch template is stale for ${patch.description}")
            }
            val bytes = Base64.getDecoder().decode(patch.byteTemplateBase64)
            if (bytes.size != patch.byteLength) {
                throw WriteRefusedException("Cached boot patch template length mismatch for ${patch.description}")
            }
            IsoByteRangePatch(patch.offset, bytes, patch.description)
        }
    }

    internal fun replacementBytes(plan: BootPatchPlan, extent: IsoFileExtent, existing: ByteArray): ByteArray {
        if (existing.size != extent.byteLength) {
            throw WriteRefusedException("Boot config read length mismatch for ${extent.path}")
        }
        if (plan.encoding != "utf-8") {
            throw WriteRefusedException("Unsupported boot patch encoding: ${plan.encoding}")
        }

        val text = existing.toString(Charsets.UTF_8).trimEnd(' ', '\u0000')
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.splitToLines()
        val matches = lines.withIndex().filter { it.value == plan.lineMatch }
        when (matches.size) {
            0 -> throw WriteRefusedException("Boot patch line was not found in ${extent.path}")
            1 -> Unit
            else -> throw WriteRefusedException("Boot patch line matched multiple times in ${extent.path}")
        }

        val replacementLine = plan.lineMatch + " " + plan.appendArgs.joinToString(" ")
        val patchedText = lines.replaceLine(matches.single().index, replacementLine, text.endsWith("\n"), newline)
        val patched = patchedText.toByteArray(Charsets.UTF_8)
        if (patched.size > existing.size) {
            throw WriteRefusedException("Boot patch replacement is larger than ${extent.path}")
        }
        return ByteArray(existing.size) { index ->
            if (index < patched.size) patched[index] else ' '.code.toByte()
        }
    }

    private fun findRequiredCandidateExtent(
        candidatePaths: List<String>,
        roots: List<DirectoryRecord>,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): IsoFileExtent {
        val tried = mutableListOf<String>()
        candidatePaths.forEach { candidate ->
            tried += candidate
            val extent = findPath(candidate, roots, readBytes)
            if (extent != null) return extent
        }
        throw WriteRefusedException("No boot config candidate found: ${tried.joinToString()}")
    }

    private fun applyKernelArgPatch(text: String, patch: BootKernelArgPatchSpec, path: String): String {
        return when (patch.configFamily) {
            "grub" -> patchGrub(text, patch, path)
            "isolinux", "syslinux" -> patchSyslinux(text, patch, path)
            else -> throw WriteRefusedException("Unsupported boot patch config family: ${patch.configFamily}")
        }
    }

    private fun applyTextPatch(text: String, patch: BootTextPatchSpec, path: String): String {
        if (patch.kind != "replace_once") {
            throw WriteRefusedException("Unsupported boot text patch kind: ${patch.kind}")
        }
        val first = text.indexOf(patch.find)
        if (first < 0) {
            throw WriteRefusedException("Boot text patch was not found in $path")
        }
        if (text.indexOf(patch.find, first + patch.find.length) >= 0) {
            throw WriteRefusedException("Boot text patch matched multiple times in $path")
        }
        return text.replaceRange(first, first + patch.find.length, patch.replace)
    }

    private fun buildScriptPatches(
        patch: BootScriptPatchSpec,
        roots: List<DirectoryRecord>,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): List<IsoByteRangePatch> {
        if (patch.kind != "netboot_xyz_autoexec") {
            throw WriteRefusedException("Unsupported boot script patch kind: ${patch.kind}")
        }
        val script = patch.script.toByteArray(Charsets.UTF_8)
        val isoScriptExtent = findRequiredCandidateExtent(patch.candidatePaths, roots, readBytes)
        val isoScriptExisting = readBytes(isoScriptExtent.byteOffset, isoScriptExtent.storageByteLength)
        val isoScriptPatch = replaceIsoFileBytes(isoScriptExtent, isoScriptExisting, script)

        val espImagePath = patch.espImagePath ?: throw WriteRefusedException("Boot script patch is missing ESP image path")
        val espScriptPath = patch.espScriptPath ?: throw WriteRefusedException("Boot script patch is missing ESP script path")
        val espExtent = findRequiredCandidateExtent(listOf(espImagePath), roots, readBytes)
        val espImage = readBytes(espExtent.byteOffset, espExtent.byteLength)
        val patchedEsp = FatBootScriptPatcher.replaceRootFile(espImage, espScriptPath, script)

        return isoScriptPatch + IsoByteRangePatch(
            offset = espExtent.byteOffset,
            bytes = patchedEsp,
            description = "$espImagePath:$espScriptPath",
        )
    }

    private fun buildPlaceholderPatch(
        patch: BootPlaceholderPatchSpec,
        roots: List<DirectoryRecord>,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): IsoByteRangePatch? {
        if (patch.encoding !in setOf("ascii", "utf-8")) {
            throw WriteRefusedException("Unsupported boot placeholder patch encoding: ${patch.encoding}")
        }
        if (patch.padding != "space") {
            throw WriteRefusedException("Unsupported boot placeholder patch padding: ${patch.padding}")
        }
        val placeholder = patch.placeholder.toPlaceholderBytes(patch.encoding, "placeholder")
        val replacement = patch.replacement.toPlaceholderBytes(patch.encoding, "replacement")
        val canCompactRendezvousReplacement = !patch.placeholder.contains("/$RENDEZVOUS_PATH")
        val compactReplacement = if (canCompactRendezvousReplacement) {
            patch.replacement.compactMailroomRendezvousSlot()
        } else {
            null
        }
        if (placeholder.isEmpty()) {
            throw WriteRefusedException("Boot placeholder patch requires placeholder")
        }

        val extent = findRequiredCandidateExtent(patch.candidatePaths, roots, readBytes)
        val existing = readBytes(extent.byteOffset, extent.byteLength)
        val finalReplacement = if (replacement.size > placeholder.size) {
            compactReplacement ?: replacement
        } else {
            replacement
        }

        if (finalReplacement.size > placeholder.size) {
            val label = patch.extractRendezvousLabel()
            val candidateSummary = if (patch.candidatePaths.isNotEmpty()) {
                " (candidates: ${patch.candidatePaths.joinToString()})"
            } else {
                ""
            }
            val labelMessage = label?.let { " (label=$it)" } ?: ""
            throw WriteRefusedException(
                "Boot placeholder patch replacement exceeds placeholder length$labelMessage$candidateSummary",
            )
        }

        val matches = existing.findPlaceholderMatches(placeholder)
            .ifEmpty {
                patch.placeholder.toSwappedMailroomScheme()
                    ?.let { swapped ->
                        swapped.toPlaceholderBytes(patch.encoding, "placeholder").let(existing::findPlaceholderMatches)
                    } ?: emptyList()
            }
            .ifEmpty {
                sequenceOf(patch.placeholder, patch.placeholder.toSwappedMailroomScheme())
                    .filterNotNull()
                    .flatMap { existing.findRendezvousPlaceholderMatches(it) }
                    .toList()
            }
        when (matches.size) {
            0 -> throw WriteRefusedException("Boot placeholder was not found in ${extent.path}")
            1 -> Unit
            else -> throw WriteRefusedException("Boot placeholder matched multiple times in ${extent.path}")
        }

        val match = matches.single()
        if (finalReplacement.size > match.size) {
            val label = patch.extractRendezvousLabel()
            val candidateSummary = if (patch.candidatePaths.isNotEmpty()) {
                " (candidates: ${patch.candidatePaths.joinToString()})"
            } else {
                ""
            }
            val labelMessage = label?.let { " (label=$it)" } ?: ""
            throw WriteRefusedException(
                "Boot placeholder patch replacement exceeds placeholder length$labelMessage$candidateSummary",
            )
        }
        val patchBytes = existing.quotedValuePatch(match.offset, match.size, finalReplacement)
            ?: ByteArray(match.size) { ' '.code.toByte() }.also { finalReplacement.copyInto(it) }
        return IsoByteRangePatch(
            offset = extent.byteOffset + match.offset,
            bytes = patchBytes,
            description = "${extent.path} placeholder",
        )
    }

    private fun ByteArray.quotedValuePatch(
        matchOffset: Int,
        placeholderSize: Int,
        replacement: ByteArray,
    ): ByteArray? {
        if (matchOffset <= 0) return null
        val quote = this[matchOffset - 1]
        if (quote != '"'.code.toByte() && quote != '\''.code.toByte()) return null
        val closingQuoteOffset = matchOffset + placeholderSize
        if (closingQuoteOffset >= size || this[closingQuoteOffset] != quote) return null

        return ByteArray(placeholderSize + 1) { ' '.code.toByte() }.also { padded ->
            replacement.copyInto(padded)
            padded[replacement.size] = quote
        }
    }

    private fun String.compactMailroomRendezvousSlot(): ByteArray? {
        val compacted = compactMailroomRendezvousReplacement() ?: return null
        return compacted.toAsciiBytes("replacement")
    }

    private fun String.compactMailroomRendezvousReplacement(): String? {
        return when {
            startsWith(MAILROOM_RENDEZVOUS_PREFIX_HTTP) -> removePrefix(MAILROOM_RENDEZVOUS_PREFIX_HTTP)
            startsWith(MAILROOM_RENDEZVOUS_PREFIX_HTTPS) -> removePrefix(MAILROOM_RENDEZVOUS_PREFIX_HTTPS)
            else -> null
        }
    }

    private fun BootPlaceholderPatchSpec.extractRendezvousLabel(): String? {
        return sequenceOf(placeholder, replacement).firstNotNullOfOrNull { text ->
            val marker = "/$RENDEZVOUS_PATH"
            val index = text.indexOf(marker)
            if (index < 0) return@firstNotNullOfOrNull null
            text.substring(index + marker.length).trim().ifBlank { null }?.takeIf { it.isNotBlank() }
        }
    }

    private fun replaceIsoFileBytes(
        extent: IsoFileExtent,
        existing: ByteArray,
        replacement: ByteArray,
    ): List<IsoByteRangePatch> {
        if (replacement.size > extent.storageByteLength) {
            throw WriteRefusedException("Boot script patch is larger than allocated space for ${extent.path}")
        }
        val content = existing.copyOf()
        replacement.copyInto(content)
        content.fill(0, replacement.size, content.size)
        return buildList {
            add(IsoByteRangePatch(extent.byteOffset, content, extent.path))
            if (replacement.size != extent.byteLength) {
                addDirectoryLengthPatches(extent, replacement.size)
            }
        }
    }

    private fun MutableList<IsoByteRangePatch>.addDirectoryLengthPatches(extent: IsoFileExtent, replacementSize: Int) {
        if (extent.directoryRecordOffsets.isEmpty()) {
            throw WriteRefusedException("Cannot update ISO9660 record length for ${extent.path}")
        }
        extent.directoryRecordOffsets.distinct().forEach { recordOffset ->
            add(
                IsoByteRangePatch(
                    offset = recordOffset + 10,
                    bytes = isoBothEndianInt(replacementSize),
                    description = "${extent.path} directory length",
                ),
            )
        }
    }

    private fun patchGrub(text: String, patch: BootKernelArgPatchSpec, path: String): String {
        val lines = text.splitToLines()
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val menuentry = patch.selector.menuentry
        val kernel = patch.selector.kernel ?: throw WriteRefusedException("GRUB boot patch selector is missing kernel")
        val requiredArgs = patch.selector.argsContains
        val matches = mutableListOf<Int>()
        val fallbackMatches = mutableListOf<Int>()

        var index = 0
        while (index < lines.size) {
            val title = lines[index].grubMenuentryTitle()
            if (title == null) {
                index++
                continue
            }
            val end = findGrubBlockEnd(lines, index)
            val blockKernelMatches = (index + 1 until end).filter {
                lines[it].grubKernelPath() == kernel && lines[it].grubKernelArgsContain(requiredArgs)
            }
            fallbackMatches += blockKernelMatches
            if (menuentry != null && title == menuentry) {
                matches += blockKernelMatches
            }
            index = end + 1
        }

        val patchLines = matches.ifEmpty { fallbackMatches.distinct() }
        if (requiredArgs.isEmpty()) {
            val lineIndex = singlePatchLine(patchLines, path)
            return lines.replaceLine(lineIndex, lines[lineIndex].patchGrubKernelArgs(patch), text.endsWith("\n"), newline)
        }
        if (patchLines.isEmpty()) {
            throw WriteRefusedException("Boot patch line was not found in $path for ${patch.describeGrubSelector()}")
        }
        val patchedText = lines.replaceLines(patchLines, text.endsWith("\n"), newline) { line ->
            line.patchGrubKernelArgs(patch)
        }
        return patchedText.removeLinesContaining(patch.removeLinesContaining, newline)
    }

    private fun patchSyslinux(text: String, patch: BootKernelArgPatchSpec, path: String): String {
        val lines = text.splitToLines()
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val label = patch.selector.label ?: throw WriteRefusedException("syslinux boot patch selector is missing label")
        val matches = mutableListOf<Int>()

        var index = 0
        while (index < lines.size) {
            val currentLabel = lines[index].syslinuxLabel()
            if (currentLabel == null) {
                index++
                continue
            }
            val end = generateSequence(index + 1) { it + 1 }
                .firstOrNull { it >= lines.size || lines[it].syslinuxLabel() != null }
                ?: lines.size
            if (currentLabel == label) {
                matches += (index + 1 until end).filter { lines[it].trimStart().startsWith("append ") }
            }
            index = end
        }

        val lineIndex = singlePatchLine(matches, path)
        return lines.replaceLine(lineIndex, lines[lineIndex].patchSyslinuxAppendArgs(patch), text.endsWith("\n"), newline)
    }

    private fun singlePatchLine(matches: List<Int>, path: String): Int = when (matches.size) {
        0 -> throw WriteRefusedException("Boot patch line was not found in $path")
        1 -> matches.single()
        else -> throw WriteRefusedException("Boot patch line matched multiple times in $path")
    }

    private fun BootKernelArgPatchSpec.describeGrubSelector(): String {
        return buildList {
            selector.menuentry?.let { add("menuentry=$it") }
            selector.kernel?.let { add("kernel=$it") }
            if (selector.argsContains.isNotEmpty()) add("args_contains=${selector.argsContains.joinToString(" ")}")
            if (ensureArgs.isNotEmpty()) add("ensure_args=${ensureArgs.joinToString(" ")}")
            if (removeArgs.isNotEmpty()) add("remove_args=${removeArgs.joinToString(" ")}")
            if (removeLinesContaining.isNotEmpty()) add("remove_lines_containing=${removeLinesContaining.joinToString(" | ")}")
        }.joinToString(prefix = "selector[", postfix = "]")
    }

    private fun findGrubBlockEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        for (index in start until lines.size) {
            depth += lines[index].count { it == '{' }
            depth -= lines[index].count { it == '}' }
            if (index > start && depth <= 0) return index
        }
        throw WriteRefusedException("GRUB menuentry block is unterminated")
    }

    private fun String.grubMenuentryTitle(): String? {
        val match = Regex("""^\s*menuentry\b.*?(['"])(.*?)\1\s*\{""").find(this) ?: return null
        return match.groupValues[2]
    }

    private fun String.grubKernelPath(): String? {
        val tokens = trim().split(Regex("""\s+"""))
        if (tokens.size < 2 || tokens[0] !in setOf("linux", "linuxefi")) return null
        return tokens[1]
    }

    private fun String.grubKernelArgsContain(requiredArgs: List<String>): Boolean {
        if (requiredArgs.isEmpty()) return true
        val match = Regex("""^\s*\S+\s+\S+\s+(.*)$""").find(this) ?: return false
        val args = match.groupValues[1].trim().splitArgs()
        return requiredArgs.all { it in args }
    }

    private fun String.syslinuxLabel(): String? {
        val match = Regex("""^\s*label\s+(\S+)\s*$""").find(this) ?: return null
        return match.groupValues[1]
    }

    private fun String.patchGrubKernelArgs(patch: BootKernelArgPatchSpec): String {
        val match = Regex("""^(\s*)(\S+)(\s+)(\S+)(.*)$""").find(this)
            ?: throw WriteRefusedException("GRUB kernel line is malformed")
        val args = match.groupValues[5].trim().splitArgs()
        val trailing = if (endsWith(" ")) " " else ""
        return match.groupValues[1] +
            match.groupValues[2] +
            match.groupValues[3] +
            match.groupValues[4] +
            " " +
            patchArgs(args, patch.ensureArgs, patch.removeArgs, patch.insertBefore).joinToString(" ") +
            trailing
    }

    private fun String.patchSyslinuxAppendArgs(patch: BootKernelArgPatchSpec): String {
        val match = Regex("""^(\s*append\s+)(.*)$""").find(this)
            ?: throw WriteRefusedException("syslinux append line is malformed")
        val trailing = if (endsWith(" ")) " " else ""
        return match.groupValues[1] +
            patchArgs(match.groupValues[2].trim().splitArgs(), patch.ensureArgs, patch.removeArgs, patch.insertBefore).joinToString(" ") +
            trailing
    }

    private fun patchArgs(args: List<String>, ensureArgs: List<String>, removeArgs: List<String>, insertBefore: String): List<String> {
        val separatorIndex = args.indexOf(insertBefore).let { if (it < 0) args.size else it }
        val before = args.take(separatorIndex)
        val after = args.drop(separatorIndex)
        val ensureKeys = ensureArgs.map { it.argKey() }.toSet()
        val removeValues = removeArgs.toSet()
        val removeKeys = removeArgs.filter { "=" in it }.map { it.argKey() }.toSet()
        return ensureArgs + before.filterNot {
            it.argKey() in ensureKeys || it.argKey() in removeKeys || it in removeValues
        } + after.filterNot { it in removeValues }
    }

    private fun readRootDirectoryRecords(readBytes: (offset: Long, length: Int) -> ByteArray): List<DirectoryRecord> {
        val roots = mutableListOf<DirectoryRecord>()
        var sector = 16
        while (sector < 256) {
            val descriptor = readBytes(sector.toLong() * SECTOR_SIZE, SECTOR_SIZE)
            if (descriptor[0].toInt() and 0xff == VOLUME_DESCRIPTOR_SET_TERMINATOR) break
            val descriptorType = descriptor[0].toInt() and 0xff
            if (descriptor.copyOfRange(1, 6).toString(Charsets.US_ASCII) == "CD001" &&
                descriptorType in setOf(PRIMARY_VOLUME_DESCRIPTOR, SUPPLEMENTARY_VOLUME_DESCRIPTOR)
            ) {
                val encoding = if (descriptorType == SUPPLEMENTARY_VOLUME_DESCRIPTOR) {
                    IsoNameEncoding.UTF16_BE
                } else {
                    IsoNameEncoding.US_ASCII
                }
                roots += parseDirectoryRecord(descriptor, 156, sector.toLong() * SECTOR_SIZE + 156, encoding)
                    ?: throw WriteRefusedException("ISO9660 root directory record is invalid")
            }
            sector++
        }
        if (roots.isNotEmpty()) return roots
        throw WriteRefusedException("ISO9660 primary volume descriptor was not found")
    }

    private fun findPath(
        path: String,
        roots: List<DirectoryRecord>,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): IsoFileExtent? {
        val matches = roots.mapNotNull { root -> findPath(path, root, readBytes) }
        if (matches.isEmpty()) return null
        val first = matches.first()
        matches.drop(1).forEach { match ->
            if (match.byteOffset != first.byteOffset || match.byteLength != first.byteLength) {
                throw WriteRefusedException("ISO9660 directory records disagree for $path")
            }
        }
        return first.copy(directoryRecordOffsets = matches.flatMap { it.directoryRecordOffsets })
    }

    private fun findPath(
        path: String,
        root: DirectoryRecord,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): IsoFileExtent? {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        var current = root
        parts.forEachIndexed { index, part ->
            if (!current.directory) return null
            val entries = readDirectory(current, readBytes)
            val next = entries.firstOrNull { it.matchesName(part.isoName()) } ?: return null
            if (index == parts.lastIndex) {
                if (next.directory) return null
                return IsoFileExtent(
                    path = path,
                    byteOffset = next.byteOffset,
                    byteLength = next.byteLength,
                    storageByteLength = next.byteLength.roundUpToIsoSector(),
                    directoryRecordOffsets = listOf(next.recordByteOffset),
                )
            }
            current = next
        }
        return null
    }

    private fun readDirectory(
        directory: DirectoryRecord,
        readBytes: (offset: Long, length: Int) -> ByteArray,
    ): List<DirectoryRecord> {
        val bytes = readBytes(directory.byteOffset, directory.byteLength)
        val records = mutableListOf<DirectoryRecord>()
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xff
            if (length == 0) {
                offset = ((offset / SECTOR_SIZE) + 1) * SECTOR_SIZE
                continue
            }
            val record = parseDirectoryRecord(bytes, offset, directory.byteOffset + offset, directory.nameEncoding)
            if (record != null && record.name != "\u0000" && record.name != "\u0001") {
                records += record
            }
            offset += length
        }
        return records
    }

    private fun parseDirectoryRecord(
        bytes: ByteArray,
        offset: Int,
        recordByteOffset: Long,
        nameEncoding: IsoNameEncoding,
    ): DirectoryRecord? {
        if (offset !in bytes.indices) return null
        val length = bytes[offset].toInt() and 0xff
        if (length < 34 || offset + length > bytes.size) return null
        val extentLba = bytes.readLe32(offset + 2)
        val dataLength = bytes.readLe32(offset + 10)
        val flags = bytes[offset + 25].toInt() and 0xff
        val nameLength = bytes[offset + 32].toInt() and 0xff
        if (offset + 33 + nameLength > bytes.size) return null
        val nameBytes = bytes.copyOfRange(offset + 33, offset + 33 + nameLength)
        val specialDirectoryName = nameBytes.size == 1 && nameBytes[0] in listOf(0.toByte(), 1.toByte())
        val rawName = if (specialDirectoryName) {
            nameBytes.toString(Charsets.US_ASCII)
        } else {
            when (nameEncoding) {
                IsoNameEncoding.US_ASCII -> nameBytes.toString(Charsets.US_ASCII)
                IsoNameEncoding.UTF16_BE -> nameBytes.toString(Charsets.UTF_16BE)
            }
        }
        val canonicalName = rawName.substringBefore(';').substringBefore(':')
        val alternateNames = if (specialDirectoryName) {
            emptyList()
        } else {
            parseRockRidgeAlternateNames(bytes, offset, length, nameLength)
        }
        return DirectoryRecord(
            name = canonicalName,
            alternateNames = alternateNames,
            byteOffset = extentLba * SECTOR_SIZE.toLong(),
            byteLength = dataLength.toIntOrNull() ?: throw WriteRefusedException("ISO9660 file is too large"),
            directory = flags and 0x02 != 0,
            recordByteOffset = recordByteOffset,
            nameEncoding = nameEncoding,
        )
    }

    private fun parseRockRidgeAlternateNames(
        bytes: ByteArray,
        offset: Int,
        recordLength: Int,
        nameLength: Int,
    ): List<String> {
        val systemUseOffset = offset + 33 + nameLength + if (nameLength % 2 == 0) 1 else 0
        val recordEnd = offset + recordLength
        if (systemUseOffset + 5 > recordEnd) return emptyList()

        val names = mutableListOf<String>()
        var cursor = systemUseOffset
        while (cursor + 4 <= recordEnd) {
            val signature = bytes.copyOfRange(cursor, cursor + 2).toString(Charsets.US_ASCII)
            val entryLength = bytes[cursor + 2].toInt() and 0xff
            if (entryLength < 4 || cursor + entryLength > recordEnd) break
            if (signature == "NM" && entryLength >= 5) {
                val flags = bytes[cursor + 4].toInt() and 0xff
                val nameStart = cursor + 5
                val nameEnd = cursor + entryLength
                val name = bytes.copyOfRange(nameStart, nameEnd).toString(Charsets.UTF_8)
                if (flags and ROCK_RIDGE_NM_CONTINUE == 0 && name.isNotBlank()) {
                    names += name
                }
            }
            cursor += entryLength
        }
        return names
    }

    private fun String.isoName(): String = substringAfterLast('/').substringBefore(';')

    private fun String.splitToLines(): List<String> {
        val normalized = replace("\r\n", "\n")
        return if (normalized.endsWith("\n")) {
            normalized.dropLast(1).split('\n')
        } else {
            normalized.split('\n')
        }
    }

    private fun List<String>.replaceLine(
        lineIndex: Int,
        replacement: String,
        terminalNewline: Boolean,
        newline: String,
    ): String {
        return buildString {
            this@replaceLine.forEachIndexed { index, line ->
                append(if (index == lineIndex) replacement else line)
                if (index < this@replaceLine.lastIndex || terminalNewline) append(newline)
            }
        }
    }

    private fun List<String>.replaceLines(
        lineIndexes: Collection<Int>,
        terminalNewline: Boolean,
        newline: String,
        replacement: (String) -> String,
    ): String {
        val selected = lineIndexes.toSet()
        return buildString {
            this@replaceLines.forEachIndexed { index, line ->
                append(if (index in selected) replacement(line) else line)
                if (index < this@replaceLines.lastIndex || terminalNewline) append(newline)
            }
        }
    }

    private fun String.argKey(): String = substringBefore('=')

    private fun String.splitArgs(): List<String> = if (isBlank()) emptyList() else split(Regex("""\s+"""))

    private fun String.removeLinesContaining(markers: List<String>, newline: String): String {
        if (markers.isEmpty()) return this
        val terminalNewline = endsWith("\n")
        val filtered = splitToLines().filterNot { line -> markers.any(line::contains) }
        return buildString {
            filtered.forEachIndexed { index, line ->
                append(line)
                if (index < filtered.lastIndex || terminalNewline) append(newline)
            }
        }
    }

    private data class DirectoryRecord(
        val name: String,
        val alternateNames: List<String>,
        val byteOffset: Long,
        val byteLength: Int,
        val directory: Boolean,
        val recordByteOffset: Long,
        val nameEncoding: IsoNameEncoding,
    ) {
        fun matchesName(candidate: String): Boolean {
            return name.equals(candidate, ignoreCase = true) ||
                alternateNames.any { it.equals(candidate, ignoreCase = true) }
        }
    }

    private enum class IsoNameEncoding { US_ASCII, UTF16_BE }

    private const val ROCK_RIDGE_NM_CONTINUE = 0x01
}

private fun String.toAsciiBytes(owner: String): ByteArray {
    if (any { it.code > 0x7f }) {
        throw WriteRefusedException("Boot placeholder patch $owner must be ASCII")
    }
    return toByteArray(Charsets.US_ASCII)
}

private fun String.toPlaceholderBytes(encoding: String, owner: String): ByteArray = when (encoding) {
    "ascii" -> toAsciiBytes(owner)
    "utf-8" -> toByteArray(Charsets.UTF_8)
    else -> throw WriteRefusedException("Unsupported boot placeholder patch encoding: $encoding")
}

private fun ByteArray.findAll(needle: ByteArray): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val matches = mutableListOf<Int>()
    var index = 0
    while (index <= size - needle.size) {
        var matched = true
        for (needleIndex in needle.indices) {
            if (this[index + needleIndex] != needle[needleIndex]) {
                matched = false
                break
            }
        }
        if (matched) matches += index
        index++
    }
    return matches
}

private fun ByteArray.findPlaceholderMatches(placeholder: ByteArray): List<PlaceholderMatch> {
    return findAll(placeholder).map { matchOffset -> PlaceholderMatch(matchOffset, placeholder.size) }
}

private fun ByteArray.findRendezvousPlaceholderMatches(patchPlaceholder: String): List<PlaceholderMatch> {
    val prefix = patchPlaceholder.rendezvousPrefixSlot() ?: return emptyList()
    val prefixBytes = prefix.toAsciiBytes("placeholder")
    return findAll(prefixBytes).mapNotNull { prefixOffset ->
        var end = prefixOffset + prefixBytes.size
        while (end < size && get(end).isRendezvousPlaceholderSlotChar()) {
            end++
        }
        if (end <= prefixOffset + prefixBytes.size) return@mapNotNull null
        PlaceholderMatch(prefixOffset, end - prefixOffset)
    }
}

private fun String.rendezvousPrefixSlot(): String? {
    val markerIndex = indexOf("/$RENDEZVOUS_PATH")
    if (markerIndex < 0) return null
    val slotStart = markerIndex + 1 + RENDEZVOUS_PATH.length
    val tail = substring(slotStart)
    val slot = tail.substringBefore('/')
    if (slot.isEmpty() || !slot.all { c -> c == '0' }) return null
    return substring(0, slotStart)
}

private fun String.toSwappedMailroomScheme(): String? = when {
    startsWith(MAILROOM_RENDEZVOUS_PREFIX_HTTP) -> replace(MAILROOM_RENDEZVOUS_PREFIX_HTTP, MAILROOM_RENDEZVOUS_PREFIX_HTTPS, ignoreCase = false)
    startsWith(MAILROOM_RENDEZVOUS_PREFIX_HTTPS) -> replace(MAILROOM_RENDEZVOUS_PREFIX_HTTPS, MAILROOM_RENDEZVOUS_PREFIX_HTTP, ignoreCase = false)
    else -> null
}

private fun Byte.isRendezvousPlaceholderSlotChar(): Boolean {
    return when (toInt() and 0xff) {
        in 'A'.code..'Z'.code,
        in 'a'.code..'z'.code,
        in '0'.code..'9'.code,
        '-'.code,
        '_'.code,
        '/'.code -> true
        else -> false
    }
}

private fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}

private object FatBootScriptPatcher {
    fun replaceRootFile(image: ByteArray, path: String, replacement: ByteArray): ByteArray {
        val name = path.trim('/').uppercase()
        if (name.isBlank() || name.contains('/')) {
            throw WriteRefusedException("Only root-level ESP boot script paths are supported: $path")
        }
        val bps = image.readLe16(11)
        val sectorsPerCluster = image[13].toInt() and 0xff
        val reservedSectors = image.readLe16(14)
        val fatCount = image[16].toInt() and 0xff
        val rootEntries = image.readLe16(17)
        val totalSectors = image.readLe16(19).takeIf { it > 0 } ?: image.readLe32(32).toIntOrNull()
            ?: throw WriteRefusedException("ESP FAT image is too large")
        val fatSectors = image.readLe16(22).takeIf { it > 0 } ?: image.readLe32(36).toIntOrNull()
            ?: throw WriteRefusedException("ESP FAT image is too large")
        if (bps <= 0 || sectorsPerCluster <= 0 || fatCount <= 0 || rootEntries <= 0) {
            throw WriteRefusedException("ESP FAT image has unsupported geometry")
        }

        val rootDirSectors = ((rootEntries * 32) + bps - 1) / bps
        val firstRootSector = reservedSectors + fatCount * fatSectors
        val firstDataSector = firstRootSector + rootDirSectors
        val dataSectors = totalSectors - firstDataSector
        val clusterCount = dataSectors / sectorsPerCluster
        val fatType = if (clusterCount < 4085) FatType.FAT12 else FatType.FAT16
        val rootOffset = firstRootSector * bps
        val rootLength = rootEntries * 32
        val entryOffset = findRootEntry(image, rootOffset, rootLength, name)
        val firstCluster = image.readLe16(entryOffset + 26)
        if (firstCluster < 2) {
            throw WriteRefusedException("ESP boot script has invalid cluster chain")
        }

        val fatOffset = reservedSectors * bps
        val clusterSize = sectorsPerCluster * bps
        val clusters = clusterChain(image, fatOffset, firstCluster, fatType)
        val capacity = clusters.size * clusterSize
        if (replacement.size > capacity) {
            throw WriteRefusedException("ESP boot script patch is larger than the existing cluster chain")
        }

        val patched = image.copyOf()
        val padded = ByteArray(capacity)
        replacement.copyInto(padded)
        clusters.forEachIndexed { index, cluster ->
            val offset = (firstDataSector + (cluster - 2) * sectorsPerCluster) * bps
            padded.copyInto(
                destination = patched,
                destinationOffset = offset,
                startIndex = index * clusterSize,
                endIndex = (index + 1) * clusterSize,
            )
        }
        patched.putLe32(entryOffset + 28, replacement.size)
        return patched
    }

    private fun findRootEntry(image: ByteArray, rootOffset: Int, rootLength: Int, pathName: String): Int {
        val targetShortName = fatShortNameOrNull(pathName)
        val targetLongName = pathName.trim('/').substringAfterLast('/')
        var offset = rootOffset
        val end = rootOffset + rootLength
        val longNameEntries = mutableListOf<ByteArray>()
        while (offset + 32 <= end) {
            val first = image[offset].toInt() and 0xff
            if (first == 0x00) break
            val attr = image[offset + 11].toInt() and 0xff
            if (first == 0xe5) {
                longNameEntries.clear()
                offset += 32
                continue
            }
            if (attr == 0x0f) {
                longNameEntries += image.copyOfRange(offset, offset + 32)
                offset += 32
                continue
            }
            if (attr and 0x18 == 0) {
                val rawName = image.copyOfRange(offset, offset + 11).toString(Charsets.US_ASCII)
                val longName = longNameEntries.toLongFileName()
                if (rawName == targetShortName || longName?.equals(targetLongName, ignoreCase = true) == true) return offset
            }
            longNameEntries.clear()
            offset += 32
        }
        throw WriteRefusedException("ESP boot script was not found: /$pathName")
    }

    private fun fatShortNameOrNull(pathName: String): String? {
        val dot = pathName.lastIndexOf('.')
        val base = if (dot < 0) pathName else pathName.substring(0, dot)
        val ext = if (dot < 0) "" else pathName.substring(dot + 1)
        if (base.isBlank() || base.length > 8 || ext.length > 3) {
            return null
        }
        return base.padEnd(8, ' ') + ext.padEnd(3, ' ')
    }

    private fun List<ByteArray>.toLongFileName(): String? {
        if (isEmpty()) return null
        return sortedBy { it[0].toInt() and 0x1f }
            .flatMap { entry ->
                listOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30).map { offset ->
                    entry.readLe16(offset)
                }
            }
            .takeWhile { it != 0x0000 && it != 0xffff }
            .map { it.toChar() }
            .joinToString("")
    }

    private fun clusterChain(image: ByteArray, fatOffset: Int, firstCluster: Int, fatType: FatType): List<Int> {
        val clusters = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        var cluster = firstCluster
        while (true) {
            if (!seen.add(cluster)) throw WriteRefusedException("ESP boot script FAT chain loops")
            clusters += cluster
            val next = when (fatType) {
                FatType.FAT12 -> image.readFat12(fatOffset, cluster)
                FatType.FAT16 -> image.readLe16(fatOffset + cluster * 2)
            }
            val endOfChain = when (fatType) {
                FatType.FAT12 -> next >= 0x0ff8
                FatType.FAT16 -> next >= 0xfff8
            }
            if (endOfChain) return clusters
            if (next < 2) throw WriteRefusedException("ESP boot script FAT chain is invalid")
            cluster = next
        }
    }

    private fun ByteArray.readFat12(fatOffset: Int, cluster: Int): Int {
        val offset = fatOffset + cluster + (cluster / 2)
        val value = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
        return if (cluster and 1 == 0) value and 0x0fff else value shr 4
    }

    private enum class FatType { FAT12, FAT16 }
}

private fun ByteArray.readLe32(offset: Int): Long {
    return ((this[offset].toLong() and 0xff)) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)
}

private fun ByteArray.readLe16(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun Long.toIntOrNull(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null

private fun Int.roundUpToIsoSector(): Int = ((this + 2047) / 2048) * 2048

private fun isoBothEndianInt(value: Int): ByteArray {
    val bytes = ByteArray(8)
    bytes.putLe32(0, value)
    bytes[4] = (value shr 24).toByte()
    bytes[5] = (value shr 16).toByte()
    bytes[6] = (value shr 8).toByte()
    bytes[7] = value.toByte()
    return bytes
}

private fun ByteArray.putLe32(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
    this[offset + 2] = (value shr 16).toByte()
    this[offset + 3] = (value shr 24).toByte()
}
