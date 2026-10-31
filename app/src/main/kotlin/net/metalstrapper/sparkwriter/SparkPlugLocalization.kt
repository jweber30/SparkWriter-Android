package net.metalstrapper.sparkwriter

import android.content.Context
import net.metalstrapper.sparkwriter.core.TextLocalizer

/** Android strings for each [net.metalstrapper.sparkwriter.core.SharedText] id. */
internal val SharedTextResources: Map<String, Int> = mapOf(
    "user.name" to R.string.shared_text_user_name,
    "user.username" to R.string.shared_text_user_username,
    "user.email" to R.string.shared_text_user_email,
    "user.ssh_public_keys" to R.string.shared_text_user_ssh_public_keys,
    "network.suffix" to R.string.shared_text_network_suffix,
    "network.wifi.ssid" to R.string.shared_text_network_wifi_ssid,
    "apt.proxy" to R.string.shared_text_apt_proxy,
    "locale.timezone" to R.string.shared_text_locale_timezone,
    "locale.keyboard" to R.string.shared_text_locale_keyboard,
    "hostname" to R.string.shared_text_hostname,
    "fqdn" to R.string.shared_text_fqdn,
    "country" to R.string.shared_text_country,
    "language" to R.string.shared_text_language,
    "network_card" to R.string.shared_text_network_card,
    "ip_address" to R.string.shared_text_ip_address,
    "gateway" to R.string.shared_text_gateway,
    "dns_servers" to R.string.shared_text_dns_servers,
    "vlan_id" to R.string.shared_text_vlan_id,
    "filesystem" to R.string.shared_text_filesystem,
    "install_disk" to R.string.shared_text_install_disk,
    "root_password" to R.string.shared_text_root_password,
    "admin_password" to R.string.shared_text_admin_password,
    "identity" to R.string.shared_text_identity,
    "network" to R.string.shared_text_network,
    "storage" to R.string.shared_text_storage,
    "access" to R.string.shared_text_access,
    "packages" to R.string.shared_text_packages,
    "first_boot" to R.string.shared_text_first_boot,
    "none" to R.string.shared_text_none,
    "custom" to R.string.shared_text_custom,
    "reboot" to R.string.shared_text_reboot,
    "power_off" to R.string.shared_text_power_off,
)

/** Picks sparkplug text for the languages this context is showing. */
internal fun Context.sparkPlugLocalizer(): TextLocalizer {
    val locales = resources.configuration.locales
    return TextLocalizer(
        preferredLanguages = (0 until locales.size()).map { locales[it].toLanguageTag() },
        uiLanguage = getString(R.string.ui_language_tag),
        sharedText = { id -> SharedTextResources[id]?.let(::getString) },
    )
}
