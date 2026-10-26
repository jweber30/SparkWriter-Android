package net.metalstrapper.sparkwriter.core

/**
 * Manifest features that make the phone, or the machine being installed, talk to a
 * service outside the user's control. The initial release ships with all of them off.
 */
object ExternalServices {
    const val DISABLED_PREFIX = "Disabled in this release: "

    /** Hosts whose appearance in a template or boot patch means the target will call Mailroom. */
    val MAILROOM_MARKERS = listOf(
        "mailroom.metalstrapper.net",
        "mr.metalstrapper.net",
        "//wri7e.net",
        "/netboot/v1/rendezvous/",
    )
}

/** One reason per external-service feature the manifest uses; empty when it uses none. */
fun SparkManifest.externalServiceReasons(): List<String> = buildList {
    if (hostEvents.any { it.returnDelivery != null }) {
        add("${ExternalServices.DISABLED_PREFIX}host_events return_delivery callbacks")
    }
    if (mailroom != null || referencesMailroom()) {
        add("${ExternalServices.DISABLED_PREFIX}Mailroom artifact handoff")
    }
    val fetchFields = configFields.filter { it.fetch != null }.map { it.id }
    if (fetchFields.isNotEmpty()) {
        add("${ExternalServices.DISABLED_PREFIX}config field fetch (${fetchFields.joinToString()})")
    }
    if (form.buttons.isNotEmpty()) {
        add("${ExternalServices.DISABLED_PREFIX}remote prefill buttons (${form.buttons.joinToString { it.id }})")
    }
}

/** Adds the external-service reasons to [SparkManifest.unsupportedReasons] when services are off. */
fun SparkManifest.withExternalServicePolicy(enabled: Boolean): SparkManifest {
    val kept = unsupportedReasons.filterNot { it.startsWith(ExternalServices.DISABLED_PREFIX) }
    val reasons = if (enabled) kept else (kept + externalServiceReasons()).distinct()
    return if (reasons == unsupportedReasons) this else copy(unsupportedReasons = reasons)
}

private fun SparkManifest.referencesMailroom(): Boolean {
    val texts = templates.values.asSequence() +
        bootPatch?.scriptPatches.orEmpty().asSequence().map { it.script } +
        bootPatch?.placeholderPatches.orEmpty().asSequence().map { it.replacement }
    return texts.any { text -> ExternalServices.MAILROOM_MARKERS.any { it in text } }
}
