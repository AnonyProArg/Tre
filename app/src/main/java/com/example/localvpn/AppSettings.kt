package com.example.localvpn

import android.content.Context

object AppSettings {
    private const val PREFS = "tre_settings"
    private const val KEY_TUNNEL_DOMAIN = "tunnel_domain"
    private const val KEY_TUN_STACK = "tun_stack"
    private const val KEY_SMUX_MAX_STREAMS = "smux_max_streams"
    private const val KEY_H2MUX_MAX_STREAMS = "h2mux_max_streams"
    private const val KEY_CUSTOM_MUX_MAX_STREAMS = "custom_mux_max_streams"
    private const val KEY_MUX_PROTOCOL = "mux_protocol"
    private const val KEY_PERFORMANCE_PROFILE = "performance_profile"
    private const val KEY_GAMER_TARGET_PACKAGE = "gamer_target_package"
    private const val KEY_GAMER_TARGET_PACKAGES = "gamer_target_packages"
    private const val KEY_VPN_ACTIVE = "vpn_active"
    private const val KEY_ACCOUNT_SUMMARY = "account_summary"
    private const val KEY_ACCOUNT_EXPIRE_EPOCH_DAY = "account_expire_epoch_day"
    private const val KEY_SERVER_LIST = "server_list"
    private const val KEY_CUSTOM_TUNNEL_ENABLED = "custom_tunnel_enabled"
    private const val KEY_CUSTOM_TUNNEL_SERVER = "custom_tunnel_server"
    private const val KEY_CUSTOM_TUNNEL_PORT = "custom_tunnel_port"
    private const val KEY_CUSTOM_TUNNEL_UUID = "custom_tunnel_uuid"
    private const val KEY_CUSTOM_TUNNEL_SNI = "custom_tunnel_sni"
    private const val KEY_CUSTOM_TUNNEL_PAYLOAD_1 = "custom_tunnel_payload_1"
    private const val KEY_CUSTOM_TUNNEL_PAYLOAD_2 = "custom_tunnel_payload_2"
    private const val KEY_CUSTOM_TUNNEL_PROXY_PORT = "custom_tunnel_proxy_port"

    private const val DEFAULT_TUN_STACK = "gvisor"
    private const val DEFAULT_SMUX_MAX_STREAMS = 5000
    private const val DEFAULT_H2MUX_MAX_STREAMS = 1000
    private const val DEFAULT_CUSTOM_MUX_MAX_STREAMS = 5000
    private const val DEFAULT_MUX_PROTOCOL = "smux"
    private const val DEFAULT_PERFORMANCE_PROFILE = "normal"
    private const val DEFAULT_CUSTOM_TUNNEL_PORT = 443
    private const val DEFAULT_CUSTOM_TUNNEL_PROXY_PORT = 1080

    fun getTunnelDomain(context: Context): String {
        val explicit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TUNNEL_DOMAIN, "")
            ?.trim()
            .orEmpty()
        if (explicit.isNotBlank()) return explicit
        return getServerList(context).firstOrNull()?.host.orEmpty()
    }

    fun setTunnelDomain(context: Context, domain: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TUNNEL_DOMAIN, domain.trim())
            .apply()
    }

    fun getTunStack(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TUN_STACK, DEFAULT_TUN_STACK)
            ?.trim()
            .orEmpty()
        return when (value.lowercase()) {
            "system", "gvisor" -> value.lowercase()
            "mixed" -> "system"
            else -> DEFAULT_TUN_STACK
        }
    }

    fun setTunStack(context: Context, stack: String) {
        val normalized = when (stack.trim().lowercase()) {
            "system", "gvisor" -> stack.trim().lowercase()
            "mixed" -> "system"
            else -> DEFAULT_TUN_STACK
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TUN_STACK, normalized)
            .apply()
    }

    fun getSmuxMaxStreams(context: Context): Int {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SMUX_MAX_STREAMS, DEFAULT_SMUX_MAX_STREAMS)
        return value.coerceIn(700, 20000)
    }

    fun setSmuxMaxStreams(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SMUX_MAX_STREAMS, value.coerceIn(700, 20000))
            .apply()
    }

    fun getH2MuxMaxStreams(context: Context): Int {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_H2MUX_MAX_STREAMS, DEFAULT_H2MUX_MAX_STREAMS)
        return value.coerceIn(700, 20000)
    }

    fun setH2MuxMaxStreams(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_H2MUX_MAX_STREAMS, value.coerceIn(700, 20000))
            .apply()
    }

    fun getCustomMuxMaxStreams(context: Context): Int {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM_MUX_MAX_STREAMS, DEFAULT_CUSTOM_MUX_MAX_STREAMS)
        return value.coerceIn(1, 20000)
    }

    fun setCustomMuxMaxStreams(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CUSTOM_MUX_MAX_STREAMS, value.coerceIn(1, 20000))
            .apply()
    }

    fun getMuxProtocol(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MUX_PROTOCOL, DEFAULT_MUX_PROTOCOL)
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return when (value) {
            "smux", "h2mux" -> value
            else -> DEFAULT_MUX_PROTOCOL
        }
    }

    fun setMuxProtocol(context: Context, protocol: String) {
        val normalized = when (protocol.trim().lowercase()) {
            "smux", "h2mux" -> protocol.trim().lowercase()
            else -> DEFAULT_MUX_PROTOCOL
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MUX_PROTOCOL, normalized)
            .apply()
    }

    fun getPerformanceProfile(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PERFORMANCE_PROFILE, DEFAULT_PERFORMANCE_PROFILE)
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return when (value) {
            "battery", "low_end", "normal", "ultra", "gamer", "custom", "custom_tunnel" -> value
            else -> DEFAULT_PERFORMANCE_PROFILE
        }
    }

    fun setPerformanceProfile(context: Context, profile: String) {
        val normalized = when (profile.trim().lowercase()) {
            "battery", "low_end", "normal", "ultra", "gamer", "custom", "custom_tunnel" -> profile.trim().lowercase()
            else -> DEFAULT_PERFORMANCE_PROFILE
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERFORMANCE_PROFILE, normalized)
            .apply()
    }

    fun getMuxMaxStreams(context: Context, protocol: String): Int {
        return if (protocol.lowercase() == "h2mux") getH2MuxMaxStreams(context) else getSmuxMaxStreams(context)
    }

    fun setMuxMaxStreams(context: Context, protocol: String, value: Int) {
        if (protocol.lowercase() == "h2mux") setH2MuxMaxStreams(context, value) else setSmuxMaxStreams(context, value)
    }


    fun getGamerTargetPackage(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GAMER_TARGET_PACKAGE, "")
            ?.trim()
            .orEmpty()
    }

    fun getGamerTargetPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY_GAMER_TARGET_PACKAGES, null)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        if (raw.isNotEmpty()) return raw

        val legacySingle = getGamerTargetPackage(context)
        return if (legacySingle.isBlank()) emptySet() else setOf(legacySingle)
    }

    fun setGamerTargetPackage(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GAMER_TARGET_PACKAGE, packageName.trim())
            .apply()
    }

    fun setGamerTargetPackages(context: Context, packageNames: Set<String>) {
        val normalized = packageNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_GAMER_TARGET_PACKAGES, normalized)
            .putString(KEY_GAMER_TARGET_PACKAGE, normalized.firstOrNull().orEmpty())
            .apply()
    }


    fun getCustomTunnelConfig(context: Context): CustomTunnelConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CustomTunnelConfig(
            enabled = prefs.getBoolean(KEY_CUSTOM_TUNNEL_ENABLED, false),
            server = prefs.getString(KEY_CUSTOM_TUNNEL_SERVER, "")?.trim().orEmpty(),
            port = prefs.getInt(KEY_CUSTOM_TUNNEL_PORT, DEFAULT_CUSTOM_TUNNEL_PORT).coerceIn(1, 65535),
            uuid = prefs.getString(KEY_CUSTOM_TUNNEL_UUID, "")?.trim().orEmpty(),
            sni = prefs.getString(KEY_CUSTOM_TUNNEL_SNI, "")?.trim().orEmpty(),
            payload1 = prefs.getString(KEY_CUSTOM_TUNNEL_PAYLOAD_1, "")?.trim().orEmpty(),
            payload2 = prefs.getString(KEY_CUSTOM_TUNNEL_PAYLOAD_2, "")?.trim().orEmpty(),
            proxyPort = prefs.getInt(KEY_CUSTOM_TUNNEL_PROXY_PORT, DEFAULT_CUSTOM_TUNNEL_PROXY_PORT).coerceIn(1, 65535)
        )
    }

    fun setCustomTunnelConfig(context: Context, config: CustomTunnelConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CUSTOM_TUNNEL_ENABLED, config.enabled)
            .putString(KEY_CUSTOM_TUNNEL_SERVER, config.server.trim())
            .putInt(KEY_CUSTOM_TUNNEL_PORT, config.port.coerceIn(1, 65535))
            .putString(KEY_CUSTOM_TUNNEL_UUID, config.uuid.trim())
            .putString(KEY_CUSTOM_TUNNEL_SNI, config.sni.trim())
            .putString(KEY_CUSTOM_TUNNEL_PAYLOAD_1, config.payload1)
            .putString(KEY_CUSTOM_TUNNEL_PAYLOAD_2, config.payload2)
            .putInt(KEY_CUSTOM_TUNNEL_PROXY_PORT, config.proxyPort.coerceIn(1, 65535))
            .apply()
    }

    fun isVpnActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VPN_ACTIVE, false)
    }

    fun setVpnActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VPN_ACTIVE, active)
            .apply()
    }

    fun getAccountSummary(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCOUNT_SUMMARY, "")
            .orEmpty()
    }

    fun setAccountSummary(context: Context, summary: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT_SUMMARY, summary)
            .apply()
    }

    fun getAccountExpireEpochDay(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ACCOUNT_EXPIRE_EPOCH_DAY, -1L)
    }

    fun setAccountExpireEpochDay(context: Context, epochDay: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACCOUNT_EXPIRE_EPOCH_DAY, epochDay)
            .apply()
    }


    data class CustomTunnelConfig(
        val enabled: Boolean,
        val server: String,
        val port: Int,
        val uuid: String,
        val sni: String,
        val payload1: String,
        val payload2: String,
        val proxyPort: Int
    )

    data class SavedServer(val host: String, val region: String, val status: String)

    fun getServerList(context: Context): List<SavedServer> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_LIST, "")
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) null else SavedServer(parts[0], parts[1], parts[2])
        }
    }

    fun setServerList(context: Context, servers: List<SavedServer>) {
        val normalized = linkedMapOf<String, SavedServer>()
        servers.forEach { s ->
            val host = s.host.trim().lowercase()
            if (host.isNotBlank()) normalized[host] = SavedServer(host, s.region.trim(), s.status.trim().lowercase())
        }
        val raw = normalized.values.joinToString("\n") { "${it.host}|${it.region}|${it.status}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_LIST, raw)
            .apply()
    }
}
