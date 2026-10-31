package net.metalstrapper.sparkwriter

import java.security.MessageDigest

/**
 * A URL or URI reduced to what is safe to log: scheme and authority, plus a short tag derived
 * from the whole value so log lines about the same URL can still be correlated. Paths, queries
 * and fragments can carry tokens (Mailroom write URLs are capabilities) or local file names, so
 * they never reach Logcat, receipts or error messages.
 *
 *   https://mailroom.example/netboot/v1/rendezvous/abc?token=x  ->  https://mailroom.example/… #1a2b3c
 *   content://com.android.providers.downloads.documents/document/42  ->  content://com.android.providers.downloads.documents/… #9f8e7d
 */
internal fun String?.logSafe(): String {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return "<none>"
    val tag = value.toByteArray(Charsets.UTF_8).logTag()
    val scheme = SCHEME_REGEX.find(value)?.groupValues?.get(1)?.lowercase()
        ?: return "<uri> #$tag"
    val rest = value.substring(scheme.length + 1)
    if (!rest.startsWith("//")) return "$scheme:… #$tag"
    val hierarchical = rest.removePrefix("//")
    val rawAuthority = hierarchical.takeWhile { it != '/' && it != '?' && it != '#' }
    // Drop userinfo: "user:password@host" must not survive either.
    val authority = rawAuthority.substringAfterLast('@')
    val hasMore = hierarchical.length > rawAuthority.length
    return "$scheme://$authority${if (hasMore) "/…" else ""} #$tag"
}

internal fun android.net.Uri?.logSafe(): String = this?.toString().logSafe()

private fun ByteArray.logTag(): String =
    MessageDigest.getInstance("SHA-256").digest(this).take(3).joinToString("") { "%02x".format(it) }

private val SCHEME_REGEX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
