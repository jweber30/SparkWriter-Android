package net.metalstrapper.sparkwriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {
    @Test
    fun keepsSchemeAndHostButDropsPathQueryAndFragment() {
        val safe = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/AbC123?token=s3cret#frag".logSafe()

        assertTrue(safe, safe.matches(Regex("""https://mailroom\.metalstrapper\.net/… #[0-9a-f]{6}""")))
        assertFalse(safe.contains("s3cret") || safe.contains("AbC123") || safe.contains("rendezvous"))
    }

    @Test
    fun dropsUserInfo() {
        val safe = "https://user:hunter2@example.org/x".logSafe()

        assertTrue(safe, safe.startsWith("https://example.org/… #"))
        assertFalse(safe.contains("hunter2") || safe.contains("user"))
    }

    @Test
    fun keepsTheProviderOfAContentUriButNotTheDocument() {
        val safe = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fproxmox.iso".logSafe()

        assertTrue(safe, safe.startsWith("content://com.android.externalstorage.documents/… #"))
        assertFalse(safe.contains("proxmox") || safe.contains("Download"))
    }

    @Test
    fun hidesLocalFilePaths() {
        assertTrue("file:///data/user/0/net.metalstrapper.sparkwriter/files/x.iso".logSafe().startsWith("file:///… #"))
    }

    @Test
    fun handlesOpaqueSchemesAndValuesWithoutAScheme() {
        assertTrue("magnet:?xt=urn:btih:abcdef".logSafe().startsWith("magnet:… #"))
        assertTrue("not a uri".logSafe().startsWith("<uri> #"))
        assertEquals("<none>", (null as String?).logSafe())
        assertEquals("<none>", "  ".logSafe())
    }

    @Test
    fun hostOnlyUrlHasNoEllipsis() {
        assertTrue("https://example.org".logSafe().startsWith("https://example.org #"))
    }

    @Test
    fun sameUrlGetsTheSameTagAndDifferentUrlsDiffer() {
        val a = "https://h/a?t=1".logSafe()
        assertEquals(a, "https://h/a?t=1".logSafe())
        assertTrue(a != "https://h/a?t=2".logSafe())
    }
}
