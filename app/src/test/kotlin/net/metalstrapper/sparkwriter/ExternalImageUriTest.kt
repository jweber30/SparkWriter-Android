package net.metalstrapper.sparkwriter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalImageUriTest {
    private val pkg = "net.metalstrapper.sparkwriter"

    @Test
    fun acceptsContentUriFromAnotherApp() {
        assertTrue(isAcceptableExternalImageUri("content", "com.android.providers.downloads.documents", pkg))
        assertTrue(isAcceptableExternalImageUri("CONTENT", "org.proninyaroslav.libretorrent.fileprovider", pkg))
    }

    @Test
    fun rejectsFileUris() {
        assertFalse(isAcceptableExternalImageUri("file", null, pkg))
        assertFalse(isAcceptableExternalImageUri("file", "", pkg))
    }

    @Test
    fun rejectsOtherSchemes() {
        assertFalse(isAcceptableExternalImageUri("https", "example.com", pkg))
        assertFalse(isAcceptableExternalImageUri(null, null, pkg))
    }

    @Test
    fun rejectsOwnProviderAuthorities() {
        assertFalse(isAcceptableExternalImageUri("content", pkg, pkg))
        assertFalse(isAcceptableExternalImageUri("content", "$pkg.androidx-startup", pkg))
        assertFalse(isAcceptableExternalImageUri("content", "0@$pkg.workmanager-init", pkg))
    }

    @Test
    fun rejectsMissingAuthority() {
        assertFalse(isAcceptableExternalImageUri("content", null, pkg))
    }
}
