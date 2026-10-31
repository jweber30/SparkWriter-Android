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
        assertTrue(script.contains("sources_dir=\"\$target_root/etc/apt/sources.list.d\""))
        assertTrue(script.contains("\"\$sources_dir/pve-enterprise.sources\""))
        assertTrue(script.contains("target_root=''\n"))
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
        NETWORK_VARIANTS.forEach { (name, values) ->
            val script = temp.newFile("check-$name.sh").apply { writeText(renderWith(*values).second) }
            val result = run(bash, "-n", script.path)
            assertEquals("bash -n ($name): ${result.second}", 0, result.first)
        }
    }

    @Test
    fun customCommandRunsExactlyAsTyped() {
        val bash = bash()
        val out = temp.newFile("out.txt")
        // Quotes, substitutions and newlines must reach bash -c unchanged.
        val command = "printf '%s|' \"it's\" '\$(not run)' >> ${out.path}\nprintf 'line2' >> ${out.path}"
        val result = runScript(bash, render(repoMode = "keep", command = command).second, "late")

        assertEquals("script output: ${result.second}", 0, result.first)
        assertEquals("it's|\$(not run)|line2", out.readText())
        assertTrue(result.second.contains("Custom first-boot command finished"))
    }

    @Test
    fun failingCustomCommandFailsTheScript() {
        val result = runScript(bash(), render(repoMode = "keep", command = "exit 7").second, "late")
        assertEquals(7, result.first)
        assertTrue(result.second.contains("failed with exit status 7"))
    }

    @Test
    fun emptyCustomCommandIsSkipped() {
        val result = runScript(bash(), render(repoMode = "keep", command = "").second, "late")
        assertEquals(0, result.first)
        assertFalse(result.second.contains("Running custom first-boot command"))
    }

    @Test
    fun communityModeRewritesRepositoriesInAFakeRoot() {
        val bash = bash()
        val root = fakeRoot()
        val sources = File(root, "etc/apt/sources.list.d").apply { mkdirs() }
        File(root, "etc/os-release").writeText("ID=debian\nVERSION_CODENAME=trixie\n")
        File(sources, "pve-enterprise.sources").writeText(
            "Types: deb\nURIs: https://enterprise.proxmox.com/debian/pve\nSuites: trixie\nComponents: pve-enterprise\n",
        )
        File(sources, "ceph.list").writeText("deb https://enterprise.proxmox.com/debian/ceph-squid trixie enterprise\n")

        val result = runScript(bash, render().second, "late", root = root)

        assertEquals("script output: ${result.second}", 0, result.first)
        val enterprise = File(sources, "pve-enterprise.sources").readText()
        assertTrue(enterprise.startsWith("Enabled: no\nTypes: deb"))
        assertEquals("# deb https://enterprise.proxmox.com/debian/ceph-squid trixie enterprise\n", File(sources, "ceph.list").readText())
        val community = File(sources, "proxmox-community.sources").readText()
        assertTrue(community.contains("URIs: https://download.proxmox.com/debian/pve\nSuites: trixie\nComponents: pve-no-subscription"))
        assertTrue(community.contains("Components: no-subscription"))
        assertFalse("no keyring present, so no Signed-By", community.contains("Signed-By"))
        assertEquals("apt-get update\n", File(root, "calls.log").readText())
    }

    @Test
    fun answerUsesBeforeNetworkOrdering() {
        assertTrue(render().first.contains("ordering = \"before-network\""))
    }

    @Test
    fun everyNetworkVariantPassesTheProxmoxValidator() {
        val assistant = File("/usr/bin/proxmox-auto-install-assistant")
        assumeTrue("proxmox-auto-install-assistant not installed", assistant.canExecute())
        (listOf("dhcp" to emptyArray<Pair<String, String>>()) + NETWORK_VARIANTS).forEach { (name, values) ->
            val answer = temp.newFile("answer-$name.toml").apply { writeText(renderWith(*values).first) }
            val builder = ProcessBuilder(assistant.path, "validate-answer", answer.path).redirectErrorStream(true)
            val process = builder.start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            assertEquals("validate-answer ($name): $output\n${answer.readText()}", 0, process.exitValue())
        }
    }

    @Test
    fun staticOnlyNicMatchesAnyCard() {
        val answer = renderWith(*STATIC).first
        assertTrue(answer.contains("source = \"from-answer\""))
        assertTrue(answer.contains("cidr = \"192.0.2.10/24\""))
        assertTrue(answer.contains("filter.ID_NET_NAME_MAC = \"*\"\n"))
    }

    @Test
    fun staticMacIsNormalizedForUdev() {
        val answer = renderWith(*STATIC, "pve_network_nic" to "mac", "pve_network_mac" to "00:11:22:AA:BB:CC").first
        assertTrue(answer, answer.contains("filter.ID_NET_NAME_MAC = \"*001122aabbcc\""))
    }

    @Test
    fun dhcpDropsStaleStaticAndVlanValues() {
        // Values typed before switching back to DHCP must not reach the answer or script.
        val (answer, script) = renderWith(
            *STATIC.filterNot { it.first == "pve_network_source" }.toTypedArray(),
            "pve_network_nic" to "mac",
            "pve_network_mac" to "not a mac",
            "pve_mgmt_vlan_id" to "20",
            "pve_mgmt_vlan_cidr" to "198.51.100.10/24",
        )
        assertTrue(answer.contains("source = \"from-dhcp\""))
        listOf("cidr", "gateway", "dns", "filter").forEach { assertFalse(it, answer.contains(it)) }
        assertTrue(script.contains("vlan_id=''\n"))
        assertFalse(script.contains("198.51.100.10"))
    }

    @Test
    fun macFieldIsHiddenUnlessStaticAndMacChosen() {
        fun visible(vararg values: Pair<String, String>) =
            manifest.activeConfigFields(mapOf(*values)).any { it.id == "pve_network_mac" }
        assertFalse(visible("pve_network_source" to "from-dhcp", "pve_network_nic" to "mac"))
        assertFalse(visible("pve_network_source" to "from-answer", "pve_network_nic" to "only"))
        assertTrue(visible("pve_network_source" to "from-answer", "pve_network_nic" to "mac"))
    }

    @Test
    fun badFormatsAreRejectedAtRender() {
        listOf(
            arrayOf("pve_network_nic" to "mac", "pve_network_mac" to "00:11:22:aa:bb"),
            arrayOf("pve_network_cidr" to "192.0.2.10"),
            arrayOf("pve_network_gateway" to "192.0.2.300"),
            arrayOf("pve_mgmt_vlan_id" to "4095", "pve_mgmt_vlan_cidr" to "198.51.100.10/24"),
            arrayOf("pve_mgmt_vlan_id" to "0", "pve_mgmt_vlan_cidr" to "198.51.100.10/24"),
            arrayOf("pve_mgmt_vlan_id" to "20", "pve_mgmt_vlan_cidr" to "198.51.100.10/33"),
        ).forEach { overrides ->
            val error = assertThrows(overrides.toList().toString(), IllegalStateException::class.java) {
                renderWith(*STATIC, *overrides)
            }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("not in the expected format"))
        }
    }

    @Test
    fun earlyPhaseWithoutVlanOnlySchedulesTheLatePhase() {
        val root = installedRoot()
        val script = renderWith(*STATIC).second
        val result = runScript(bash(), script, "before-network", root = root)

        assertEquals("script output: ${result.second}", 0, result.first)
        assertEquals(INSTALLER_INTERFACES, File(root, "etc/network/interfaces").readText())
        assertEquals(INSTALLER_HOSTS, File(root, "etc/hosts").readText())
        assertLatePhaseScheduled(root)
    }

    @Test
    fun earlyPhaseAddsVlanAndKeepsUntaggedAddressAndGateway() {
        val root = installedRoot()
        val script = renderWith(*STATIC, "pve_mgmt_vlan_id" to "20", "pve_mgmt_vlan_cidr" to "198.51.100.10/24").second
        val result = runScript(bash(), script, "before-network", root = root)

        assertEquals("script output: ${result.second}", 0, result.first)
        val interfaces = File(root, "etc/network/interfaces").readText()
        assertEquals(
            """
            auto lo
            iface lo inet loopback

            iface eno1 inet manual

            auto vmbr0
            iface vmbr0 inet static
            	bridge-vlan-aware yes
            	bridge-vids 2-4094
            	address 192.0.2.10/24
            	gateway 192.0.2.1
            	bridge-ports eno1
            	bridge-stp off
            	bridge-fd 0

            iface eno2 inet manual


            # sparkwriter: management VLAN. vmbr0 keeps its untagged address as a
            # fallback; remove that address once you can reach the host over the VLAN.
            auto vmbr0.20
            iface vmbr0.20 inet static
            	address 198.51.100.10/24

            source /etc/network/interfaces.d/*

            """.trimIndent(),
            interfaces,
        )
        assertEquals(INSTALLER_HOSTS.replace("192.0.2.10 pve1", "198.51.100.10 pve1"), File(root, "etc/hosts").readText())
        assertLatePhaseScheduled(root)

        // Proxmox reruns the hook if a boot is cut short; a second run changes nothing.
        val hosts = File(root, "etc/hosts").readText()
        val again = runScript(bash(), script, "before-network", root = root)
        assertEquals(0, again.first)
        assertTrue(again.second.contains("already set up"))
        assertEquals(interfaces, File(root, "etc/network/interfaces").readText())
        assertEquals(hosts, File(root, "etc/hosts").readText())
    }

    @Test
    fun vlanGatewayTakesOverTheDefaultRoute() {
        val root = installedRoot()
        val script = renderWith(
            *STATIC,
            "pve_mgmt_vlan_id" to "20",
            "pve_mgmt_vlan_cidr" to "198.51.100.10/24",
            "pve_mgmt_vlan_gateway" to "198.51.100.1",
        ).second
        val result = runScript(bash(), script, "before-network", root = root)

        assertEquals("script output: ${result.second}", 0, result.first)
        val interfaces = File(root, "etc/network/interfaces").readText()
        assertFalse(interfaces.contains("gateway 192.0.2.1"))
        assertTrue(interfaces.contains("\taddress 192.0.2.10/24\n"))
        assertTrue(interfaces.contains("iface vmbr0.20 inet static\n\taddress 198.51.100.10/24\n\tgateway 198.51.100.1\n"))
    }

    @Test
    fun failedVlanSetupRestoresTheInstallerNetwork() {
        val root = installedRoot()
        val unbridged = INSTALLER_INTERFACES.replace("vmbr0", "br-other")
        File(root, "etc/network/interfaces").writeText(unbridged)
        val script = renderWith(*STATIC, "pve_mgmt_vlan_id" to "20", "pve_mgmt_vlan_cidr" to "198.51.100.10/24").second
        val result = runScript(bash(), script, "before-network", root = root)

        assertEquals("script output: ${result.second}", 0, result.first)
        assertTrue(result.second.contains("Management VLAN setup failed"))
        assertEquals(unbridged, File(root, "etc/network/interfaces").readText())
        assertEquals(INSTALLER_HOSTS, File(root, "etc/hosts").readText())
        assertLatePhaseScheduled(root)
    }

    private fun assertLatePhaseScheduled(root: File) {
        val unit = File(root, "etc/systemd/system/sparkwriter-firstboot-late.service").readText()
        assertTrue(unit.contains("ExecStart=/var/lib/sparkwriter/firstboot.sh late\n"))
        assertTrue(unit.contains("ConditionPathExists=/var/lib/sparkwriter/late-pending\n"))
        assertTrue(unit.contains("After=network-online.target pveproxy.service\n"))
        assertTrue(File(root, "var/lib/sparkwriter/late-pending").isFile)
        assertTrue(File(root, "var/lib/sparkwriter/firstboot.sh").canExecute())
        assertTrue(
            File(root, "calls.log").readText().contains(
                "systemctl daemon-reload\n" +
                    "systemctl enable sparkwriter-firstboot-late.service\n" +
                    "systemctl start --no-block sparkwriter-firstboot-late.service\n",
            ),
        )
    }

    // A root holding /etc/network/interfaces and /etc/hosts as the Proxmox 9.2
    // installer writes them for a static install on eno1 (Proxmox/Install.pm).
    private fun installedRoot(): File = fakeRoot().also { root ->
        File(root, "etc/network").mkdirs()
        File(root, "etc/network/interfaces").writeText(INSTALLER_INTERFACES)
        File(root, "etc/hosts").writeText(INSTALLER_HOSTS)
    }

    // A root with stub apt-get and systemctl that log their arguments to calls.log.
    private fun fakeRoot(): File {
        val root = temp.newFolder()
        val bin = File(root, "bin").apply { mkdirs() }
        listOf("apt-get", "systemctl").forEach { name ->
            File(bin, name).apply {
                writeText("#!/bin/sh\necho \"$name \$*\" >> ${File(root, "calls.log").path}\n")
                setExecutable(true)
            }
        }
        return root
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

    private fun render(repoMode: String = "community", command: String = ""): Pair<String, String> =
        renderWith("pve_community_repos" to repoMode, "pve_firstboot_command" to command)

    // Renders through activeConfigValues, as the wizard does, so hidden fields drop out.
    private fun renderWith(vararg overrides: Pair<String, String>): Pair<String, String> {
        val values = mapOf(
            "pve_fqdn" to "pve1.srv.home.arpa",
            "pve_keyboard" to "en-us",
            "pve_country" to "us",
            "pve_timezone" to "America/Denver",
            "pve_mailto" to "root@example.invalid",
            "pve_network_source" to "from-dhcp",
            "pve_disk_filesystem" to "ext4",
            "pve_disk_list" to """["sda"]""",
            "pve_zfs_raid" to "",
            "pve_root_ssh_keys" to "[]",
            "pve_reboot_mode" to "reboot",
            "pve_community_repos" to "community",
            "pve_firstboot_command" to "",
        ) + overrides
        val plan = manifest.buildLocalDeliveryPreparation(manifest.activeConfigValues(values))
            .deliveryPlan as LocalDeliveryPlan.FixedByteRanges
        val patches = plan.plan.patches
        return patches.single { it.id == "answer_toml" }.content to patches.single { it.id == "firstboot_script" }.content
    }

    private fun bash(): File = File("/bin/bash").also { assumeTrue("bash not available", it.canExecute()) }

    // Runs the rendered script against a fake root, with stub commands first on PATH.
    private fun runScript(bash: File, script: String, phase: String, root: File = fakeRoot()): Pair<Int, String> {
        val file = temp.newFile().apply {
            writeText(script.replaceFirst("target_root=''", "target_root='${root.path}'"))
        }
        return run(bash, file.path, phase, path = "${File(root, "bin").path}:/usr/bin:/bin")
    }

    private companion object {
        val STATIC = arrayOf(
            "pve_network_source" to "from-answer",
            "pve_network_nic" to "only",
            "pve_network_cidr" to "192.0.2.10/24",
            "pve_network_gateway" to "192.0.2.1",
            "pve_network_dns" to "192.0.2.53",
        )

        val NETWORK_VARIANTS: List<Pair<String, Array<Pair<String, String>>>> = listOf(
            "static-only-nic" to STATIC,
            "static-mac" to arrayOf(*STATIC, "pve_network_nic" to "mac", "pve_network_mac" to "00:11:22:AA:BB:CC"),
            "static-vlan" to arrayOf(*STATIC, "pve_mgmt_vlan_id" to "20", "pve_mgmt_vlan_cidr" to "198.51.100.10/24"),
            "static-vlan-gateway" to arrayOf(
                *STATIC,
                "pve_mgmt_vlan_id" to "20",
                "pve_mgmt_vlan_cidr" to "198.51.100.10/24",
                "pve_mgmt_vlan_gateway" to "198.51.100.1",
            ),
            "static-ipv6" to arrayOf(
                *STATIC,
                "pve_network_cidr" to "2001:db8::10/64",
                "pve_network_gateway" to "2001:db8::1",
                "pve_network_dns" to "2001:db8::53",
            ),
        )

        val INSTALLER_INTERFACES = """
            auto lo
            iface lo inet loopback

            iface eno1 inet manual

            auto vmbr0
            iface vmbr0 inet static
            	address 192.0.2.10/24
            	gateway 192.0.2.1
            	bridge-ports eno1
            	bridge-stp off
            	bridge-fd 0

            iface eno2 inet manual


            source /etc/network/interfaces.d/*

        """.trimIndent()

        val INSTALLER_HOSTS = """
            127.0.0.1 localhost.localdomain localhost
            192.0.2.10 pve1.srv.home.arpa pve1

            # The following lines are desirable for IPv6 capable hosts

            ::1     ip6-localhost ip6-loopback
            fe00::0 ip6-localnet
            ff00::0 ip6-mcastprefix
            ff02::1 ip6-allnodes
            ff02::2 ip6-allrouters
            ff02::3 ip6-allhosts

        """.trimIndent()
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
