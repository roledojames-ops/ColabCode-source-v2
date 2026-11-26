package com.colabcode

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Colab MCP client: https://colaboratory.googleapis.com/mcp (JSON-RPC over HTTP). */
class Colab(private val st: Store) {
    private val mcp = "https://colaboratory.googleapis.com/mcp"
    private val tokenUrl = "https://oauth2.googleapis.com/token"
    var runtime: String = st.get("runtime")
    var session: String = st.get("session")
    private var rid = 0
    private var props: Map<String, JSONObject>? = null

    private fun form(vararg p: Pair<String, String>) = p.filter { it.second.isNotEmpty() }
        .joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }
    private val formH = mapOf("Content-Type" to "application/x-www-form-urlencoded")

    // ---------- OAuth (installed-app loopback + PKCE) ----------
    private fun save(j: JSONObject) {
        st.put("atoken", j.getString("access_token"))
        if (j.has("refresh_token")) st.put("rtoken", j.getString("refresh_token"))
        st.put("exp", (System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000).toString())
    }

    fun token(): String {
        val at = st.get("atoken"); val rt = st.get("rtoken")
        if (at.isNotEmpty() && (rt.isEmpty() || System.currentTimeMillis() < st.get("exp").toLong() - 60_000)) return at
        if (rt.isEmpty()) error("Not signed in. Use /login or paste an access token in Settings.")
        val (c, b) = Http.req("POST", tokenUrl, formH, form("client_id" to st.get("cid"), "client_secret" to st.get("csec"), "refresh_token" to rt, "grant_type" to "refresh_token"))
        if (c !in 200..299) error("Token refresh failed: ${clip(b, 300)}")
        save(JSONObject(b)); return st.get("atoken")
    }

    fun signIn(open: (String) -> Unit) {
        val cid = st.get("cid")
        if (cid.isEmpty()) error("Set the Google OAuth client ID in Settings first.")
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); ss.soTimeout = 180_000
        val redirect = "http://127.0.0.1:${ss.localPort}"
        val fl = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val verifier = Base64.encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) }, fl)
        val chal = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), fl)
        open("https://accounts.google.com/o/oauth2/v2/auth?" + form(
            "client_id" to cid, "redirect_uri" to redirect, "response_type" to "code", "scope" to st.get("scope"),
            "code_challenge" to chal, "code_challenge_method" to "S256", "access_type" to "offline", "prompt" to "consent"))
        ss.use { s ->
            val sock = s.accept()
            val line = sock.getInputStream().bufferedReader().readLine() ?: ""
            val code = Regex("[?&]code=([^& ]+)").find(line)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<h3>Signed in. Return to ColabCode.</h3>".toByteArray())
            sock.close()
            require(code != null) { "No auth code received: $line" }
            val (c, b) = Http.req("POST", tokenUrl, formH, form("client_id" to cid, "client_secret" to st.get("csec"), "code" to code,
                "redirect_uri" to redirect, "code_verifier" to verifier, "grant_type" to "authorization_code"))
            if (c !in 200..299) error("Sign-in failed: ${clip(b, 300)}")
            save(JSONObject(b))
        }
    }

    // ---------- MCP ----------
    private fun parse(t: String): JSONObject? {
        val s = t.trim()
        if (s.startsWith("{")) return runCatching { JSONObject(s) }.getOrNull()
        return s.lines().filter { it.startsWith("data:") }
            .mapNotNull { runCatching { JSONObject(it.removePrefix("data:").trim()) }.getOrNull() }.lastOrNull()
    }

    private fun rpc(method: String, params: JSONObject): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", ++rid).put("method", method).put("params", params).toString()
        val (c, t) = Http.req("POST", mcp, mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream"), body)
        val j = parse(t) ?: error("HTTP $c: ${clip(t, 500)}")
        j.optJSONObject("error")?.let { error(it.optString("message", it.toString())) }
        return j.optJSONObject("result") ?: error("HTTP $c: ${clip(t, 500)}")
    }

    fun tools(): JSONArray = rpc("tools/list", JSONObject()).optJSONArray("tools") ?: JSONArray()

    /** Pick the real argument name from the server's schema (first candidate present). */
    private fun pk(tool: String, vararg c: String): String {
        if (props == null) props = runCatching {
            val a = tools()
            (0 until a.length()).associate {
                a.getJSONObject(it).getString("name") to (a.getJSONObject(it).optJSONObject("inputSchema")?.optJSONObject("properties") ?: JSONObject())
            }
        }.getOrNull()
        val p = props?.get(tool)
        return c.firstOrNull { p?.has(it) == true } ?: c[0]
    }

    fun call(tool: String, args: JSONObject): JSONObject {
        val r = rpc("tools/call", JSONObject().put("name", tool).put("arguments", args))
        val a = r.optJSONArray("content")
        val text = if (a == null) "" else (0 until a.length()).joinToString("\n") { a.getJSONObject(it).optString("text") }
        if (r.optBoolean("isError")) error(clip(text, 800))
        return r.optJSONObject("structuredContent") ?: runCatching { JSONObject(text) }.getOrNull() ?: JSONObject().put("text", text)
    }

    private fun firstArr(o: JSONObject): JSONArray =
        o.keys().asSequence().map { o.opt(it) }.filterIsInstance<JSONArray>().firstOrNull() ?: JSONArray()

    // ---------- runtime lifecycle ----------
    fun specs(): JSONArray = firstArr(call("list_runtime_specs", JSONObject()))
    fun runtimes(): JSONArray = firstArr(call("list_runtimes", JSONObject()))

    fun use(name: String) { runtime = name; session = ""; st.put("runtime", name); st.put("session", "") }

    fun create(spec: JSONObject): String {
        val r = call("create_runtime", JSONObject().put("runtime", JSONObject().put("runtimeSpec", spec))
            .put("requestId", UUID.randomUUID().toString()))
        val n = r.optString("name").ifEmpty { r.optJSONObject("runtime")?.optString("name") ?: "" }
        if (n.isEmpty()) error("No runtime name in: ${clip(r.toString(), 400)}")
        use(n); return n
    }

    private fun need() { if (runtime.isEmpty()) error("No runtime. /specs then /new N, or /runtimes then /use N.") }
    fun info(): JSONObject { need(); return call("get_runtime", JSONObject().put(pk("get_runtime", "name", "runtime"), runtime)) }
    fun stop() { need(); call("delete_runtime", JSONObject().put(pk("delete_runtime", "name", "runtime"), runtime)); use("") }
    fun usage(): JSONObject = call("get_subscription", JSONObject())

    fun exec(code: String, retry: Boolean = true): JSONObject {
        need()
        val a = JSONObject().put(pk("execute_code", "code", "source"), code)
        if (session.isNotEmpty()) a.put("session", session).put("executionId", UUID.randomUUID().toString())
        else a.put("runtime", runtime)
        return try {
            call("execute_code", a).also { r ->
                r.optString("session").takeIf { it.isNotEmpty() }?.let { session = it; st.put("session", it) }
            }
        } catch (e: Exception) {
            if (retry && session.isNotEmpty()) { session = ""; st.put("session", ""); exec(code, false) } else throw e
        }
    }

    // ---------- file transfer via runtime proxy (as documented under get_runtime) ----------
    private fun conn(): Pair<String, String> {
        val r = info()
        val c = r.optJSONObject("connectionInfo") ?: r.optJSONObject("runtime")?.optJSONObject("connectionInfo") ?: error("No connectionInfo")
        return c.getString("url").trimEnd('/') to c.getString("token")
    }

    fun download(path: String): ByteArray {
        val (u, t) = conn()
        return Http.bytes("$u/files/${path.trimStart('/')}", mapOf("X-Colab-Runtime-Proxy-Token" to t))
    }

    fun upload(path: String, data: ByteArray) {
        if (data.size > 48_000_000) error("Max 48 MB per upload")
        val (u, t) = conn()
        val h = mapOf("X-Colab-Runtime-Proxy-Token" to t, "Content-Type" to "application/json")
        val segs = path.trim('/').split("/")
        var acc = ""
        for (s in segs.dropLast(1)) {
            acc += (if (acc.isEmpty()) "" else "/") + s
            runCatching { Http.req("PUT", "$u/api/contents/$acc", h, "{\"type\":\"directory\"}") }
        }
        val body = JSONObject().put("type", "file").put("format", "base64").put("content", Base64.encodeToString(data, Base64.NO_WRAP)).toString()
        val (c, b) = Http.req("PUT", "$u/api/contents/${segs.joinToString("/")}", h, body)
        if (c !in 200..299) error("Upload HTTP $c ${clip(b, 200)}")
    }
}
