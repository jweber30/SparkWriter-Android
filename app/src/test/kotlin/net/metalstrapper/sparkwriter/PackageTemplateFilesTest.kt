package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.core.PackageFileRef
import net.metalstrapper.sparkwriter.fixtures.Fixtures
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageTemplateFilesTest {
    private val runtime = ManifestRuntime()

    @Test
    fun mapsExplicitTemplateRoleToTemplateId() {
        val file = PackageFileRef(
            role = "template:firstboot_script",
            url = "files/firstboot_template",
            sha256 = "a".repeat(64),
        )

        assertEquals("firstboot_script", file.templateId("firstboot_template"))
    }

    @Test
    fun sourceMetadataParticipatesInRenderContext() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {
                "id": "source",
                "name": "Source",
                "metadata": {
                  "glint_client_url": "https://glint.example/client",
                  "site_hostname": "pve1.example"
                }
              },
              "config_fields": [
                {"id": "pve_site_token", "label": "PVE Site Token"}
              ]
            }
            """.trimIndent(),
        )

        val values = manifest.activeConfigValues(
            mapOf("pve_site_token" to "token-test"),
        )

        assertEquals("https://glint.example/client", values["glint_client_url"])
        assertEquals("pve1.example", values["site_hostname"])
        assertEquals("token-test", values["pve_site_token"])
    }

    @Test
    fun formValuesOverrideSourceMetadata() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {
                "id": "source",
                "name": "Source",
                "metadata": {"site_hostname": "metadata.example"}
              },
              "config_fields": [
                {"id": "site_hostname", "label": "Site Hostname"}
              ]
            }
            """.trimIndent(),
        )

        val values = manifest.activeConfigValues(
            mapOf("site_hostname" to "form.example"),
        )

        assertEquals("form.example", values["site_hostname"])
    }

    @Test
    fun arrayFieldsExposeTomlTemplateValue() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            config_fields:
              - id: pve-disk-list
                label: Proxmox Install Disks
                type: array
                default:
                  - sda
                  - sdb
            """.trimIndent(),
        )

        val defaultValues = manifest.activeConfigValues(
            mapOf("pve-disk-list" to manifest.configFields.single().default),
        )
        val editedValues = manifest.activeConfigValues(
            mapOf("pve-disk-list" to "nvme0n1\nnvme1n1"),
        )

        assertEquals("""["sda", "sdb"]""", defaultValues["pve_disk_list_toml"])
        assertEquals("""["nvme0n1", "nvme1n1"]""", editedValues["pve_disk_list_toml"])
    }

    @Test
    fun pveFieldsDoNotExposeProxmoxTemplateAlias() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            config_fields:
              - id: pve-zfs-raid
                label: ZFS RAID
                default: raid1
                when:
                  field: pve_disk_filesystem
                  operator: equals
                  value: zfs
              - id: pve-disk-filesystem
                label: Filesystem
                default: zfs
            """.trimIndent(),
        )

        val values = manifest.activeConfigValues(
            mapOf(
                "pve-disk-filesystem" to "zfs",
                "pve-zfs-raid" to "raid1",
            ),
        )

        assertEquals("raid1", values["pve-zfs-raid"])
        assertNull(values["proxmox_zfs_raid"])
        assertEquals("raid1", values["pve_zfs_raid"])
    }

    @Test
    fun defaultPrefixedFieldExposesCanonicalAlias() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            config_fields:
              - id: default.timezone
                label: Installation Timezone
                default: UTC
            """.trimIndent(),
        )

        val values = manifest.activeConfigValues(
            mapOf("timezone" to "America/Denver"),
        )

        assertEquals("America/Denver", values["timezone"])
        assertEquals("America/Denver", values["default.timezone"])
    }

    @Test
    fun unprefixedFieldsDoNotExposeLegacyPveTemplateAlias() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            config_fields:
              - id: site_token
                label: Site Token
                type: password
            """.trimIndent(),
        )

        val values = manifest.activeConfigValues(mapOf("site_token" to "token-test"))

        assertEquals("token-test", values["site_token"])
        assertNull(values["pve_site_token"])
    }

    @Test
    fun bundledUbuntuAutoinstallerUsesDynamicCidataAppend() {
        val manifest = runtime.parseCombined(Fixtures.minimalManifestJson)

        val plan = manifest.buildAuxiliaryDataPlan(
            manifest.activeConfigValues(
                mapOf(
                    "hostname" to "ubuntu-test",
                    "username" to "ubuntu",
                ),
            ),
        )

        requireNotNull(plan)
        assertEquals("CIDATA", plan.label)
        assertEquals("vfat", plan.filesystem)
        assertEquals("append_after_image", plan.placement)
        assertNull(plan.geometry)
        assertEquals("dec49008a71f6098d0bcfc822021f4d042d5f2db279e4d75bdd981304f1ca5d9", plan.sourceSha256)
        assertTrue(plan.files.single { it.path == "/user-data" }.content.contains("autoinstall:"))
        assertTrue(plan.files.single { it.path == "/user-data" }.content.contains("proxy: \"http://apt-proxy.srv.home.arpa:3142\""))
        assertTrue(plan.files.single { it.path == "/meta-data" }.content.contains("ubuntu-test"))
    }
}
