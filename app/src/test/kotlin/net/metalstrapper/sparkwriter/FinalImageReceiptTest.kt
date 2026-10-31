package net.metalstrapper.sparkwriter

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.metalstrapper.sparkwriter.core.ByteExtent
import net.metalstrapper.sparkwriter.core.FinalImageDigest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinalImageReceiptTest {
    private val intent = WriteIntent(
        deviceId = "1002",
        confirmedDeviceId = "1002",
        image = VerifiedImage(
            uri = "file:///data/imports/base.iso",
            sha256 = "c".repeat(64),
            sizeBytes = 4096,
            mediaType = "application/x-iso9660-image",
            provenance = "content://provider/base.iso",
            displayName = "base.iso",
        ),
    )

    @Test
    fun completedReceiptCarriesTheFinalImageDigestAndExtents() {
        val digest = FinalImageDigest(
            sha256 = "d".repeat(64),
            totalBytes = 5120,
            extents = listOf(ByteExtent(0, 4608), ByteExtent(1_000_000, 512)),
        )

        val receipt = buildReceiptPayload("r1", intent, WriteStatus.COMPLETED, 0L, null, digest)

        assertEquals("usb_readback", receipt["final_verification"]!!.jsonPrimitive.content)
        assertEquals("d".repeat(64), receipt["final_image_sha256"]!!.jsonPrimitive.content)
        assertEquals(5120L, receipt["final_image_bytes"]!!.jsonPrimitive.long)
        val extents = receipt["final_image_extents"]!!.jsonArray.map {
            it.jsonObject["offset"]!!.jsonPrimitive.long to it.jsonObject["length"]!!.jsonPrimitive.long
        }
        assertEquals(listOf(0L to 4608L, 1_000_000L to 512L), extents)
    }

    @Test
    fun receiptSaysWhenNoFinalVerificationRan() {
        val receipt = buildReceiptPayload("r2", intent, WriteStatus.COMPLETED, 0L, null, null)

        assertEquals("not_performed", receipt["final_verification"]!!.jsonPrimitive.content)
        assertNull(receipt["final_image_sha256"])
    }
}
