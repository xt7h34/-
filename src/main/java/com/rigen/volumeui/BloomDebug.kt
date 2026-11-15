package com.rigen.volumeui

import android.content.Context

/**
 * A short report of what Bloom saw the last time it placed the capsule: the windows on screen and the boxes inside the
 * notification's window, as sizes only. No text from any notification or any screen is ever put in it. It stays on the
 * phone, and you can copy it from Bloom's settings if you want to send it to someone to fix the placement.
 */
object BloomDebug {
    private fun sp(c: Context) = c.getSharedPreferences("kanade_bloom_debug", Context.MODE_PRIVATE)

    fun get(c: Context): String = sp(c).getString("report", "") ?: ""

    fun set(c: Context, text: String) = sp(c).edit().putString("report", text.take(7000)).apply()

    fun append(c: Context, line: String) = set(c, get(c) + "\n" + line)
}
