package net.metalstrapper.sparkwriter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UsbWriteEnqueueTest {
    private val requestId = UUID.randomUUID()

    @Test
    fun acceptsWhenThisRequestIsTheOneEnqueued() = runBlocking {
        var discarded = false
        requireOwnRequestEnqueued(
            requestId = requestId,
            enqueue = {},
            isEnqueued = { it == requestId },
            discardInput = { discarded = true },
        )
        assertFalse(discarded)
    }

    @Test
    fun refusesAndDiscardsInputWhenKeepDroppedThisRequest() {
        var discarded = false
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                requireOwnRequestEnqueued(
                    requestId = requestId,
                    enqueue = {},
                    isEnqueued = { false },
                    discardInput = { discarded = true },
                )
            }
        }
        assertEquals("A USB write is already in progress", error.message)
        assertTrue(discarded)
    }

    @Test
    fun discardsInputWhenTheEnqueueItselfFails() {
        var discarded = false
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                requireOwnRequestEnqueued(
                    requestId = requestId,
                    enqueue = { error("database is locked") },
                    isEnqueued = { true },
                    discardInput = { discarded = true },
                )
            }
        }
        assertTrue(discarded)
    }
}
