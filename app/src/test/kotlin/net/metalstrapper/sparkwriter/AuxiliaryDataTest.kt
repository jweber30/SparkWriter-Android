package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteReturnPlan
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.manifest.ManifestValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuxiliaryDataTest {
    // These tests exercise external-service features, which the release build disables.
    private val runtime = ManifestRuntime(externalServicesEnabled = true)
    private val hash = "c".repeat(64)

    @Test
    fun buildsProxmoxAuxiliaryPartitionPlanFromWorkflowActions() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            source:
              id: proxmox
              name: Proxmox
              sha256: $hash
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - auxiliary_partition
              auxiliary_partition:
                label: PROXMOX-AIS
                filesystem: vfat
                required_files:
                  - /answer.toml
            delivery:
              mode: auxiliary_partition
              partition:
                label: PROXMOX-AIS
                filesystem: vfat
                size_mb: 64
                placement: preallocated_in_base_image
                geometry:
                  base_image_sha256: $hash
                  partition_table: gpt
                  partition_number: 3
                  start_lba: 1048576
                  sector_size: 512
                  byte_offset: 536870912
                  byte_length: 67108864
              files:
                /answer.toml:
                  artifact_id: proxmox-answer
                  media_type: application/toml
                /firstboot.sh:
                  artifact_id: firstboot-script
                  media_type: text/x-shellscript
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: proxmox_answer_standard
                  output_var: proxmox_answer_toml
                - id: create-answer
                  type: create_artifact
                  artifact_id: proxmox-answer
                  media_type: application/toml
                  content_var: proxmox_answer_toml
                - id: render-firstboot
                  type: render_template
                  template: firstboot_script
                  output_var: firstboot_script
                - id: create-firstboot
                  type: create_artifact
                  artifact_id: firstboot-script
                  media_type: text/x-shellscript
                  executable: true
                  content_var: firstboot_script
            templates:
              proxmox_answer_standard:
                - "[global]"
                - fqdn = "{{hostname}}"
              firstboot_script:
                - "#!/usr/bin/env bash"
                - tailscale up --auth-key={{pve_tailscale_auth_key}}
            """.trimIndent(),
        )

        val plan = manifest.buildAuxiliaryDataPlan(
            mapOf(
                "hostname" to "pve1-test.srv.home.arpa",
                "pve_tailscale_auth_key" to "tskey-test",
            ),
        )

        requireNotNull(plan)
        assertEquals("PROXMOX-AIS", plan.label)
        assertEquals("vfat", plan.filesystem)
        assertEquals(64L * 1024L * 1024L, plan.sizeBytes)
        assertEquals(hash, plan.sourceSha256)
        assertEquals(536870912L, plan.geometry?.byteOffset)
        assertTrue(plan.files.single { it.path == "/answer.toml" }.content.contains("fqdn = \"pve1-test.srv.home.arpa\""))
        assertTrue(plan.files.single { it.path == "/firstboot.sh" }.executable)
        assertTrue(plan.files.single { it.path == "/firstboot.sh" }.content.contains("tskey-test"))
    }

    @Test
    fun buildsAppendAfterImageUbuntuCidataPlanWithoutGeometry() {
        val manifest = runtime.parseCombined(appendAfterImageManifest())

        val plan = manifest.buildAuxiliaryDataPlan(mapOf("hostname" to "ubuntu-test"))

        requireNotNull(plan)
        assertEquals("CIDATA", plan.label)
        assertEquals("vfat", plan.filesystem)
        assertEquals("append_after_image", plan.placement)
        assertEquals(64L * 1024L * 1024L, plan.sizeBytes)
        assertEquals(hash, plan.sourceSha256)
        assertNull(plan.geometry)
        assertTrue(plan.files.single { it.path == "/user-data" }.content.contains("autoinstall:"))
        assertTrue(plan.files.single { it.path == "/meta-data" }.content.contains("ubuntu-test"))
    }

    @Test
    fun buildsAppendAfterImagePlanForUnknownImageHash() {
        val manifest = runtime.parseCombined(appendAfterImageManifest())

        val plan = manifest.buildAuxiliaryDataPlan(
            mapOf("hostname" to "ubuntu-test"),
            image = verifiedImage(sha256 = "d".repeat(64)),
        )

        requireNotNull(plan)
        assertEquals("append_after_image", plan.placement)
        assertEquals(hash, plan.sourceSha256)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsPreallocatedAuxiliaryPlanForUnknownImageHash() {
        val manifest = runtime.parseCombined(auxiliaryManifest())

        manifest.buildAuxiliaryDataPlan(emptyMap(), image = verifiedImage(sha256 = "d".repeat(64)))
    }

    @Test
    fun parsesDebianNetinstPreseedSparkplugFromDocs() {
        val sparkplugFile = listOf(
            File("docs/debian-netinst-preseed.sparkplug.yaml"),
            File("../docs/debian-netinst-preseed.sparkplug.yaml"),
        ).first { it.isFile }
        val manifest = runtime.parseWorkflowFile(sparkplugFile)
        val preparation = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "hostname" to "debian-test",
                "domain" to "srv.home.arpa",
                "locale.locale" to "en_US.UTF-8",
                "locale.keyboard" to "us",
                "locale.timezone" to "America/Denver",
                "target_disk" to "/dev/nvme0n1",
                "username" to "debian",
                "full_name" to "Debian User",
                "apt_proxy" to "http://apt-proxy:3142",
                "package_include" to "curl ca-certificates openssh-server",
                "ssh_public_keys" to "ssh-ed25519 AAAA-test",
            ),
        )
        val delivery = prepareWriteReadyReturnDeliveries(
            writeIntent(requireNotNull(preparation.returnPlan)),
        ).single()
        val bootPreseedUrl = requireNotNull(preparation.bootPatchPlan).kernelArgPatches.first().ensureArgs
            .single { it.startsWith("url=") }
            .removePrefix("url=")

        assertNull(preparation.deliveryPlan)
        assertTrue(bootPreseedUrl.matches(Regex("http://wri7e\\.net/[A-Za-z0-9]{7}")))
        assertEquals("POST", delivery.method)
        assertEquals("https://mailroom.metalstrapper.net/compact/v1/artifacts", delivery.url)
        assertEquals(
            listOf("url=$bootPreseedUrl", "auto=true", "priority=critical", "interface=auto"),
            requireNotNull(preparation.bootPatchPlan).kernelArgPatches.first().ensureArgs,
        )
        assertEquals(listOf("vga=788", "quiet"), requireNotNull(preparation.bootPatchPlan).kernelArgPatches.first().removeArgs)
        assertTrue(delivery.body.contains("d-i netcfg/get_hostname string debian-test"))
        assertTrue(delivery.body.contains("d-i mirror/http/proxy string http://apt-proxy:3142"))
        assertTrue(delivery.body.contains("/home/debian/.ssh/authorized_keys"))
        assertTrue(delivery.body.contains("ssh-ed25519 AAAA-test"))
        assertTrue(delivery.body.contains("d-i passwd/root-login boolean false"))
        assertTrue(delivery.body.contains("d-i passwd/root-password password unused"))
        assertTrue(delivery.body.contains("d-i passwd/root-password-again password unused"))
        assertTrue(delivery.body.contains("d-i passwd/user-fullname string Debian User"))
        assertTrue(delivery.body.contains("d-i passwd/username string debian"))
        assertTrue(Regex("""d-i passwd/user-password-crypted password \$6\$[./A-Za-z0-9]{16}\$""").containsMatchIn(delivery.body))
        assertTrue(delivery.body.contains("d-i partman-auto/disk string /dev/nvme0n1"))
        assertTrue(delivery.body.contains("d-i partman-lvm/device_remove_lvm boolean true"))
        assertTrue(delivery.body.contains("d-i partman-md/device_remove_md boolean true"))
        assertTrue(delivery.body.contains("d-i partman-partitioning/confirm_write_new_label boolean true"))
        assertFalse(delivery.body.contains("d-i partman/early_command string"))
        assertFalse(delivery.body.contains("d-i partman-auto/init_automatically_partition select biggest_free"))
        assertFalse(delivery.body.contains("d-i passwd/user-password password"))
        assertFalse(delivery.body.contains("d-i passwd/user-password-again password"))
        val secret = preparation.userSecrets.single()
        assertEquals("generated_user_password", secret.id)
        assertEquals("Admin password", secret.label)
        assertTrue(secret.value.isNotBlank())
        assertFalse(delivery.body.contains(secret.value))
    }

    @Test
    fun parsesBundledSparkplugExamples() {
        val sparkplugs = bundledSparkplugFiles()

        assertTrue(sparkplugs.isNotEmpty())
        assertEquals(
            listOf("debian-https-preseed", "proxmox-autoinstall", "proxmox-tailscale", "ubuntu-autoinstall"),
            sparkplugs.map { runtime.parseWorkflowFile(it).metadata.id }.sorted(),
        )
    }

    @Test
    fun bundledProxmoxTailscaleBuildsIsoPlaceholderPatches() {
        val manifest = runtime.parseWorkflowFile(bundledSparkplug("proxmox-tailscale.sparkplug.yaml"))
        val preparation = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "pve_fqdn" to "pve1-test.srv.home.arpa",
                "pve_keyboard" to "en-us",
                "pve_country" to "us",
                "pve_timezone" to "America/Denver",
                "pve_mailto" to "root@example.invalid",
                "pve_network_source" to "from-dhcp",
                "pve_network_cidr" to "",
                "pve_network_gateway" to "",
                "pve_network_dns" to "",
                "pve_disk_filesystem" to "zfs",
                "pve_disk_list" to """["sda", "sdb"]""",
                "pve_zfs_raid" to "raid1",
                "pve_root_ssh_keys" to """["ssh-ed25519 AAAATEST root@test"]""",
                "pve_reboot_mode" to "reboot",
                "tailscale_auth_key" to "tskey-test",
            ),
        )

        assertNull(preparation.bootPatchPlan)
        assertNull(preparation.returnPlan)

        val plan = preparation.deliveryPlan as LocalDeliveryPlan.FixedByteRanges
        assertEquals("fd438f0233337849e41a8ec632275bc769657e4befe64b25078fa1e9178c0b29", plan.plan.baseImageSha256)

        val answerPatch = plan.plan.patches.single { it.id == "answer_toml" }
        assertEquals("answer.toml", answerPatch.logicalName)
        assertEquals(1706962944L, answerPatch.byteOffset)
        assertEquals(16384L, answerPatch.byteLength)
        assertEquals("metalstrapper-patch-slot", answerPatch.placeholder?.encoding)
        assertEquals("toml-comment", answerPatch.padding?.strategy)
        assertTrue(answerPatch.content.contains("""fqdn = "pve1-test.srv.home.arpa""""))
        val rootPassword = preparation.userSecrets.single().value
        assertFalse("answer.toml must not carry the plaintext root password", answerPatch.content.contains(rootPassword))
        assertFalse(answerPatch.content.contains("root-password ="))
        val hash = Regex("""root-password-hashed = "(\$6\$[./A-Za-z0-9]{16}\$[./A-Za-z0-9]{86})"""")
            .find(answerPatch.content)?.groupValues?.get(1)
        assertNotNull("answer.toml must carry a sha512-crypt root password hash", hash)
        assertEquals(hash, org.apache.commons.codec.digest.Crypt.crypt(rootPassword, hash!!))
        assertTrue(answerPatch.content.contains("""disk-list = ["sda", "sdb"]"""))
        assertTrue(answerPatch.content.contains("""root-ssh-keys = ["ssh-ed25519 AAAATEST root@test"]"""))
        assertTrue(answerPatch.content.contains("""zfs.raid = "raid1""""))
        assertTrue(answerPatch.content.contains("""source = "from-iso""""))
        assertTrue(answerPatch.content.contains("""ordering = "fully-up""""))
        assertFalse(answerPatch.content.contains("wri7e.net"))
        assertFalse(answerPatch.content.contains("mailroom"))

        val firstbootPatch = plan.plan.patches.single { it.id == "firstboot_script" }
        assertEquals("proxmox-first-boot", firstbootPatch.logicalName)
        assertEquals(1707356160L, firstbootPatch.byteOffset)
        assertEquals(65536L, firstbootPatch.byteLength)
        assertEquals("metalstrapper-patch-slot", firstbootPatch.placeholder?.encoding)
        assertEquals("shell-comment", firstbootPatch.padding?.strategy)
        assertTrue(firstbootPatch.content.contains("normalize_proxmox_repositories"))
        assertTrue(firstbootPatch.content.contains("URIs: http://download.proxmox.com/debian/pve"))
        assertTrue(firstbootPatch.content.contains("URIs: http://download.proxmox.com/debian/ceph-squid"))
        assertTrue(firstbootPatch.content.contains("Suites: trixie"))
        assertTrue(firstbootPatch.content.contains("curl -fsSL https://tailscale.com/install.sh | sh"))
        assertTrue(firstbootPatch.content.contains("""tailscale_auth_key='tskey-test'"""))
        assertFalse(firstbootPatch.content.contains("tailscale_hostname"))
        assertTrue(firstbootPatch.content.contains("""tailscale up --ssh --auth-key="${'$'}tailscale_auth_key"""))
        assertFalse(firstbootPatch.content.contains("--hostname="))
        assertTrue(
            "Proxmox repositories must be normalized before invoking the Tailscale installer",
            firstbootPatch.content.indexOf("      normalize_proxmox_repositories") <
                firstbootPatch.content.indexOf("curl -fsSL https://tailscale.com/install.sh | sh"),
        )
    }

    @Test
    fun bundledProxmoxEscapesHostileFieldValues() {
        val manifest = runtime.parseWorkflowFile(bundledSparkplug("proxmox-tailscale.sparkplug.yaml"))
        val preparation = manifest.buildLocalDeliveryPreparation(
            proxmoxValues(
                "pve_mailto" to "root@example.invalid\"\n[first-boot]\nsource = \"from-url",
                "tailscale_auth_key" to "tskey-'; echo pwned #",
            ),
        )
        val patches = (preparation.deliveryPlan as LocalDeliveryPlan.FixedByteRanges).plan.patches
        val answer = patches.single { it.id == "answer_toml" }.content
        val script = patches.single { it.id == "firstboot_script" }.content

        assertTrue(answer.contains("""mailto = "root@example.invalid\"\n[first-boot]\nsource = \"from-url""""))
        assertEquals(1, Regex("""^\s*\[first-boot\]""", RegexOption.MULTILINE).findAll(answer).count())
        assertTrue(script.contains("""tailscale_auth_key='tskey-'\''; echo pwned #'"""))
    }

    @Test
    fun renderEnforcesMaxLengthEvenWhenEditorIsBypassed() {
        val manifest = runtime.parseWorkflowFile(bundledSparkplug("proxmox-tailscale.sparkplug.yaml"))
        val error = assertThrows(IllegalStateException::class.java) {
            manifest.buildLocalDeliveryPreparation(proxmoxValues("pve_fqdn" to "a".repeat(254)))
        }
        assertTrue(error.message.orEmpty().contains("the limit is 253"))
    }

    @Test
    fun renderSparkTemplateAppliesFilters() {
        val rendered = renderSparkTemplate(
            "a={{ v | shell_quote }}\nb = {{v|toml_string}}\nc={{ v }}",
            mapOf("v" to "x'\"y"),
        )
        assertEquals("a='x'\\''\"y'\nb = \"x'\\\"y\"\nc=x'\"y\n", rendered)
    }

    private fun proxmoxValues(vararg overrides: Pair<String, String>): Map<String, String> = mapOf(
        "pve_fqdn" to "pve1-test.srv.home.arpa",
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
        "tailscale_auth_key" to "tskey-test",
    ) + overrides

    @Test
    fun bundledProxmoxTailscaleDhcpDoesNotRequireHiddenStaticNetworkFields() {
        val manifest = runtime.parseWorkflowFile(bundledSparkplug("proxmox-tailscale.sparkplug.yaml"))
        val values = manifest.activeConfigValues(
            mapOf(
                "pve_fqdn" to "pve1-test.srv.home.arpa",
                "pve_keyboard" to "en-us",
                "pve_country" to "us",
                "pve_timezone" to "America/Denver",
                "pve_mailto" to "root@example.invalid",
                "pve_network_source" to "from-dhcp",
                "pve_disk_filesystem" to "ext4",
                "pve_disk_list" to """["sda"]""",
                "pve_root_ssh_keys" to "[]",
                "pve_reboot_mode" to "reboot",
                "tailscale_auth_key" to "tskey-test",
            ),
        )

        assertNull(values["pve_network_cidr"])
        assertNull(values["pve_network_gateway"])
        assertNull(values["pve_network_dns"])

        val plan = manifest.buildLocalDeliveryPreparation(values).deliveryPlan as LocalDeliveryPlan.FixedByteRanges
        val answer = plan.plan.patches.single { it.id == "answer_toml" }.content

        assertTrue(answer.contains("""source = "from-iso""""))
        assertFalse(answer.contains("cidr ="))
        assertFalse(answer.contains("gateway ="))
        assertFalse(answer.contains("dns ="))
        assertFalse(answer.contains("zfs.raid ="))
        assertFalse(answer.contains("{% if"))
        assertFalse(answer.contains("mailroom"))
        assertFalse(answer.contains("wri7e.net"))
    }

    @Test
    fun bundledDebianHttpsPreseedRequestsHttpsCompactReadUrl() {
        val manifest = runtime.parseWorkflowFile(bundledSparkplug("debian-https-preseed.sparkplug.yaml"))
        val preparation = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "hostname" to "debian-test",
                "domain" to "srv.home.arpa",
                "locale.locale" to "en_US.UTF-8",
                "locale.keyboard" to "us",
                "locale.timezone" to "America/Denver",
                "target_disk" to "/dev/nvme0n1",
                "username" to "debian",
                "full_name" to "Debian User",
                "apt_proxy" to "",
                "package_include" to "curl ca-certificates openssh-server",
                "ssh_public_keys" to "",
            ),
        )
        val bootPreseedUrl = requireNotNull(preparation.bootPatchPlan).kernelArgPatches.first().ensureArgs
            .single { it.startsWith("url=") }
            .removePrefix("url=")
        val delivery = prepareWriteReadyReturnDeliveries(
            writeIntent(requireNotNull(preparation.returnPlan)),
        ).single()

        assertTrue(bootPreseedUrl.matches(Regex("https://wri7e\\.net/[A-Za-z0-9]{7}")))
        assertEquals(bootPreseedUrl, delivery.compactReadUrl)
        assertEquals("POST", delivery.method)
        assertEquals("https://mailroom.metalstrapper.net/compact/v1/artifacts", delivery.url)
    }

    @Test
    fun declaredCompactMailroomArtifactRendersReadAndWriteUrls() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: compact-demo
              name: Compact Demo
            source:
              id: compact-source
              name: Compact Source
              user_supplied: true
            mailroom:
              compact_artifacts:
                - id: answer
                  read_protocol: https
            boot_patch:
              filesystem: iso9660
              kernel_arg_patches:
                - candidate_paths:
                    - /boot/grub/grub.cfg
                  config_family: grub
                  selector:
                    menuentry: Install
                    kernel: /vmlinuz
                  ensure_args:
                    - url={{Spark.mail.compact.answer_url}}
            templates:
              answer: ok
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.compact.answer_write_url}}"
                  method: POST
                  delivery_mode: required
                  template: answer
                  media_type: text/plain
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val delivery = prepareWriteReadyReturnDeliveries(
            writeIntent(requireNotNull(preparation.returnPlan)),
        ).single()
        val bootUrl = requireNotNull(preparation.bootPatchPlan).kernelArgPatches.single().ensureArgs.single().removePrefix("url=")

        assertTrue(bootUrl.matches(Regex("https://wri7e\\.net/[A-Za-z0-9]{7}")))
        assertEquals(bootUrl, delivery.compactReadUrl)
        assertEquals("POST", delivery.method)
        assertEquals("https://mailroom.metalstrapper.net/compact/v1/artifacts", delivery.url)
    }

    @Test(expected = IllegalStateException::class)
    fun declaredCompactMailroomArtifactRequiresWriteReadyDelivery() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: compact-demo
              name: Compact Demo
            source:
              id: compact-source
              name: Compact Source
              user_supplied: true
            mailroom:
              compact_artifacts:
                - id: answer
            boot_patch:
              filesystem: iso9660
              kernel_arg_patches:
                - candidate_paths:
                    - /boot/grub/grub.cfg
                  config_family: grub
                  selector:
                    menuentry: Install
                    kernel: /vmlinuz
                  ensure_args:
                    - url={{Spark.mail.compact.answer_url}}
            """.trimIndent(),
        )

        manifest.buildLocalDeliveryPreparation(emptyMap())
    }

    @Test
    fun rendersDebianPreseedSelectedInstallDisk() {
        val manifest = runtime.parseWorkflowFile(docsSparkplug("debian-netinst-preseed.sparkplug.yaml"))

        val sdaPreparation = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "hostname" to "debian-test",
                "domain" to "srv.home.arpa",
                "locale.locale" to "en_US.UTF-8",
                "locale.keyboard" to "us",
                "locale.timezone" to "America/Denver",
                "target_disk" to "/dev/sda",
                "username" to "debian",
                "full_name" to "Debian User",
                "apt_proxy" to "",
                "package_include" to "curl ca-certificates openssh-server",
                "ssh_public_keys" to "",
            ),
        )
        val sdaDelivery = prepareWriteReadyReturnDeliveries(
            writeIntent(requireNotNull(sdaPreparation.returnPlan)),
        ).single()

        val customPreparation = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "hostname" to "debian-test",
                "domain" to "srv.home.arpa",
                "locale.locale" to "en_US.UTF-8",
                "locale.keyboard" to "us",
                "locale.timezone" to "America/Denver",
                "target_disk" to "custom",
                "custom_target_disk" to "/dev/vda",
                "username" to "debian",
                "full_name" to "Debian User",
                "apt_proxy" to "",
                "package_include" to "curl ca-certificates openssh-server",
                "ssh_public_keys" to "",
            ),
        )
        val customDelivery = prepareWriteReadyReturnDeliveries(
            writeIntent(requireNotNull(customPreparation.returnPlan)),
        ).single()

        assertEquals(
            listOf("/dev/nvme0n1", "/dev/sda", "custom"),
            manifest.configFields.single { it.id == "target_disk" }.options.map { it.value },
        )
        assertEquals("custom", manifest.configFields.single { it.id == "custom_target_disk" }.whenCondition?.value)
        assertTrue(sdaDelivery.body.contains("d-i partman-auto/disk string /dev/sda"))
        assertFalse(sdaDelivery.body.contains("d-i partman-auto/disk string custom"))
        assertTrue(customDelivery.body.contains("d-i partman-auto/disk string /dev/vda"))
        assertFalse(customDelivery.body.contains("d-i partman-auto/disk string custom"))
    }

    @Test
    fun parsesBundledDebianNetinstPreseedManifestWithoutPinnedImageHash() {
        val manifest = runtime.parseWorkflowFile(docsSparkplug("debian-netinst-preseed.sparkplug.yaml"))
        val preparation = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "hostname" to "debian-test",
                "domain" to "srv.home.arpa",
                "locale.locale" to "en_US.UTF-8",
                "locale.keyboard" to "us",
                "locale.timezone" to "America/Denver",
                "target_disk" to "/dev/nvme0n1",
                "username" to "debian",
                "full_name" to "Debian User",
                "apt_proxy" to "",
                "package_include" to "curl ca-certificates openssh-server",
                "ssh_public_keys" to "",
            ),
        )
        val delivery = prepareWriteReadyReturnDeliveries(
            writeIntent(requireNotNull(preparation.returnPlan)),
        ).single()
        val bootPreseedUrl = requireNotNull(preparation.bootPatchPlan).kernelArgPatches.first().ensureArgs
            .single { it.startsWith("url=") }
            .removePrefix("url=")

        assertEquals("https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-13.5.0-amd64-netinst.iso", manifest.source?.url)
        assertEquals(791674880L, manifest.source?.sizeBytes)
        assertEquals("a3c32ee35ae8daeac6cfa2570ddb3394d830e2a324730085637ff295ff6f340b", manifest.source?.first64KSha256)
        assertNull(manifest.source?.sha256)
        assertNull(preparation.deliveryPlan)
        assertTrue(bootPreseedUrl.matches(Regex("http://wri7e\\.net/[A-Za-z0-9]{7}")))
        assertEquals("POST", delivery.method)
        assertEquals("https://mailroom.metalstrapper.net/compact/v1/artifacts", delivery.url)
        assertEquals(
            listOf("url=$bootPreseedUrl", "auto=true", "priority=critical", "interface=auto"),
            requireNotNull(preparation.bootPatchPlan).kernelArgPatches.first().ensureArgs,
        )
        assertEquals(listOf("vga=788", "quiet"), requireNotNull(preparation.bootPatchPlan).kernelArgPatches.first().removeArgs)
        assertTrue(delivery.body.contains("d-i passwd/root-login boolean false"))
        assertTrue(delivery.body.contains("d-i passwd/root-password password unused"))
        assertTrue(delivery.body.contains("d-i passwd/root-password-again password unused"))
        assertTrue(delivery.body.contains("d-i passwd/user-fullname string Debian User"))
        assertTrue(delivery.body.contains("d-i passwd/username string debian"))
        assertTrue(Regex("""d-i passwd/user-password-crypted password \$6\$[./A-Za-z0-9]{16}\$""").containsMatchIn(delivery.body))
        assertTrue(delivery.body.contains("d-i partman-auto/disk string /dev/nvme0n1"))
        assertTrue(delivery.body.contains("d-i partman-lvm/device_remove_lvm boolean true"))
        assertTrue(delivery.body.contains("d-i partman-md/device_remove_md boolean true"))
        assertTrue(delivery.body.contains("d-i partman-partitioning/confirm_write_new_label boolean true"))
        assertFalse(delivery.body.contains("d-i partman/early_command string"))
        assertFalse(delivery.body.contains("d-i partman-auto/init_automatically_partition select biggest_free"))
        assertFalse(delivery.body.contains("d-i passwd/user-password password"))
        assertFalse(delivery.body.contains("d-i passwd/user-password-again password"))
        val secret = preparation.userSecrets.single()
        assertEquals("generated_user_password", secret.id)
        assertEquals("Admin password", secret.label)
        assertTrue(secret.value.isNotBlank())
        assertFalse(delivery.body.contains(secret.value))
    }

    @Test
    fun buildsPatchableAppendAdapterPreparationWithoutSourceHash() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: append-demo
              name: Append Demo
            source:
              id: ubuntu-user-iso
              name: Ubuntu user ISO
              user_supplied: true
              adaptation:
                status: patchable_on_import
            installer:
              scheme: ubuntu-autoinstall
              accepts_delivery:
                - auxiliary_partition
              auxiliary_partition:
                label: CIDATA
                filesystem: vfat
                required_files:
                  - /user-data
            artifacts:
              user-data:
                media_type: text/cloud-config
                template: user-data
            delivery:
              mode: auxiliary_partition
              partition:
                label: CIDATA
                filesystem: vfat
                size_mb: 64
                placement: append_after_image
              files:
                /user-data:
                  artifact_id: user-data
                  media_type: text/cloud-config
            boot_patch:
              filesystem: iso9660
              kernel_arg_patches:
                - candidate_paths:
                    - /boot/grub/grub.cfg
                  config_family: grub
                  selector:
                    menuentry: Try or Install Ubuntu
                    kernel: /casper/vmlinuz
                  ensure_args:
                    - autoinstall
                    - ds=nocloud
            templates:
              user-data: |
                #cloud-config
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val auxiliary = (preparation.deliveryPlan as LocalDeliveryPlan.Auxiliary).plan
        val bootPatch = requireNotNull(preparation.bootPatchPlan)

        assertNull(auxiliary.sourceSha256)
        val kernelArgPatch = bootPatch.kernelArgPatches.single()
        assertEquals(listOf("/boot/grub/grub.cfg"), kernelArgPatch.candidatePaths)
        assertEquals(listOf("autoinstall", "ds=nocloud"), kernelArgPatch.ensureArgs)
    }

    @Test
    fun buildsScriptOnlyBootPatchWithoutDeliveryPlan() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: netboot-demo
              name: Netboot Demo
            source:
              id: netboot-xyz-user-iso
              name: netboot.xyz ISO
              family: netboot
              user_supplied: true
              capabilities:
                - netboot-rendezvous
              adaptation:
                status: patchable_on_import
            boot_patch:
              filesystem: iso9660
              script_patches:
                - kind: netboot_xyz_autoexec
                  candidate_paths:
                    - /autoexec.ipxe
                  esp_image_path: /esp.img
                  esp_script_path: /autoexec.ipxe
                  script: |
                    #!ipxe
                    dhcp
                    chain --autofree https://mailroom.metalstrapper.net/netboot/v1/rendezvous/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val bootPatch = requireNotNull(preparation.bootPatchPlan)

        assertNull(preparation.deliveryPlan)
        assertEquals("netboot_xyz_autoexec", bootPatch.scriptPatches.single().kind)
        assertTrue(bootPatch.scriptPatches.single().script.startsWith("#!ipxe"))
    }

    @Test
    fun rendersNetbootRendezvousLabelInPlaceholderBootPatchPlan() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: ipxe-rendezvous-demo
              name: iPXE Rendezvous Demo
            source:
              id: sparkwriter-ipxe-user-iso
              name: SparkWriter iPXE ISO
              family: netboot
              user_supplied: true
              capabilities:
                - netboot-rendezvous
              adaptation:
                status: patchable_on_import
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /autoexec.ipxe
                  placeholder: http://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                  replacement: http://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
                  encoding: ascii
                  padding: space
                - candidate_paths:
                    - /autoexec.ipxe
                  placeholder: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                  replacement: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot
                  encoding: ascii
                  padding: space
            """.trimIndent(),
        )

        val patches = requireNotNull(manifest.buildLocalDeliveryPreparation(emptyMap()).bootPatchPlan)
            .placeholderPatches
        val ids = patches.map { it.replacement.rendezvousId() }

        assertFalse(patches.any { it.replacement.contains("<rendezvous-id>") })
        assertEquals(1, ids.toSet().size)
        assertTrue(ids.toSet().single().matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(patches.all { it.replacement.endsWith("/boot") })
    }

    @Test
    fun generatesRandomNetbootRendezvousIdShape() {
        val ids = List(8) { generateNetbootRendezvousId() }

        assertTrue(ids.all { it.matches(Regex("[A-Za-z0-9_-]{43}")) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun generatesRandomMailroomCompactArtifactIdShape() {
        val ids = List(8) { generateMailroomCompactArtifactId() }

        assertTrue(ids.all { it.matches(Regex("[A-Za-z0-9]{7}")) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun rendersBootAnswerAndFirstbootUrlsUnderOneRendezvousId() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: mailroom-all-labels
              name: Mailroom All Labels
            templates:
              payload: |
                boot={{Spark.mail.aux_data.boot_url}}
                answer={{Spark.mail.aux_data.answer_url}}
                firstboot={{Spark.mail.aux_data.firstboot_url}}
            actions:
              on_iso_ready:
                - id: render-payload
                  type: render_template
                  template: payload
                  output_var: payload_body
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.boot_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: payload_body
                  media_type: text/plain
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: payload_body
                  media_type: text/plain
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.firstboot_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: payload_body
                  media_type: text/plain
            """.trimIndent(),
        )

        val variables = requireNotNull(manifest.buildLocalDeliveryPreparation(emptyMap()).returnPlan).variables
        val rendezvousId = variables.getValue("Spark.mail.aux_data.rendezvous_id")

        assertTrue(rendezvousId.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(rendezvousId, variables.getValue("Spark.mail.aux_data.boot_label"))
        assertEquals(rendezvousId, variables.getValue("Spark.mail.aux_data.answer_label"))
        assertEquals(rendezvousId, variables.getValue("Spark.mail.aux_data.firstboot_label"))
        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/boot",
            variables.getValue("Spark.mail.aux_data.boot_url"),
        )
        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/answer",
            variables.getValue("Spark.mail.aux_data.answer_url"),
        )
        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/firstboot",
            variables.getValue("Spark.mail.aux_data.firstboot_url"),
        )
    }

    @Test
    fun exposesNetbootRendezvousLabelToWriteCompleteReturnPlan() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: ipxe-rendezvous-post-demo
              name: iPXE Rendezvous Post Demo
            source:
              id: sparkwriter-ipxe-user-iso
              name: SparkWriter iPXE ISO
              family: netboot
              user_supplied: true
              capabilities:
                - netboot-rendezvous
              adaptation:
                status: patchable_on_import
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /autoexec.ipxe
                  placeholder: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
                  replacement: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<label>/boot
                  encoding: ascii
                  padding: space
            templates:
              ipxe: |
                #!ipxe
                chain https://example.test/boot.ipxe
            host_events:
              - event: write_complete
                return_delivery:
                  url: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{netboot_rendezvous_label}}/boot
                  method: PUT
                  delivery_mode: required
                  template: ipxe
                  media_type: application/x-ipxe
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val patchLabel = requireNotNull(preparation.bootPatchPlan)
            .placeholderPatches
            .single()
            .replacement
            .rendezvousId()
        val returnLabel = requireNotNull(preparation.returnPlan)
            .variables
            .getValue("netboot_rendezvous_label")

        assertEquals(patchLabel, returnLabel)
    }

    @Test
    fun rendersMailroomLabelsForBootPatchArtifactsAndWriteReadyReturns() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: proxmox-mailroom-demo
              name: Proxmox Mailroom Demo
            source:
              id: proxmox-ve-ais-generic-netboot
              name: Proxmox VE AIS generic netboot ISO
              user_supplied: true
              capabilities:
                - netboot-rendezvous
              adaptation:
                status: patchable_on_import
                satisfies:
                  - auto_install_mode
                  - answer_fetch.http
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - mailroom_netboot
              base_image_requires:
                - auto_install_mode
                - answer_fetch.http
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /autoexec.ipxe
                  placeholder: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/0000000000000000000000000000000000000000000000000000000000000000
                  replacement: "{{Spark.mail.aux_data.answer_url}}"
                  encoding: ascii
                  padding: space
            templates:
              answer:
                - "[first-boot]"
                - url = "{{Spark.mail.aux_data.firstboot_url}}"
              firstboot:
                - "#!/usr/bin/env bash"
                - "echo ready"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: answer_toml
                - id: create-answer
                  type: create_artifact
                  artifact_id: answer
                  media_type: application/toml
                  content_var: answer_toml
                - id: render-firstboot
                  type: render_template
                  template: firstboot
                  output_var: firstboot_script
                - id: create-firstboot
                  type: create_artifact
                  artifact_id: firstboot
                  media_type: text/x-shellscript
                  content_var: firstboot_script
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: answer_toml
                  media_type: application/toml
              - event: write_ready
                return_delivery:
                  url: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{netboot_rendezvous_id}}/firstboot
                  method: PUT
                  delivery_mode: required
                  content_var: firstboot_script
                  media_type: text/x-shellscript
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val variables = requireNotNull(preparation.returnPlan).variables
        val answerLabel = variables.getValue("Spark.mail.aux_data.answer_label")
        val firstbootLabel = variables.getValue("Spark.mail.aux_data.firstboot_label")
        val rendezvousId = variables.getValue("netboot_rendezvous_id")
        val patchReplacement = requireNotNull(preparation.bootPatchPlan).placeholderPatches.single().replacement
        val deliveries = prepareWriteReadyReturnDeliveries(writeIntent(preparation.returnPlan))

        assertTrue(answerLabel.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(firstbootLabel.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(rendezvousId, answerLabel)
        assertEquals(rendezvousId, firstbootLabel)
        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/answer",
            patchReplacement,
        )
        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/firstboot",
            variables.getValue("Spark.mail.aux_data.firstboot_url"),
        )
        assertEquals(
            variables.getValue("Spark.mail.aux_data.firstboot_url"),
            variables.getValue("Spark.mail.aux_data.firstboot_url"),
        )
        assertTrue(variables.getValue("answer_toml").contains(variables.getValue("Spark.mail.aux_data.firstboot_url")))
        assertEquals(
            setOf(
                "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/answer",
                "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/firstboot",
            ),
            deliveries.map { it.url }.toSet(),
        )
    }

    @Test
    fun normalizesAnswerLabelInRendezvousUrlFields() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: proxmox-legacy-answer-label
              name: Proxmox Legacy Answer Label
            source:
              id: proxmox-ve-ais-generic-netboot
              name: Proxmox VE AIS generic netboot ISO
              user_supplied: true
              capabilities:
                - netboot-rendezvous
              adaptation:
                status: patchable_on_import
                satisfies:
                  - auto_install_mode
                  - answer_fetch.http
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - mailroom
              base_image_requires:
                - auto_install_mode
                - answer_fetch.http
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /auto-installer-mode.toml
                  placeholder: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/0000000000000000000000000000000000000000000000000000000000000000
                  replacement: "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{Spark.mail.aux_data.answer_label}}"
                  encoding: ascii
                  padding: space
            templates:
              answer:
                - "[first-boot]"
                - url = "http://example.test/{{Spark.mail.aux_data.answer_label}}/answer.toml"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: answer_toml
            host_events:
              - event: write_ready
                return_delivery:
                  url: "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{Spark.mail.aux_data.answer_label}}"
                  method: PUT
                  delivery_mode: required
                  content_var: answer_toml
                  media_type: application/toml
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val patchReplacement = requireNotNull(preparation.bootPatchPlan).placeholderPatches.single().replacement
        val returnPlan = requireNotNull(preparation.returnPlan)
        val variables = returnPlan.variables
        val deliveries = prepareWriteReadyReturnDeliveries(writeIntent(returnPlan))
        val answerUrl = deliveries.single().url
        val answerLabel = variables.getValue("Spark.mail.aux_data.answer_label")

        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$answerLabel/answer",
            answerUrl,
        )
        assertEquals(answerUrl, patchReplacement)
        assertTrue(answerUrl.startsWith("https://mailroom.metalstrapper.net/netboot/v1/rendezvous/"))
        assertFalse(answerUrl.endsWith("{{Spark.mail.aux_data.answer_label}}"))
        assertTrue(answerLabel.isNotBlank())
    }

    @Test
    fun returnDeliveryCanReferenceArtifactSpecKeyWhenGeneratedArtifactUsesArtifactId() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: artifact-alias
              name: Artifact Alias
            artifacts:
              answer_toml:
                artifact_id: proxmox-answer
                media_type: application/toml
                template: answer
            templates:
              answer:
                - "[first-boot]"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: rendered_answer
                - id: create-answer
                  type: create_artifact
                  artifact_id: proxmox-answer
                  media_type: application/toml
                  content_var: rendered_answer
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
                  from: artifacts.answer_toml
                  media_type: application/toml
            """.trimIndent(),
        )

        val returnPlan = requireNotNull(manifest.buildLocalDeliveryPreparation(emptyMap()).returnPlan)
        val deliveries = prepareWriteReadyReturnDeliveries(writeIntent(returnPlan))

        assertEquals("[first-boot]\n", deliveries.single().body)
    }

    @Test
    fun rendersSparkMailAuxDataVariablesWithoutBootPatch() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: mailroom-template-only
              name: Mailroom Template Only
            templates:
              answer:
                - "[first-boot]"
                - url = "{{Spark.mail.aux_data.firstboot_url}}"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: answer_toml
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.firstboot_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: answer_toml
                  media_type: application/toml
            """.trimIndent(),
        )

        val variables = requireNotNull(manifest.buildLocalDeliveryPreparation(emptyMap()).returnPlan).variables
        val firstbootUrl = variables.getValue("Spark.mail.aux_data.firstboot_url")

        assertTrue(firstbootUrl.matches(Regex("https://mailroom\\.metalstrapper\\.net/netboot/v1/rendezvous/[A-Za-z0-9_-]{43}/firstboot")))
        assertTrue(variables.getValue("answer_toml").contains(firstbootUrl))
    }

    @Test
    fun doesNotSynthesizeBootPatchForProxmoxLikeHttpAnswerIso() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: proxmox-mailroom-http
              name: Proxmox Mailroom HTTP
            source:
              id: proxmox-ve-ais-generic-http
              name: Proxmox VE AIS generic HTTP answer ISO
              family: proxmox
              user_supplied: true
              adaptation:
                status: patchable_on_import
                satisfies:
                  - auto_install_mode
                  - answer_fetch.http
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - mailroom
              base_image_requires:
                - auto_install_mode
                - answer_fetch.http
            templates:
              answer:
                - mode = "http"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: answer_toml
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: answer_toml
                  media_type: application/toml
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val variables = requireNotNull(preparation.returnPlan).variables
        assertNull(preparation.bootPatchPlan)
        assertTrue(variables.getValue("Spark.mail.aux_data.answer_url").matches(Regex("^https://mailroom\\.metalstrapper\\.net/netboot/v1/rendezvous/[A-Za-z0-9_-]{43}/answer$")))
        assertFalse(variables.getValue("Spark.mail.aux_data.answer_url").endsWith("/${"0".repeat(64)}"))
    }

    @Test
    fun rendersExplicitMailroomBootPatchForProxmoxLikeHttpAnswerIso() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: proxmox-mailroom-http
              name: Proxmox Mailroom HTTP
            source:
              id: proxmox-ve-ais-generic-http
              name: Proxmox VE AIS generic HTTP answer ISO
              family: proxmox
              user_supplied: true
              adaptation:
                status: patchable_on_import
                satisfies:
                  - auto_install_mode
                  - answer_fetch.http
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - mailroom
              base_image_requires:
                - auto_install_mode
                - answer_fetch.http
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /auto-installer-mode.toml
                    - /AUTO_INSTALLER_MODE.TOML
                  placeholder: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/0000000000000000000000000000000000000000000000000000000000000000
                  replacement: "{{Spark.mail.aux_data.answer_url}}"
                  encoding: ascii
                  padding: space
            templates:
              answer:
                - mode = "http"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: answer_toml
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: answer_toml
                  media_type: application/toml
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val variables = requireNotNull(preparation.returnPlan).variables
        val bootPatch = requireNotNull(preparation.bootPatchPlan)
        val patch = bootPatch.placeholderPatches.single()

        assertEquals(
            listOf("/auto-installer-mode.toml", "/AUTO_INSTALLER_MODE.TOML"),
            patch.candidatePaths,
        )
        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(64)}",
            patch.placeholder,
        )
        assertEquals(variables.getValue("Spark.mail.aux_data.answer_url"), patch.replacement)
        assertFalse(patch.replacement.endsWith("/${"0".repeat(64)}"))
    }

    @Test(expected = IllegalStateException::class)
    fun failsMailroomAnswerUrlWithoutRequiredWriteReadyDelivery() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: proxmox-mailroom-http
              name: Proxmox Mailroom HTTP
            source:
              id: proxmox-ve-ais-generic-http
              name: Proxmox VE AIS generic HTTP answer ISO
              user_supplied: true
              family: proxmox
              adaptation:
                status: patchable_on_import
                satisfies:
                  - auto_install_mode
                  - answer_fetch.http
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /auto-installer-mode.toml
                  placeholder: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/0000000000000000000000000000000000000000000000000000000000000000
                  replacement: "{{Spark.mail.aux_data.answer_url}}"
                  encoding: ascii
                  padding: space
            host_events:
              - event: workflow.started
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
            """.trimIndent(),
        )

        manifest.buildLocalDeliveryPreparation(emptyMap())
    }

    @Test
    fun buildsPlaceholderBootPatchWithoutDeliveryPlan() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: ipxe-placeholder-demo
              name: iPXE Placeholder Demo
            source:
              id: custom-ipxe-iso
              name: Custom iPXE ISO
              user_supplied: true
              adaptation:
                status: patchable_on_import
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /ipxe.lkrn
                  placeholder: http://mailroom.invalid/placeholder/0000000000000000000000000000000000000000000000000000000000000000
                  replacement: http://10.0.0.5/jobs/abc123/boot.ipxe
                  encoding: ascii
                  padding: space
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())
        val bootPatch = requireNotNull(preparation.bootPatchPlan)

        assertNull(preparation.deliveryPlan)
        assertEquals("http://10.0.0.5/jobs/abc123/boot.ipxe", bootPatch.placeholderPatches.single().replacement)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsDynamicAuxiliaryNonVfatFilesystem() {
        runtime.parseCombined(appendAfterImageManifest(filesystem = "ext4"))
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsHashMismatchedGeometry() {
        runtime.parseCombined(auxiliaryManifest(baseImageSha256 = "d".repeat(64))).buildAuxiliaryDataPlan(emptyMap())
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsBadOffsetArithmetic() {
        runtime.parseCombined(auxiliaryManifest(byteOffset = 1234)).buildAuxiliaryDataPlan(emptyMap())
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsRelativeDeliveryPath() {
        runtime.parseCombined(auxiliaryManifest(filePath = "answer.toml")).buildAuxiliaryDataPlan(emptyMap())
    }

    @Test
    fun buildsFixedByteRangePlanFromStaticArtifacts() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            source:
              id: proxmox
              name: Proxmox
              sha256: $hash
              adaptation:
                status: preadapted
                satisfies:
                  - auto_install_mode
                  - answer_fetch.iso_placeholder
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - fixed_byte_ranges
              base_image_requires:
                - auto_install_mode
                - answer_fetch.iso_placeholder
            artifacts:
              answer_toml:
                artifact_id: answer
                media_type: application/toml
                template: answer
            delivery:
              mode: fixed_byte_ranges
              base_image_sha256: $hash
              patches:
                - id: answer
                  artifact_id: answer
                  media_type: application/toml
                  logical_name: answer.toml
                  byte_offset: 1024
                  byte_length: 128
                  placeholder:
                    encoding: zeroes
                  padding:
                    strategy: toml-comment
            templates:
              answer:
                - "[global]"
                - fqdn = "{{hostname}}"
            """.trimIndent(),
        )

        val plan = manifest.buildFixedByteRangePlan(mapOf("hostname" to "pve1.example"))

        requireNotNull(plan)
        assertEquals(hash, plan.baseImageSha256)
        val patch = plan.patches.single()
        assertEquals("answer.toml", patch.logicalName)
        assertEquals(1024L, patch.byteOffset)
        assertTrue(patch.content.contains("pve1.example"))
        assertEquals(64, patch.contentSha256.length)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsFixedByteRangeArtifactWhenTemplateFieldWasNotProvided() {
        val manifest = fixedByteRangeManifest(
            template = """
                [global]
                fqdn = "{{hostname}}"
            """.trimIndent(),
        )

        manifest.buildFixedByteRangePlan(emptyMap())
    }

    @Test
    fun rendersHyphenatedFieldThroughJinjaStyleAlias() {
        val manifest = fixedByteRangeManifest(
            template = "password = \"{{root_password}}\"",
        )

        val plan = manifest.buildFixedByteRangePlan(mapOf("root-password" to "secret"))

        requireNotNull(plan)
        assertTrue(plan.patches.single().content.contains("secret"))
    }

    @Test
    fun rendersStaticArtifactWithGeneratedEphemeralPassword() {
        val manifest = fixedByteRangeManifest(
            template = "password = \"{{generated_proxmox_root_password}}\"",
            actions = """
            actions:
              on_iso_ready:
                - id: generate-root-password
                  type: generate_ephemeral_password
                  output_var: generated_proxmox_root_password
            """.trimIndent(),
        )

        val plan = manifest.buildFixedByteRangePlan(emptyMap())

        requireNotNull(plan)
        val content = plan.patches.single().content
        assertTrue(content.startsWith("password = \""))
        assertFalse(content.contains("generated_proxmox_root_password"))
        assertTrue(content.trim().length > "password = \"\"".length)
    }

    @Test
    fun actionConditionsMatchHyphenatedFieldsThroughJinjaStyleAlias() {
        val manifest = fixedByteRangeManifest(
            template = """
                filesystem = "zfs"
                zfs.raid = "{{pve_zfs_raid}}"
            """.trimIndent(),
            actions = """
            actions:
              on_iso_ready:
                - id: render-zfs
                  type: render_template
                  template: answer
                  when:
                    field: pve_disk_filesystem
                    operator: equals
                    value: zfs
                  output_var: answer_toml
                - id: create-answer
                  type: create_artifact
                  artifact_id: answer
                  media_type: application/toml
                  content_var: answer_toml
            """.trimIndent(),
        )

        val plan = manifest.buildFixedByteRangePlan(
            mapOf(
                "pve-disk-filesystem" to "zfs",
                "pve-zfs-raid" to "raid1",
            ),
        )

        val content = requireNotNull(plan).patches.single().content
        assertTrue(content.contains("filesystem = \"zfs\""))
        assertTrue(content.contains("zfs.raid = \"raid1\""))
    }

    @Test
    fun includesGeneratedVariablesInWriteCompleteReturnPlan() {
        val manifest = fixedByteRangeManifest(
            template = "password = \"{{generated_proxmox_root_password}}\"",
            actions = """
            actions:
              on_iso_ready:
                - id: generate-root-password
                  type: generate_ephemeral_password
                  output_var: generated_proxmox_root_password
              on_write_complete:
                - id: render-return
                  type: render_template
                  template: return_body
                  output_var: return_json
            host_events:
              - event: write_complete
                return_delivery:
                  url: https://localhost:3001/api/returns/sparkwriter
                  delivery_mode: required
                  content_var: return_json
            """.trimIndent(),
            extraTemplates = """
              return_body: '{"password":"{{generated_proxmox_root_password}}"}'
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())

        val returnPlan = requireNotNull(preparation.returnPlan)
        assertTrue(returnPlan.variables["generated_proxmox_root_password"].orEmpty().isNotBlank())
        assertEquals("required", returnPlan.hostEvents.single().returnDelivery?.deliveryMode)
    }

    @Test
    fun exposesOnlyOptedInGeneratedUserSecrets() {
        val manifest = fixedByteRangeManifest(
            template = "password = \"{{generated_proxmox_root_password}}\"",
            actions = """
            actions:
              on_iso_ready:
                - id: generate-root-password
                  type: generate_ephemeral_password
                  output_var: generated_proxmox_root_password
                  user_secret: true
                  user_secret_label: Root password
            host_events:
              - event: write_complete
                return_delivery:
                  url: https://localhost:3001/api/returns/sparkwriter
                  content_var: generated_proxmox_root_password
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(emptyMap())

        val secret = preparation.userSecrets.single()
        assertEquals("generated_proxmox_root_password", secret.id)
        assertEquals("Root password", secret.label)
        assertTrue(secret.value.isNotBlank())
        assertEquals(secret.value, preparation.returnPlan?.userSecrets?.single()?.value)
    }

    @Test
    fun doesNotExposeUserProvidedSecretNamedFields() {
        val manifest = fixedByteRangeManifest(
            template = """
                ssh = "{{ssh_public_keys}}"
                password = "{{root_password}}"
            """.trimIndent(),
        )

        val preparation = manifest.buildLocalDeliveryPreparation(
            mapOf(
                "ssh_public_keys" to "ssh-ed25519 AAAA...",
                "root_password" to "already-known",
            ),
        )

        assertTrue(preparation.userSecrets.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsFixedByteRangeArtifactThatStillContainsAnyPatchSlotPlaceholder() {
        val manifest = fixedByteRangeManifest(
            template = "# METALSTRAPPER_PATCH_SLOT firstboot_script v1",
        )

        manifest.buildFixedByteRangePlan(emptyMap())
    }

    @Test
    fun producedAdaptationIsUnavailableForLocalAndroidWrites() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            artifacts:
              answer:
                media_type: text/plain
            source:
              id: proxmox
              name: Proxmox
              sha256: $hash
              adaptation:
                status: produced
                satisfies:
                  - auto_install_mode
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - fixed_byte_ranges
              base_image_requires:
                - auto_install_mode
            delivery:
              mode: fixed_byte_ranges
              base_image_sha256: $hash
              patches:
                - id: answer
                  artifact_id: answer
                  byte_offset: 1024
                  byte_length: 128
            """.trimIndent(),
        )

        assertFalse(manifest.isAndroidRunnable)
        assertTrue(manifest.localAndroidUnavailableReasons().single().contains("adapting service"))
    }

    private fun auxiliaryManifest(
        baseImageSha256: String = hash,
        byteOffset: Long = 512,
        filePath: String = "/answer.toml",
    ): String = """
        version: "3.0"
        metadata:
          id: demo
          name: Demo
        source:
          id: proxmox
          name: Proxmox
          sha256: $hash
        installer:
          scheme: proxmox-auto-install
          accepts_delivery:
            - auxiliary_partition
          auxiliary_partition:
            label: PROXMOX-AIS
            filesystem: vfat
            required_files:
              - /answer.toml
        artifacts:
          answer_toml:
            artifact_id: proxmox-answer
            media_type: application/toml
            template: answer
        delivery:
          mode: auxiliary_partition
          partition:
            label: PROXMOX-AIS
            filesystem: vfat
            size_mb: 1
            placement: preallocated_in_base_image
            geometry:
              base_image_sha256: $baseImageSha256
              partition_table: gpt
              partition_number: 1
              start_lba: 1
              sector_size: 512
              byte_offset: $byteOffset
              byte_length: 1048576
          files:
            $filePath:
              artifact_id: proxmox-answer
              media_type: application/toml
        templates:
          answer: answer = true
    """.trimIndent()

    private fun appendAfterImageManifest(filesystem: String = "vfat"): String = """
        version: "3.0"
        metadata:
          id: ubuntu-demo
          name: Ubuntu Demo
        source:
          id: ubuntu
          name: Ubuntu
          sha256: $hash
        installer:
          scheme: ubuntu-nocloud
          accepts_delivery:
            - auxiliary_partition
          auxiliary_partition:
            label: CIDATA
            filesystem: $filesystem
            required_files:
              - /user-data
              - /meta-data
        artifacts:
          user_data:
            artifact_id: user-data
            media_type: text/cloud-config
            template: user-data
          meta_data:
            artifact_id: meta-data
            media_type: text/plain
            template: meta-data
        delivery:
          mode: auxiliary_partition
          partition:
            label: CIDATA
            filesystem: $filesystem
            size_mb: 64
            placement: append_after_image
          files:
            /user-data:
              artifact_id: user-data
              media_type: text/cloud-config
            /meta-data:
              artifact_id: meta-data
              media_type: text/plain
        templates:
          user-data: |
            #cloud-config
            autoinstall:
              version: 1
          meta-data: |
            instance-id: sparkwriter-test
            local-hostname: {{hostname}}
    """.trimIndent()

    private fun fixedByteRangeManifest(
        template: String,
        actions: String = "",
        extraTemplates: String = "",
    ): SparkManifest {
        val actionsBlock = actions.trim()
        val extraTemplatesBlock = extraTemplates.trim().prependIndent("  ")
        return runtime.parseCombined(
            """
version: "3.0"
metadata:
  id: demo
  name: Demo
source:
  id: proxmox
  name: Proxmox
  sha256: $hash
  adaptation:
    status: preadapted
    satisfies:
      - auto_install_mode
      - answer_fetch.iso_placeholder
installer:
  scheme: proxmox-auto-install
  accepts_delivery:
    - fixed_byte_ranges
  base_image_requires:
    - auto_install_mode
    - answer_fetch.iso_placeholder
artifacts:
  answer_toml:
    artifact_id: answer
    media_type: application/toml
    template: answer
delivery:
  mode: fixed_byte_ranges
  base_image_sha256: $hash
  patches:
    - id: answer
      artifact_id: answer
      media_type: application/toml
      logical_name: answer.toml
      byte_offset: 1024
      byte_length: 128
      placeholder:
        encoding: zeroes
      padding:
        strategy: toml-comment
$actionsBlock
templates:
  answer: |
${template.prependIndent("    ")}
$extraTemplatesBlock
            """.trim(),
        )
    }

    private fun writeIntent(returnPlan: WriteReturnPlan): WriteIntent = WriteIntent(
        deviceId = "usb-1",
        confirmedDeviceId = "usb-1",
        image = VerifiedImage(
            uri = "content://image",
            sha256 = "c".repeat(64),
            sizeBytes = 1024,
            mediaType = "application/x-iso9660-image",
            provenance = "test",
            displayName = "test.iso",
        ),
        returnPlan = returnPlan,
    )

    private fun verifiedImage(sha256: String): VerifiedImage = VerifiedImage(
        uri = "content://image",
        sha256 = sha256,
        sizeBytes = 1024,
        mediaType = "application/x-iso9660-image",
        provenance = "test",
        displayName = "test.iso",
    )

    private fun String.rendezvousId(): String = substringAfter("/rendezvous/").substringBefore("/")

    private fun String.compactArtifactId(): String = substringAfter("/a/").substringBefore("/")

    private fun bundledSparkplug(name: String): File = listOf(
        File("app/bundled/$name"),
        File("../app/bundled/$name"),
        File("examples/$name"),
        File("../examples/$name"),
    ).first { it.isFile }

    private fun docsSparkplug(name: String): File = listOf(
        File("docs/$name"),
        File("../docs/$name"),
    ).first { it.isFile }

    private fun bundledSparkplugFiles(): List<File> = listOf(
        File("app/bundled"),
        File("../app/bundled"),
        File("examples"),
        File("../examples"),
    ).filter { it.isDirectory }
        .flatMap { directory ->
            directory.listFiles { file -> file.extension in setOf("yaml", "yml", "json") }.orEmpty().toList()
        }
        .distinctBy { it.name }
        .sortedBy { it.name }
}
