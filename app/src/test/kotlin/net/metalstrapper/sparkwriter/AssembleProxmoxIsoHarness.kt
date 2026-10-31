package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.usb.AssembledImageWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

/**
 * Headless harness: runs the app's real code path (workflow rendering, then
 * [AssembledImageWriter]) to turn a base MetalStrap Proxmox ISO into an assembled ISO,
 * so the result can be booted in a VM. It is skipped unless SPARKWRITER_BASE_ISO is set.
 *
 * Environment:
 *   SPARKWRITER_BASE_ISO     path to the published base ISO (required to run)
 *   SPARKWRITER_OUT_DIR      output directory (required)
 *   SPARKWRITER_NETWORK      "dhcp" (default) or "static"
 *   SPARKWRITER_DISK         install disk name, default "sda"
 *   SPARKWRITER_FIELDS_FILE  optional Java .properties file of config-field overrides
 *                            (field id = value), applied last. Use it to exercise static
 *                            addressing, VLANs, MAC selection and so on.
 *
 * Output (in SPARKWRITER_OUT_DIR): assembled.iso, answer.toml, first-boot.sh, root-password.txt.
 * The password is a throwaway for the test VM; the file is written mode 0600.
 *
 * Run through Gradle, see tools/vm-test/README.md.
 */
class AssembleProxmoxIsoHarness {
    private val manifest: SparkManifest = ManifestRuntime().parseWorkflowFile(
        listOf(File("app/bundled"), File("bundled")).first { it.isDirectory }
            .resolve("proxmox-autoinstall.sparkplug.yaml"),
    )

    @Test
    fun assembleIso() {
        val baseIso = System.getenv("SPARKWRITER_BASE_ISO")
        assumeTrue("SPARKWRITER_BASE_ISO not set; harness skipped", !baseIso.isNullOrBlank())
        val outDir = File(requireNotNull(System.getenv("SPARKWRITER_OUT_DIR")) { "SPARKWRITER_OUT_DIR is required" })
            .apply { mkdirs() }
        val base = File(baseIso!!).also { assertTrue("base ISO missing: $it", it.isFile) }

        // The app verifies the image against the pinned hash before assembling; do the same.
        val expectedSha = manifest.source!!.sha256
        val actualSha = sha256Hex(base)
        assertEquals("Base ISO does not match the pinned SHA-256", expectedSha!!.lowercase(), actualSha)
        val image = VerifiedImage(
            uri = base.toURI().toString(),
            sha256 = actualSha,
            sizeBytes = base.length(),
            mediaType = manifest.source!!.mediaType ?: "",
            provenance = "harness",
            displayName = base.name,
        )

        val values = fieldValues()
        val preparation = manifest.buildLocalDeliveryPreparation(manifest.activeConfigValues(values), image)
        val plan = (preparation.deliveryPlan as LocalDeliveryPlan.FixedByteRanges).plan

        val assembled = File(outDir, "assembled.iso")
        FileInputStream(base).use { input ->
            AssembledImageWriter.assemble(
                source = input,
                outputFile = assembled,
                image = image,
                fixedByteRanges = plan,
                bootPatch = preparation.bootPatchPlan,
                onProgress = { println("[assemble] ${it.message} ${it.completed}/${it.total}") },
            )
        }

        File(outDir, "answer.toml").writeText(plan.patches.single { it.id == "answer_toml" }.content)
        File(outDir, "first-boot.sh").writeText(plan.patches.single { it.id == "firstboot_script" }.content)
        File(outDir, "root-password.txt").apply {
            writeText(preparation.userSecrets.joinToString("\n") { "${it.label}: ${it.value}" } + "\n")
            java.nio.file.Files.setPosixFilePermissions(toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(base.length(), assembled.length())
        println("[assemble] wrote ${assembled.path} (${assembled.length()} bytes)")
    }

    private fun fieldValues(): Map<String, String> {
        val disk = System.getenv("SPARKWRITER_DISK") ?: "sda"
        val network = (System.getenv("SPARKWRITER_NETWORK") ?: "dhcp").lowercase()
        val base = mapOf(
            "pve_fqdn" to "pve-test.example.invalid",
            "pve_keyboard" to "en-us",
            "pve_country" to "us",
            "pve_timezone" to "America/Denver",
            "pve_mailto" to "root@example.invalid",
            "pve_disk_filesystem" to "ext4",
            "pve_disk_list" to """["$disk"]""",
            "pve_zfs_raid" to "",
            "pve_root_ssh_keys" to "[]",
            "pve_reboot_mode" to "reboot",
            "pve_community_repos" to "community",
            "pve_firstboot_command" to "",
        )
        val net = when (network) {
            "dhcp" -> mapOf("pve_network_source" to "from-dhcp")
            // QEMU user-mode networking: guest 10.0.2.0/24, gateway 10.0.2.2, DNS 10.0.2.3.
            "static" -> mapOf(
                "pve_network_source" to "from-answer",
                "pve_network_nic" to "only",
                "pve_network_cidr" to "10.0.2.50/24",
                "pve_network_gateway" to "10.0.2.2",
                "pve_network_dns" to "10.0.2.3",
            )
            else -> error("SPARKWRITER_NETWORK must be dhcp or static, got $network")
        }
        val overrides = System.getenv("SPARKWRITER_FIELDS_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            Properties().apply { FileInputStream(path).use(::load) }
                .let { props -> props.stringPropertyNames().associateWith { props.getProperty(it) } }
        }.orEmpty()
        return base + net + overrides
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
