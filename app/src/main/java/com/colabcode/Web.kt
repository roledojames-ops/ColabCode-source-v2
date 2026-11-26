package com.colabcode

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun htmlToText(h: String): String {
        var s = h.replace(Regex("(?is)<(script|style|noscript|svg|head)\\b.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6]|/pre|/table)[^>]*>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "\n• ")
            .replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        return s.lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** GET/POST a URL. HTML becomes readable text; JSON/plain stays raw. */
    fun fetch(url: String, method: String = "GET", headers: String = "", body: String = "", max: Int = 5000): String {
        require(url.startsWith("http://") || url.startsWith("https://")) { "url must start with http:// or https://" }
        val h = mutableMapOf("User-Agent" to UA, "Accept" to "text/html,application/json,text/plain,*/*")
        headers.lines().forEach { l -> val i = l.indexOf(':'); if (i > 0) h[l.substring(0, i).trim()] = l.substring(i + 1).trim() }
        val m = method.uppercase().ifEmpty { "GET" }
        val (c, b) = Http.req(m, url, h, body.ifEmpty { null }, 30_000)
        val text = if (b.trimStart().startsWith("<")) htmlToText(b) else b
        val cut = if (text.length > max) text.take(max) + "\n…[${text.length - max} more chars; call fetch again with a bigger max, or use bash+curl to save it to a file]" else text
        return "HTTP $c\n$cut"
    }

    /** SearXNG JSON if `searx` is set, else DuckDuckGo HTML. */
    fun search(q: String, searx: String = "", n: Int = 8): String {
        require(q.isNotBlank()) { "query is empty" }
        if (searx.isNotBlank()) {
            val (c, b) = Http.req("GET", searx.trimEnd('/') + "/search?format=json&q=" + URLEncoder.encode(q, "UTF-8"), mapOf("User-Agent" to UA), null, 30_000)
            if (c !in 200..299) return "ERR search HTTP $c"
            val r = JSONObject(b).optJSONArray("results") ?: return "(no results)"
            return (0 until minOf(n, r.length())).joinToString("\n\n") {
                val o = r.getJSONObject(it)
                "${it + 1}. ${o.optString("title")}\n${o.optString("url")}\n${o.optString("content").take(220)}"
            }.ifEmpty { "(no results)" }
        }
        val (c, b) = Http.req("POST", "https://html.duckduckgo.com/html/",
            mapOf("User-Agent" to UA, "Content-Type" to "application/x-www-form-urlencoded"), "q=" + URLEncoder.encode(q, "UTF-8"), 30_000)
        if (c !in 200..299) return "ERR search HTTP $c (set a SearXNG URL in Settings if DuckDuckGo blocks this device)"
        val links = Regex("(?s)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(b).toList()
        val snips = Regex("(?s)class=\"result__snippet\"[^>]*>(.*?)</a>").findAll(b).map { htmlToText(it.groupValues[1]) }.toList()
        if (links.isEmpty()) return "(no results — DuckDuckGo may have blocked the request; set a SearXNG URL in Settings)"
        return links.take(n).mapIndexed { i, m ->
            var u = m.groupValues[1].replace("&amp;", "&")
            Regex("[?&]uddg=([^&]+)").find(u)?.let { u = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (u.startsWith("//")) u = "https:$u"
            "${i + 1}. ${htmlToText(m.groupValues[2])}\n$u\n${snips.getOrElse(i) { "" }.take(220)}"
        }.joinToString("\n\n")
    }
}
