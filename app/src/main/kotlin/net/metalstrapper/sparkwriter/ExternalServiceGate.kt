package net.metalstrapper.sparkwriter

import androidx.annotation.VisibleForTesting
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.withExternalServicePolicy

/**
 * Last line of defense for the external-service release policy. Manifests that use
 * these features are already marked unavailable at load; this stops the request
 * itself if one slips through (for example, a manifest stored by an older build).
 */
internal object ExternalServiceGate {
    @Volatile
    @VisibleForTesting
    var enabled: Boolean = BuildConfig.EXTERNAL_SERVICES_ENABLED

    fun require(feature: String) {
        if (!enabled) throw ExternalServicesDisabledException(feature)
    }
}

internal class ExternalServicesDisabledException(feature: String) :
    IllegalStateException("$feature is disabled in this release")

internal fun SparkManifest.withReleaseExternalServicePolicy(): SparkManifest =
    withExternalServicePolicy(ExternalServiceGate.enabled)
