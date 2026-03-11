package com.example.localvpn

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnLogStore {

    private const val MAX_LOG_LINES = 500
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = mutableListOf<String>()
    private val listeners = mutableSetOf<(String) -> Unit>()

    @Synchronized
    fun add(message: String) {
        val entry = "${formatter.format(Date())}  $message"
        lines.add(entry)
        if (lines.size > MAX_LOG_LINES) {
            lines.removeAt(0)
        }
        listeners.forEach { it(entry) }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        listeners.forEach { it("--- logs limpiados ---") }
    }

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
