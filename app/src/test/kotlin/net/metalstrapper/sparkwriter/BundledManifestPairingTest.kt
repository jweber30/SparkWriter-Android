package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.manifest.ManifestValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class BundledManifestPairingTest {
    private val runtime = ManifestRuntime()
    private val bundled = listOf(File("app/bundled"), File("bundled")).first { it.isDirectory }

    @Test
    fun pairsEveryBundledWorkflowWithItsImage() {
        val manifests = pairBundledManifests(runtime, workflows(), images())

        assertEquals(listOf("proxmox-autoinstall"), manifests.map { it.metadata.id })
        assertEquals("metalstrapper-proxmox-generic", manifests.single().source?.id)
        assertEquals("bundled:proxmox-autoinstall.sparkplug.yaml", manifests.single().installOrigin)
    }

    @Test
    fun failsClearlyWhenAnImageIsMissing() {
        val error = assertThrows(ManifestValidationException::class.java) {
            pairBundledManifests(runtime, workflows(), images = emptyMap())
        }
        assertEquals(
            "Bundled workflow proxmox-autoinstall.sparkplug.yaml needs missing image metalstrapper-proxmox-generic",
            error.message,
        )
    }

    private fun workflows(): Map<String, String> =
        bundled.listFiles { file -> file.isFile }.orEmpty().associate { it.name to it.readText() }

    private fun images(): Map<String, String> =
        bundled.resolve("images").listFiles().orEmpty().associate { it.name to it.readText() }
}
