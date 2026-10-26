package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAcquisitionTest {
    private val runtime = ManifestRuntime()

    @Test
    fun directSourceUsesExternalDownloadPlan() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {"id": "ubuntu", "name": "Ubuntu", "url": "https://example.com/u.iso"}
            }
            """.trimIndent(),
        )

        assertEquals(ImageAcquisitionPlan.Direct("https://example.com/u.iso"), manifest.imageAcquisitionPlan())
    }

    @Test
    fun torrentSourceUsesExternalTorrentPlan() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {
                "id": "ubuntu",
                "name": "Ubuntu",
                "acquire": {
                  "kind": "torrent",
                  "url": "https://example.com/u.torrent",
                  "artifact": "u.iso"
                }
              }
            }
            """.trimIndent(),
        )

        val plan = manifest.imageAcquisitionPlan()

        assertTrue(plan is ImageAcquisitionPlan.Torrent)
        assertEquals("u.iso", (plan as ImageAcquisitionPlan.Torrent).artifact)
    }

    @Test
    fun magnetTorrentSourceCanOpenExternalHandler() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {
                "id": "ubuntu",
                "name": "Ubuntu",
                "acquire": {
                  "kind": "torrent",
                  "url": "magnet:?xt=urn:btih:abc"
                }
              }
            }
            """.trimIndent(),
        )

        val plan = manifest.imageAcquisitionPlan()

        assertTrue(plan is ImageAcquisitionPlan.Torrent)
        assertEquals("magnet:?xt=urn:btih:abc", (plan as ImageAcquisitionPlan.Torrent).torrentUrl)
    }
}
