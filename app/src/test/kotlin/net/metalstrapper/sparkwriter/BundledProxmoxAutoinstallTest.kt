package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class BundledProxmoxAutoinstallTest {
    @get:Rule
    val temp = TemporaryFolder()

    // The release runtime: external services off.
    private val manifest: SparkManifest = ManifestRuntime().parseWorkflowFile(
        listOf(File("app/bundled"), File("bundled"))
            .first { it.isDirectory }
            .resolve("proxmox-autoinstall.sparkplug.yaml"),
    )

    @Test
    fun isRunnableWithExternalServicesOff() {
        assertEquals(emptyList<String>(), manifest.unsupportedReasons)
        assertTrue(manifest.isAndroidRunnable)
        assertTrue(manifest.hostEvents.isEmpty())
        assertEquals(null, manifest.mailroom)
    }

    @Test
    fun commandFieldIsCappedAndNeverSaved() {
        val field = manifest.configFields.single { it.id == "pve_firstboot_command" }
        assertEquals("multiline", field.type)
        assertEquals(8192, field.maxLength)
        assertEquals(null, field.profileKey())
        assertTrue(field.description.orEmpty().contains("plain text"))
    }

    @Test
    fun communityModeRendersHashedPasswordAndNoSubscriptionRepos() {
        val (answer, script) = render()

        assertTrue(answer.contains(Regex("""root-password-hashed = "\$6\$""")))
        assertFalse(answer.contains("root-password ="))
        assertTrue(script.contains("repo_mode='community'"))
        assertTrue(script.contains("URIs: https://download.proxmox.com/debian/pve"))
        assertTrue(script.contains("Components: pve-no-subscription"))
        assertTrue(script.contains("URIs: https://download.proxmox.com/debian/ceph-squid"))
        assertTrue(script.contains("/etc/apt/sources.list.d/pve-enterprise.sources"))
        listOf(answer, script).forEach { text ->
            assertFalse(text.contains("tailscale", ignoreCase = true))
            assertFalse(text.contains("curl"))
            assertFalse(text.contains("http://"))
            assertFalse(text.contains("wri7e.net"))
        }
    }

    @Test
    fun renderedScriptIsValidBash() {
        val bash = bash()
        listOf("community", "keep").forEach { mode ->
            val script = temp.newFile("check-$mode.sh").apply { writeText(render(repoMode = mode).second) }
            val result = run(bash, "-n", script.path)
            assertEquals("bash -n ($mode): ${result.second}", 0, result.first)
        }
    }

    @Test
    fun customCommandRunsExactlyAsTyped() {
        val bash = bash()
        val out = temp.newFile("out.txt")
        // Quotes, substitutions and newlines must reach bash -c unchanged.
        val command = "printf '%s|' \"it's\" '\$(not run)' >> ${out.path}\nprintf 'line2' >> ${out.path}"
        val result = runScript(bash, render(repoMode = "keep", command = command).second)

        assertEquals("script output: ${result.second}", 0, result.first)
        assertEquals("it's|\$(not run)|line2", out.readText())
        assertTrue(result.second.contains("Custom first-boot command finished"))
    }

    @Test
    fun failingCustomCommandFailsTheScript() {
        val result = runScript(bash(), render(repoMode = "keep", command = "exit 7").second)
        assertEquals(7, result.first)
        assertTrue(result.second.contains("failed with exit status 7"))
    }

    @Test
    fun emptyCustomCommandIsSkipped() {
        val result = runScript(bash(), render(repoMode = "keep", command = "").second)
        assertEquals(0, result.first)
        assertFalse(result.second.contains("Running custom first-boot command"))
    }

    @Test
    fun communityModeRewritesRepositoriesInAFakeRoot() {
        val bash = bash()
        val root = temp.newFolder("root")
        val sources = File(root, "etc/apt/sources.list.d").apply { mkdirs() }
        File(root, "etc/os-release").writeText("ID=debian\nVERSION_CODENAME=trixie\n")
        File(sources, "pve-enterprise.sources").writeText(
            "Types: deb\nURIs: https://enterprise.proxmox.com/debian/pve\nSuites: trixie\nComponents: pve-enterprise\n",
        )
        File(sources, "ceph.list").writeText("deb https://enterprise.proxmox.com/debian/ceph-squid trixie enterprise\n")
        val bin = File(root, "bin").apply { mkdirs() }
        File(bin, "apt-get").apply {
            writeText("#!/bin/sh\necho \"apt-get \$*\" >> ${File(root, "apt.log").path}\n")
            setExecutable(true)
        }

        val script = render().second
            .replace("/etc/apt/sources.list.d", sources.path)
            .replace("/etc/os-release", File(root, "etc/os-release").path)
            .replace("/usr/share/keyrings", File(root, "keyrings").path)
        val result = runScript(bash, script, path = "${bin.path}:/usr/bin:/bin")

        assertEquals("script output: ${result.second}", 0, result.first)
        val enterprise = File(sources, "pve-enterprise.sources").readText()
        assertTrue(enterprise.startsWith("Enabled: no\nTypes: deb"))
        assertEquals("# deb https://enterprise.proxmox.com/debian/ceph-squid trixie enterprise\n", File(sources, "ceph.list").readText())
        val community = File(sources, "proxmox-community.sources").readText()
        assertTrue(community.contains("URIs: https://download.proxmox.com/debian/pve\nSuites: trixie\nComponents: pve-no-subscription"))
        assertTrue(community.contains("Components: no-subscription"))
        assertFalse("no keyring present, so no Signed-By", community.contains("Signed-By"))
        assertEquals("apt-get update\n", File(root, "apt.log").readText())
    }

    @Test
    fun worstCaseCommandStillFitsThePatchSlot() {
        val slot = manifest.delivery!!.patches.single { it.id == "firstboot_script" }.byteLength
        // shell_quote turns each ' into 4 bytes; 日 is 3 bytes in UTF-8.
        listOf("'".repeat(8192), "日".repeat(8192)).forEach { command ->
            val script = render(command = command).second
            assertTrue(script.toByteArray(Charsets.UTF_8).size <= slot)
        }
    }

    @Test
    fun overLimitCommandIsRejectedAtRender() {
        assertThrows(IllegalStateException::class.java) { render(command = "x".repeat(8193)) }
    }

    private fun render(repoMode: String = "community", command: String = ""): Pair<String, String> {
        val plan = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "pve_fqdn" to "pve1.srv.home.arpa",
                "pve_keyboard" to "en-us",
                "pve_country" to "us",
                "pve_timezone" to "America/Denver",
                "pve_mailto" to "root@example.invalid",
                "pve_network_source" to "from-dhcp",
                "pve_network_cidr" to "",
                "pve_network_gateway" to "",
                "pve_network_dns" to "",
                "pve_disk_filesystem" to "ext4",
                "pve_disk_list" to """["sda"]""",
                "pve_zfs_raid" to "",
                "pve_root_ssh_keys" to "[]",
                "pve_reboot_mode" to "reboot",
                "pve_community_repos" to repoMode,
                "pve_firstboot_command" to command,
            ),
        ).deliveryPlan as LocalDeliveryPlan.FixedByteRanges
        val patches = plan.plan.patches
        return patches.single { it.id == "answer_toml" }.content to patches.single { it.id == "firstboot_script" }.content
    }

    private fun bash(): File = File("/bin/bash").also { assumeTrue("bash not available", it.canExecute()) }

    // Runs the rendered script with its log redirected into the temp folder.
    private fun runScript(bash: File, script: String, path: String? = null): Pair<Int, String> {
        val log = File(temp.root, "firstboot.log")
        val file = temp.newFile().apply {
            writeText(script.replace("/var/log/sparkwriter-firstboot.log", log.path))
        }
        return run(bash, file.path, path = path)
    }

    private fun run(bash: File, vararg args: String, path: String? = null): Pair<Int, String> {
        val builder = ProcessBuilder(listOf(bash.path) + args).redirectErrorStream(true)
        path?.let { builder.environment()["PATH"] = it }
        val process = builder.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return process.exitValue() to output
    }
}
