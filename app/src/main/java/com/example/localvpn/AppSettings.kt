package com.example.localvpn

import android.content.Context

object AppSettings {
    private const val PREFS = "tre_settings"
    private const val KEY_TUNNEL_DOMAIN = "tunnel_domain"
    private const val KEY_TUN_STACK = "tun_stack"
    private const val KEY_SMUX_MAX_STREAMS = "smux_max_streams"
    private const val KEY_VPN_ACTIVE = "vpn_active"
    private const val KEY_ACCOUNT_SUMMARY = "account_summary"
    private const val KEY_ACCOUNT_EXPIRE_EPOCH_DAY = "account_expire_epoch_day"

    private const val DEFAULT_TUNNEL_DOMAIN = "2.brawlpass.com.ar"
    private const val DEFAULT_TUN_STACK = "system"
    private const val DEFAULT_SMUX_MAX_STREAMS = 32

    fun getTunnelDomain(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TUNNEL_DOMAIN, DEFAULT_TUNNEL_DOMAIN)
            ?.trim()
            .orEmpty()
        return if (value.isBlank()) DEFAULT_TUNNEL_DOMAIN else value
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
            "system", "gvisor", "mixed" -> value.lowercase()
            else -> DEFAULT_TUN_STACK
        }
    }

    fun setTunStack(context: Context, stack: String) {
        val normalized = when (stack.trim().lowercase()) {
            "system", "gvisor", "mixed" -> stack.trim().lowercase()
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
        return value.coerceIn(1, 512)
    }

    fun setSmuxMaxStreams(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SMUX_MAX_STREAMS, value.coerceIn(1, 512))
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
}
