package net.metalstrapper.sparkwriter

import kotlinx.coroutines.runBlocking
import net.metalstrapper.sparkwriter.core.ConfigFetchSpec
import net.metalstrapper.sparkwriter.core.HostEventSubscription
import net.metalstrapper.sparkwriter.core.ManifestMetadata
import net.metalstrapper.sparkwriter.core.ReturnDeliverySpec
import net.metalstrapper.sparkwriter.core.SparkManifest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalServiceGateTest {
    private var gateWasEnabled = false

    @Before
    fun disableExternalServices() {
        gateWasEnabled = ExternalServiceGate.enabled
        ExternalServiceGate.enabled = false
    }

    @After
    fun restoreExternalServices() {
        ExternalServiceGate.enabled = gateWasEnabled
    }

    @Test
    fun releaseBuildDisablesExternalServices() {
        assertFalse(BuildConfig.EXTERNAL_SERVICES_ENABLED)
    }

    // The gate throws before any connection; 127.0.0.1:9 would fail differently if reached.
    @Test
    fun returnDeliveryIsRefusedBeforeConnecting() {
        assertThrows(ExternalServicesDisabledException::class.java) {
            runBlocking { deliverReturnDelivery("POST", "https://127.0.0.1:9/return", "{}", "application/json") }
        }
    }

    @Test
    fun mailroomPublishIsRefusedBeforeConnecting() {
        val error = assertThrows(ExternalServicesDisabledException::class.java) {
            runBlocking {
                deliverReturnDelivery(
                    "PUT",
                    "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/x/answer",
                    "{}",
                    "text/plain",
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("Mailroom"))
    }

    @Test
    fun fieldFetchIsRefusedBeforeConnecting() {
        assertThrows(ExternalServicesDisabledException::class.java) {
            runBlocking { fetchConfigFieldText(ConfigFetchSpec(url = "https://127.0.0.1:9/value")) }
        }
    }

    @Test
    fun storedManifestFromAnOlderBuildIsMarkedUnavailable() {
        val stored = SparkManifest(
            version = "3.0",
            metadata = ManifestMetadata(id = "old", name = "Old"),
            hostEvents = listOf(
                HostEventSubscription("write_complete", ReturnDeliverySpec(url = "https://example.invalid/r")),
            ),
        )
        assertTrue(stored.isAndroidRunnable)
        assertFalse(stored.withReleaseExternalServicePolicy().isAndroidRunnable)
    }
}
