package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class BaseImageFingerprintTest {
    private val runtime = ManifestRuntime()

    @Test
    fun fingerprintReadsPrefixHashAndIsoPrimaryVolumeDescriptor() {
        val image = isoBytes(volumeId = "UBUNTU_SERVER", applicationId = "GRUB")

        val fingerprint = image.toBaseImageFingerprint()

        assertEquals(image.copyOfRange(0, BASE_IMAGE_PREFIX_BYTES).sha256(), fingerprint.first64KSha256)
        assertEquals("UBUNTU_SERVER", fingerprint.isoVolumeId)
        assertEquals("GRUB", fingerprint.isoApplicationId)
        assertEquals(image.copyOfRange(0x8000, 0x8000 + 2048).sha256(), fingerprint.pvdSha256)
    }

    @Test
    fun matcherUsesSizeThenPrefixForExactSourceCandidates() {
        val image = isoBytes(volumeId = "UBUNTU_SERVER")
        val fingerprint = image.toBaseImageFingerprint()
        val matching = manifest(
            id = "ubuntu-autoinstall",
            fullSha256 = image.sha256(),
            sizeBytes = fingerprint.sizeBytes,
            first64KSha256 = fingerprint.first64KSha256,
        )
        val wrongPrefix = manifest(
            id = "other",
            fullSha256 = "1".repeat(64),
            sizeBytes = fingerprint.sizeBytes,
            first64KSha256 = "2".repeat(64),
        )

        val matches = matchBaseImageFingerprint(fingerprint, listOf(wrongPrefix, matching))

        assertEquals(listOf(matching), matches.prefixMatches)
        assertEquals(listOf(wrongPrefix), matches.sizeOnlyMatches)
        assertTrue(matches.exactHashMatches.isEmpty())
        assertTrue(matches.pvdMatches.isEmpty())
    }

    @Test
    fun matcherConfirmsExactMatchWhenFullHashIsAvailable() {
        val image = isoBytes(volumeId = "UBUNTU_SERVER")
        val fingerprint = image.toBaseImageFingerprint()
        val manifest = manifest(
            id = "ubuntu-autoinstall",
            fullSha256 = image.sha256(),
            sizeBytes = fingerprint.sizeBytes,
            first64KSha256 = fingerprint.first64KSha256,
        )

        val matches = matchBaseImageFingerprint(fingerprint, listOf(manifest), fullSha256 = image.sha256())

        assertEquals(listOf(manifest), matches.exactHashMatches)
        assertEquals(listOf(manifest), matches.prefixMatches)
    }

    @Test
    fun matcherFallsBackToPvdWhenNoPrefixCandidateMatches() {
        val image = isoBytes(volumeId = "FEDORA", applicationId = "ANACONDA")
        val fingerprint = image.toBaseImageFingerprint()
        val compatible = manifest(
            id = "fedora-compatible",
            pvdSha256 = fingerprint.pvdSha256,
        )

        val matches = matchBaseImageFingerprint(fingerprint, listOf(compatible))

        assertTrue(matches.hasAnyMatch)
        assertTrue(matches.prefixMatches.isEmpty())
        assertEquals(listOf(compatible), matches.pvdMatches)
    }

    @Test
    fun matcherRecognizesBundledDebianNetinstFingerprint() {
        val manifest = runtime.parseWorkflowFile(docsSparkplug("debian-netinst-preseed.sparkplug.yaml"))
        val fingerprint = BaseImageFingerprint(
            sizeBytes = 791674880L,
            first64KSha256 = "a3c32ee35ae8daeac6cfa2570ddb3394d830e2a324730085637ff295ff6f340b",
            pvdSha256 = "372d18c11098696da11b5f2bc7d8088f1ddcb6565c482cf140deab0497984f68",
        )

        val matches = matchBaseImageFingerprint(fingerprint, listOf(manifest))

        assertEquals(listOf(manifest), matches.prefixMatches)
    }

    private fun bundledSparkplug(name: String): java.io.File = listOf(
        java.io.File("app/bundled/$name"),
        java.io.File("../app/bundled/$name"),
    ).first { it.isFile }

    private fun docsSparkplug(name: String): java.io.File = listOf(
        java.io.File("docs/$name"),
        java.io.File("../docs/$name"),
    ).first { it.isFile }

    private fun manifest(
        id: String,
        fullSha256: String? = null,
        sizeBytes: Long? = null,
        first64KSha256: String? = null,
        pvdSha256: String? = null,
    ) = runtime.parseCombined(
        """
        {
          "version": "3.0",
          "metadata": {"id": "$id", "name": "$id"},
          "source": {
            "id": "$id-source",
            "name": "$id source",
            "sha256": ${fullSha256?.let { "\"$it\"" } ?: "null"},
            "size_bytes": ${sizeBytes ?: "null"},
            "first_64k_sha256": ${first64KSha256?.let { "\"$it\"" } ?: "null"},
            "pvd_sha256": ${pvdSha256?.let { "\"$it\"" } ?: "null"}
          }
        }
        """.trimIndent(),
    )

    private fun isoBytes(
        volumeId: String,
        applicationId: String = "SPARKWRITER_TEST",
    ): ByteArray {
        val bytes = ByteArray(BASE_IMAGE_PREFIX_BYTES + 4096) { index -> (index % 251).toByte() }
        val pvdOffset = 0x8000
        bytes[pvdOffset] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = pvdOffset + 1)
        volumeId.padEnd(32, ' ').toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = pvdOffset + 40)
        applicationId.padEnd(128, ' ').toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = pvdOffset + 574)
        return bytes
    }

    private fun ByteArray.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }
}
