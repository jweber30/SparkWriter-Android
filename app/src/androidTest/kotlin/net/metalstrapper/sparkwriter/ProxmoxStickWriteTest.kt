package net.metalstrapper.sparkwriter

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkInfo
import kotlinx.coroutines.runBlocking
import net.metalstrapper.sparkwriter.agent.loadOfferedSparkPlugs
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.storage.IsoIndexStore
import net.metalstrapper.sparkwriter.usb.AndroidUsbHostBlockWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Writes the bundled Proxmox SparkPlug to a virtual USB stick the way the Write button
 * does: same plan building, same boot-patch template, same [UsbImageWriteWorker], so the
 * stick can then be booted in a VM (tools/emulator-test/boot-stick.sh).
 *
 * Opt-in; skipped unless the `baseIso` instrumentation argument names the base ISO on the
 * device and a "QEMU USB HARDDRIVE" is attached. Field values match
 * AssembleProxmoxIsoHarness's DHCP case. answer.toml, first-boot.sh and the generated
 * root password go to the app's external files dir under proxmox-stick/.
 */
@RunWith(AndroidJUnit4::class)
class ProxmoxStickWriteTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val usbManager = context.getSystemService(UsbManager::class.java)

    @Test
    fun writeBundledProxmoxPlugToVirtualStick(): Unit = runBlocking {
        val basePath = InstrumentationRegistry.getArguments().getString("baseIso")
        assumeTrue("No baseIso argument; see tools/emulator-test/README.md", !basePath.isNullOrBlank())
        val base = File(basePath!!)
        assertTrue("Base ISO not found on device: $base", base.isFile)

        val stick = AndroidUsbHostBlockWriter(context, usbManager).listDevices()
            .filter { it.displayName == VIRTUAL_STICK_NAME }
        assumeTrue("No $VIRTUAL_STICK_NAME attached", stick.size == 1)
        val deviceId = stick.single().id
        grantPermission(deviceId)
        val confirmedDevice = requireNotNull(
            AndroidUsbHostBlockWriter(context, usbManager).probe(deviceId).fingerprint,
        ) { "Probe returned no fingerprint" }

        val manifest = loadOfferedSparkPlugs(context).single { it.metadata.id == "proxmox-autoinstall" }
        val sha256 = sha256Hex(base)
        assertEquals("Base ISO does not match the pinned SHA-256", manifest.source!!.sha256!!.lowercase(), sha256)
        val uri = Uri.fromFile(base).toString()
        val image = VerifiedImage(
            uri = uri,
            sha256 = sha256,
            sizeBytes = base.length(),
            mediaType = manifest.source!!.mediaType ?: "",
            provenance = uri,
            displayName = base.name,
        )

        val preparation = manifest.buildLocalDeliveryPreparation(manifest.activeConfigValues(DHCP_VALUES), image)
        val deliveryPlan = preparation.deliveryPlan
        val fixedByteRanges = (deliveryPlan as? LocalDeliveryPlan.FixedByteRanges)?.plan
        val bootPatchTemplate = prepareBootPatchTemplate(
            context = context,
            isoIndexStore = IsoIndexStore(context.applicationContext),
            manifest = manifest,
            image = image,
            bootPatch = preparation.bootPatchPlan,
        )

        var lastLogged = -1
        val info = writeUsbWithWorkManager(
            context = context,
            deviceId = deviceId,
            confirmedDevice = confirmedDevice,
            image = image,
            verifyAfter = true,
            auxiliaryData = (deliveryPlan as? LocalDeliveryPlan.Auxiliary)?.plan,
            fixedByteRanges = fixedByteRanges,
            bootPatch = preparation.bootPatchPlan,
            bootPatchTemplate = bootPatchTemplate,
            returnPlan = preparation.returnPlan,
            tags = manifest.writeTags(),
            onProgress = { fraction, text ->
                val percent = (fraction * 100).toInt()
                if (percent / 10 != lastLogged / 10) Log.i(TAG, "$percent% $text")
                lastLogged = percent
            },
        )
        assertEquals(info.outputData.getString(UsbWriteData.KEY_MESSAGE), WorkInfo.State.SUCCEEDED, info.state)

        val out = File(context.getExternalFilesDir(null), "proxmox-stick").apply { mkdirs() }
        fixedByteRanges?.patches?.forEach { patch ->
            when (patch.id) {
                "answer_toml" -> File(out, "answer.toml").writeText(patch.content)
                "firstboot_script" -> File(out, "first-boot.sh").writeText(patch.content)
            }
        }
        File(out, "root-password.txt").writeText(
            preparation.userSecrets.joinToString("\n") { "${it.label}: ${it.value}" } + "\n",
        )
        Log.i(TAG, "PROXMOX_STICK_WRITTEN bytes=${image.sizeBytes} out=${out.path}")
    }

    private fun grantPermission(deviceId: String) {
        val device = usbManager.deviceList.values.single { it.deviceId.toString() == deviceId }
        if (usbManager.hasPermission(device)) return
        runBlocking { AndroidUsbHostBlockWriter(context, usbManager).requestPermission(deviceId) }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .wait(Until.findObject(By.res("android", "button1")), 10_000)?.click()
        val deadline = System.currentTimeMillis() + 10_000
        while (!usbManager.hasPermission(device) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("USB permission was not granted", usbManager.hasPermission(device))
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

    private companion object {
        const val TAG = "ProxmoxStickWriteTest"
        const val VIRTUAL_STICK_NAME = "QEMU USB HARDDRIVE"

        /** AssembleProxmoxIsoHarness's DHCP values, so the two paths can be compared. */
        val DHCP_VALUES = mapOf(
            "pve_fqdn" to "pve-test.example.invalid",
            "pve_keyboard" to "en-us",
            "pve_country" to "us",
            "pve_timezone" to "America/Denver",
            "pve_mailto" to "root@example.invalid",
            "pve_disk_filesystem" to "ext4",
            "pve_disk_list" to """["sda"]""",
            "pve_zfs_raid" to "",
            "pve_root_ssh_keys" to "[]",
            "pve_reboot_mode" to "reboot",
            "pve_community_repos" to "community",
            "pve_firstboot_command" to "",
            "pve_network_source" to "from-dhcp",
        )
    }
}
