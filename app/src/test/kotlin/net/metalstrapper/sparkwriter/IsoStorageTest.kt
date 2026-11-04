package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.storage.IsoRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IsoStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun imported(filesDir: File, name: String, ageSeconds: Long): File =
        stagedImportsDir(filesDir).apply { mkdirs() }.resolve("$name.iso").apply {
            writeText(name)
            setLastModified(System.currentTimeMillis() - ageSeconds * 1000)
        }

    @Test
    fun keepRecentKeepsNewestIncludingProtectedImage() {
        val filesDir = temp.newFolder()
        val oldest = imported(filesDir, "a", 300)
        val middle = imported(filesDir, "b", 200)
        val newest = imported(filesDir, "c", 100)

        applyRetention(filesDir, IsoRetention.KeepRecent, maxImages = 2, protect = newest)

        assertTrue(newest.exists() && middle.exists())
        assertFalse(oldest.exists())
    }

    @Test
    fun deleteAfterWriteKeepsOnlyProtectedImage() {
        val filesDir = temp.newFolder()
        val other = imported(filesDir, "a", 200)
        val staged = imported(filesDir, "b", 100)

        applyRetention(filesDir, IsoRetention.DeleteAfterWrite, maxImages = 5, protect = staged)

        assertTrue(staged.exists())
        assertFalse(other.exists())
    }

    @Test
    fun keepUntilDeletedNeverPrunes() {
        val filesDir = temp.newFolder()
        val files = listOf(imported(filesDir, "a", 300), imported(filesDir, "b", 200))

        applyRetention(filesDir, IsoRetention.KeepUntilDeleted, maxImages = 1, protect = null)

        assertTrue(files.all { it.exists() })
    }

    @Test
    fun deleteRefusesFilesOutsideImageStorage() {
        val filesDir = temp.newFolder()
        val outside = temp.newFile("outside.iso")

        val deleted = deleteStoredImage(filesDir, StoredImage(outside, 0, 0, StoredImage.Source.Imported))

        assertFalse(deleted)
        assertTrue(outside.exists())
    }

    @Test
    fun listIncludesImportedAndDownloadedNewestFirst() {
        val filesDir = temp.newFolder()
        imported(filesDir, "a", 100)
        File(filesDir, "sparkwriter/images/demo").apply { mkdirs() }.resolve("u.iso").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - 50_000)
        }

        val listed = listStoredImages(filesDir)

        assertEquals(listOf("u.iso", "a.iso"), listed.map { it.file.name })
    }
}
