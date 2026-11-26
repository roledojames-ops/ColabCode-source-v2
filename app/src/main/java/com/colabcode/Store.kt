package com.colabcode

import android.content.Context

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("cc", Context.MODE_PRIVATE)
    fun get(k: String, d: String = DEF[k] ?: ""): String = p.getString(k, null) ?: d
    fun put(k: String, v: String) { p.edit().putString(k, v).apply() }

    companion object {
        val DEF = mapOf(
            "base" to "https://api.openai.com/v1", "model" to "gpt-4o-mini", "ws" to "colab",
            "scope" to "https://www.googleapis.com/auth/colaboratory", "exp" to "0",
            "tmode" to "text", "ctx" to "7000", "maxtok" to "1024", "temp" to "0.2", "vision" to "0", "searx" to ""
        )
    }
}
