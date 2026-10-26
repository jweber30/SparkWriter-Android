package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.ConfigField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileFieldEligibilityTest {
    @Test
    fun recognizedStandardFieldWithProfileScopeParticipates() {
        val field = ConfigField(
            id = "ssh_keys",
            label = "SSH Keys",
            standardField = "user.ssh_public_keys",
            storage = mapOf("scope" to "profile"),
        )

        assertEquals("user.ssh_public_keys", field.profileKey())
    }

    @Test
    fun recognizedStandardFieldWithPersistTrueParticipates() {
        val field = ConfigField(
            id = "email",
            label = "Email",
            standardField = "user.email",
            storage = mapOf("persist" to "true"),
        )

        assertEquals("user.email", field.profileKey())
    }

    @Test
    fun aptProxyStandardFieldParticipates() {
        val field = ConfigField(
            id = "apt_proxy",
            label = "APT Proxy",
            standardField = "apt.proxy",
            storage = mapOf("persist" to "true"),
        )

        assertEquals("apt.proxy", field.profileKey())
    }

    @Test
    fun networkSuffixStandardFieldParticipates() {
        val field = ConfigField(
            id = "domain",
            label = "Network Suffix",
            standardField = "network.suffix",
            storage = mapOf("persist" to "true"),
        )

        assertEquals("network.suffix", field.profileKey())
    }

    @Test
    fun recognizedStandardFieldWithoutStorageOptInDoesNotParticipate() {
        val field = ConfigField(
            id = "email",
            label = "Email",
            standardField = "user.email",
        )

        assertNull(field.profileKey())
    }

    @Test
    fun unknownStandardFieldWithStorageOptInDoesNotParticipate() {
        val field = ConfigField(
            id = "favorite_color",
            label = "Favorite Color",
            standardField = "user.favorite_color",
            storage = mapOf("scope" to "profile"),
        )

        assertNull(field.profileKey())
    }

    @Test
    fun blankStandardFieldDoesNotParticipate() {
        val field = ConfigField(
            id = "email",
            label = "Email",
            standardField = "",
            storage = mapOf("scope" to "profile"),
        )

        assertNull(field.profileKey())
    }

    @Test
    fun missingStandardFieldDoesNotParticipate() {
        val field = ConfigField(
            id = "email",
            label = "Email",
            storage = mapOf("scope" to "profile"),
        )

        assertNull(field.profileKey())
    }

    @Test
    fun profileValuesAreKeyedByStandardFieldNotManifestFieldId() {
        val field = ConfigField(
            id = "author_email",
            label = "Email",
            standardField = "user.email",
            storage = mapOf("scope" to "profile"),
        )

        val values = listOf(field).profileValues(mapOf("author_email" to "writer@example.com"))

        assertEquals(mapOf("user.email" to "writer@example.com"), values)
    }

    @Test
    fun blankProfileValuesAreNotSaved() {
        val field = ConfigField(
            id = "author_email",
            label = "Email",
            standardField = "user.email",
            storage = mapOf("scope" to "profile"),
        )

        val values = listOf(field).profileValues(mapOf("author_email" to ""))

        assertEquals(emptyMap<String, String>(), values)
    }

    @Test
    fun passwordFieldCannotParticipateInProfilePersistence() {
        val field = ConfigField(
            id = "password",
            label = "Password",
            type = "password",
            standardField = "user.email",
            storage = mapOf("persist" to "true"),
        )

        assertNull(field.profileKey())
    }

    @Test
    fun wifiPasswordIsNotARecognizedProfileField() {
        val field = ConfigField(
            id = "wifi_password",
            label = "Wi-Fi Password",
            standardField = "network.wifi.password",
            storage = mapOf("persist" to "true"),
        )

        assertNull(field.profileKey())
    }
}
