package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class BaseImageStoreTest {
    private val runtime = ManifestRuntime()

    @Test
    fun directImageUsesAppOwnedManifestDirectory() {
        val filesDir = tempDir()
        val manifest = manifestWithHash("abc123")
        val store = BaseImageStore(filesDir)

        val target = store.directImageFile(manifest, "https://example.com/images/u.iso?mirror=1")

        assertEquals(
            File(filesDir, "sparkwriter/images/demo/u.iso").canonicalFile,
            target,
        )
    }

    @Test
    fun verifiedImageReturnsCachedImageWhenHashMatches() {
        val filesDir = tempDir()
        val bytes = "image-bytes".toByteArray()
        val manifest = manifestWithHash(bytes.sha256())
        val store = BaseImageStore(filesDir)
        val target = store.directImageFile(manifest, "https://example.com/u.iso")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)

        val image = store.verifiedImage(manifest, target, "https://example.com/u.iso")

        assertNotNull(image)
        assertEquals(bytes.size.toLong(), image?.sizeBytes)
        assertEquals("u.iso", image?.displayName)
    }

    @Test
    fun verifiedImageRejectsCachedImageWhenHashDiffers() {
        val filesDir = tempDir()
        val manifest = manifestWithHash("0".repeat(64))
        val store = BaseImageStore(filesDir)
        val target = store.directImageFile(manifest, "https://example.com/u.iso")
        target.parentFile?.mkdirs()
        target.writeText("not-the-expected-image")

        assertNull(store.verifiedImage(manifest, target, "https://example.com/u.iso"))
    }

    @Test
    fun cachedVerifiedImageReturnsDirectDownloadWhenHashMatches() {
        val filesDir = tempDir()
        val bytes = "direct-image".toByteArray()
        val manifest = manifestWithHash(bytes.sha256())
        val store = BaseImageStore(filesDir)
        val target = store.directImageFile(manifest, "https://example.com/u.iso")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)

        val image = store.cachedVerifiedImage(manifest)

        assertNotNull(image)
        assertEquals(target.toURI().toString(), image?.uri)
        assertEquals(bytes.sha256(), image?.sha256)
    }

    @Test
    fun cachedVerifiedImageReturnsTorrentArtifactWhenHashMatches() {
        val filesDir = tempDir()
        val bytes = "torrent-image".toByteArray()
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {
                "id": "ubuntu",
                "name": "Ubuntu",
                "sha256": "${bytes.sha256()}",
                "acquire": {
                  "kind": "torrent",
                  "url": "https://example.com/u.torrent",
                  "artifact": "images/u.iso"
                }
              }
            }
            """.trimIndent(),
        )
        val store = BaseImageStore(filesDir)
        val target = store.artifactImageFile(manifest, "images/u.iso")!!
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)

        val image = store.cachedVerifiedImage(manifest)

        assertNotNull(image)
        assertEquals(target.toURI().toString(), image?.uri)
        assertEquals("u.iso", image?.displayName)
    }

    @Test
    fun verifiedImageRequiresDeclaredHash() {
        val filesDir = tempDir()
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {"id": "ubuntu", "name": "Ubuntu", "url": "https://example.com/u.iso"}
            }
            """.trimIndent(),
        )
        val store = BaseImageStore(filesDir)
        val target = store.directImageFile(manifest, "https://example.com/u.iso")
        target.parentFile?.mkdirs()
        target.writeText("image")

        assertNull(store.verifiedImage(manifest, target, "https://example.com/u.iso"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun artifactImageRejectsPathEscape() {
        BaseImageStore(tempDir()).artifactImageFile(manifestWithHash("abc123"), "../escape.iso")
    }

    private fun manifestWithHash(sha256: String) = runtime.parseCombined(
        """
        {
          "version": "3.0",
          "metadata": {"id": "demo", "name": "Demo"},
          "source": {
            "id": "ubuntu",
            "name": "Ubuntu",
            "url": "https://example.com/u.iso",
            "sha256": "$sha256"
          }
        }
        """.trimIndent(),
    )

    private fun ByteArray.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }

    private fun tempDir(): File = Files.createTempDirectory("sparkwriter-test").toFile()
}
