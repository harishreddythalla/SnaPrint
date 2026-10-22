package com.snaptoprint

import android.content.Context
import java.security.SecureRandom

class Prefs(ctx: Context) {
    companion object {
        const val PORT = 8765
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }

    private val sp = ctx.applicationContext.getSharedPreferences("snap", Context.MODE_PRIVATE)

    /** 20 chars x 5 bits = 100 bits of randomness. */
    fun token(): String {
        var t = sp.getString("token", null)
        if (t == null) {
            t = newToken()
            sp.edit().putString("token", t).apply()
        }
        return t
    }

    fun reset() {
        sp.edit().putString("token", newToken()).apply()
    }

    fun display(): String = token().chunked(4).joinToString("-")

    private fun newToken(): String {
        val r = SecureRandom()
        return (1..20).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
    }
}

object Link {
    @Volatile var lastSeenMs: Long = 0L
    fun connected(): Boolean = System.currentTimeMillis() - lastSeenMs < 8000
}
