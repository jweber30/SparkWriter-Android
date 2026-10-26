package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.SparkManifest

sealed interface ImageAcquisitionPlan {
    data class Direct(
        val url: String,
    ) : ImageAcquisitionPlan

    data class Torrent(
        val torrentUrl: String,
        val artifact: String?,
        val manifestId: String,
        val sourceId: String?,
        val displayName: String,
    ) : ImageAcquisitionPlan
}

fun SparkManifest.imageAcquisitionPlan(): ImageAcquisitionPlan {
    val source = source ?: error("Selected manifest does not declare a source image")
    val acquire = source.acquire
    val acquireKind = acquire?.kind?.lowercase() ?: "direct"
    val acquireUrl = acquire?.url?.ifBlank { null } ?: source.url?.ifBlank { null }
        ?: error("Selected manifest does not declare a source image URL")

    return when (acquireKind) {
        "direct" -> ImageAcquisitionPlan.Direct(acquireUrl)
        "torrent" -> {
            ImageAcquisitionPlan.Torrent(
                torrentUrl = acquireUrl,
                artifact = acquire?.artifact?.ifBlank { null },
                manifestId = metadata.id,
                sourceId = source.id.ifBlank { null },
                displayName = source.name.ifBlank { metadata.name },
            )
        }
        else -> error("Unsupported Android acquisition kind: $acquireKind")
    }
}

val ImageAcquisitionPlan.downloadUrl: String
    get() = when (this) {
        is ImageAcquisitionPlan.Direct -> url
        is ImageAcquisitionPlan.Torrent -> torrentUrl
    }
