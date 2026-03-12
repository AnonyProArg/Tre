package com.example.localvpn

import android.content.Context

object AppSettings {
    private const val PREFS = "tre_settings"
    private const val KEY_TUNNEL_DOMAIN = "tunnel_domain"
    private const val DEFAULT_TUNNEL_DOMAIN = "1.brawlpass.com.ar"

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
}
