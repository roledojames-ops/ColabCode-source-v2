package com.colabcode

import java.net.HttpURLConnection
import java.net.URL

object Http {
    fun req(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, timeout: Int = 620_000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = timeout
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to s
    }

    fun bytes(url: String, headers: Map<String, String>): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 300_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }
}

/** Keep tool output small: head + tail. */
fun clip(s: String, max: Int = 6000): String =
    if (s.length <= max) s else s.take(max * 2 / 3) + "\n…[${s.length - max} chars cut]…\n" + s.takeLast(max / 3)
