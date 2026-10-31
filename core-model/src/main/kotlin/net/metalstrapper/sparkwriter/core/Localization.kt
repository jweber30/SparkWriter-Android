package net.metalstrapper.sparkwriter.core

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Translations for one piece of sparkplug display text, written in a sparkplug as a map:
 *
 * ```yaml
 * label:
 *   en: Root SSH keys
 *   fr: Clés SSH root
 * label:
 *   ref: hostname      # a SparkWriter shared string, translated by the app
 * ```
 *
 * The plain-string field next to it (for example [ConfigField.label]) keeps a default
 * text, so code that does not care about the user's language can keep reading it.
 */
@Serializable
data class LocalizedText(
    /** Text by BCP 47 language tag, such as "fr" or "pt-BR". */
    val translations: Map<String, String> = emptyMap(),
    /** Id of a [SharedText] string. */
    val ref: String? = null,
)

/**
 * Strings SparkWriter translates itself, so sparkplugs can reuse them by id. Ids that
 * match a `standard_field` are also used automatically for that field's label.
 * The app holds the translations; this English text must match its default strings.
 */
object SharedText {
    val english: Map<String, String> = linkedMapOf(
        // Standard fields
        "user.name" to "Full name",
        "user.username" to "Username",
        "user.email" to "Email",
        "user.ssh_public_keys" to "SSH public keys",
        "network.suffix" to "Domain",
        "network.wifi.ssid" to "Wi-Fi network name",
        "apt.proxy" to "APT proxy",
        "locale.timezone" to "Time zone",
        "locale.keyboard" to "Keyboard layout",
        // Fields
        "hostname" to "Hostname",
        "fqdn" to "Fully qualified domain name",
        "country" to "Country",
        "language" to "Language",
        "network_card" to "Network card",
        "ip_address" to "IP address",
        "gateway" to "Gateway",
        "dns_servers" to "DNS servers",
        "vlan_id" to "VLAN ID",
        "filesystem" to "Filesystem",
        "install_disk" to "Install disk",
        "root_password" to "Root password",
        "admin_password" to "Admin password",
        // Wizard pages
        "identity" to "Identity",
        "network" to "Network",
        "storage" to "Storage",
        "access" to "Access",
        "packages" to "Packages",
        "first_boot" to "First boot",
        // Options
        "none" to "None",
        "custom" to "Custom",
        "reboot" to "Reboot",
        "power_off" to "Power off",
    )

    val ids: Set<String> get() = english.keys
}

/**
 * Picks the text to show for each piece of sparkplug text.
 *
 * @param preferredLanguages the user's languages, most preferred first (BCP 47 tags).
 * @param uiLanguage the language the app's own strings are showing in.
 * @param sharedText a [SharedText] string in [uiLanguage], or null if the app has none.
 */
class TextLocalizer(
    preferredLanguages: List<String>,
    uiLanguage: String,
    private val sharedText: (String) -> String?,
) {
    private val preferred = preferredLanguages.map(::normalizeTag).filter { it.isNotEmpty() }.distinct()
    private val ui = normalizeTag(uiLanguage)

    /**
     * For each preferred language in order, use the author's text in that language, or
     * the shared string when the app itself is showing that language. Otherwise fall
     * back to the author's text in the sparkplug's own language.
     *
     * @param default the plain-string text from the sparkplug.
     * @param text translations, or null when the sparkplug wrote a plain string.
     * @param sparkplugLanguage the language plain strings in the sparkplug are written in.
     * @param impliedRef a shared string to use when [text] names none, such as a field's standard_field.
     */
    fun resolve(
        default: String,
        text: LocalizedText?,
        sparkplugLanguage: String,
        impliedRef: String? = null,
    ): String {
        val authored = text?.translations ?: mapOf(sparkplugLanguage to default)
        val ref = (text?.ref ?: impliedRef)?.takeIf { it in SharedText.ids }
        for (language in preferred) {
            authored.inLanguage(language)?.let { return it }
            if (ref != null && primary(language) == primary(ui)) {
                sharedText(ref)?.let { return it }
            }
        }
        return authored.inLanguage(normalizeTag(sparkplugLanguage))
            ?: ref?.let { sharedText(it) ?: SharedText.english[it] }
            ?: default
    }

    private fun Map<String, String>.inLanguage(language: String): String? {
        val byTag = mapKeys { normalizeTag(it.key) }
        return byTag[language] ?: byTag[primary(language)]
    }
}

/** Same as [SparkManifest] but with display text in the user's language. */
fun SparkManifest.localized(localizer: TextLocalizer): SparkManifest {
    val language = metadata.language
    fun Map<String, LocalizedText>.text(key: String, default: String, impliedRef: String? = null): String =
        localizer.resolve(default, this[key], language, impliedRef)
    fun Map<String, LocalizedText>.optionalText(key: String, default: String?): String? =
        if (default == null && key !in this) null else text(key, default.orEmpty())

    return copy(
        configFields = configFields.map { field ->
            val l = field.localizedText
            field.copy(
                label = l.text("label", field.label, field.standardField),
                description = l.optionalText("description", field.description),
                placeholder = l.optionalText("placeholder", field.placeholder),
                options = field.options.map { it.copy(label = it.localizedText.text("label", it.label)) },
            )
        },
        wizardPages = wizardPages.map { page ->
            page.copy(
                title = page.localizedText.text("title", page.title),
                description = page.localizedText.optionalText("description", page.description),
            )
        },
        form = form.copy(
            buttons = form.buttons.map {
                it.copy(
                    label = it.localizedText.text("label", it.label),
                    description = it.localizedText.optionalText("description", it.description),
                )
            },
            hostProfiles = form.hostProfiles.map {
                it.copy(
                    label = it.localizedText.text("label", it.label),
                    description = it.localizedText.optionalText("description", it.description),
                )
            },
        ),
        actionPhases = actionPhases.mapValues { (_, actions) ->
            actions.map { it.copy(userSecretLabel = it.localizedText.optionalText("user_secret_label", it.userSecretLabel)) }
        },
    )
}

/** Matches the language tags sparkplugs may use as translation keys, such as "fr" or "zh-Hans-CN". */
val LANGUAGE_TAG_PATTERN = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

private fun normalizeTag(tag: String): String = tag.trim().replace('_', '-').lowercase(Locale.ROOT)

private fun primary(tag: String): String = tag.substringBefore('-')
