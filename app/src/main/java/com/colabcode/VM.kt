package com.colabcode

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Line(val kind: String, val text: String)

const val HELP = """/login  Google sign-in (set OAuth client in ⚙)
/specs  list runtime types → /new N
/runtimes → /use N   reuse a runtime
/status /stop /usage
/ws colab|local|termux  switch workspace
/termux  test Termux link · /termux setup  (setup steps)
/mode text|native  tool protocol (text = best for small models)
/search Q · /fetch URL  (manual web tools)
/init  let the agent write AGENTS.md for this project
/exec PY · /sh CMD · /install PKG…
/ls /cat /rm PATH
/upload REMOTE · /download REMOTE
📎 attach files (any type) · or Share → ColabCode from other apps
/tools · /mcp TOOL {json} · /export /clear
Anything else goes to the agent (■ = stop)."""

class VM(private val app: Application) : AndroidViewModel(app) {
    val st = Store(app)
    private val colab = Colab(st)
    private val local = Local(File(app.filesDir, "work").apply { mkdirs() })
    private val remote = Remote(colab)
    var requestTermux: () -> Unit = {}
    private val termux = Termux(app) { ui.post { requestTermux() } }
    private fun ws(): Workspace = when (st.get("ws")) { "local" -> local; "termux" -> termux; else -> remote }
    private val agent = Agent(st, { ws() }, { termux })
    val attached = mutableStateListOf<File>()
    private val attDir = File(app.cacheDir, "attach").apply { mkdirs() }

    val log = mutableStateListOf<Line>()
    var busy by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var wsMode by mutableStateOf(st.get("ws"))
    var rt by mutableStateOf(colab.runtime)
    var pick by mutableStateOf<Pair<String, String>?>(null)
    var openUrl: (String) -> Unit = {}
    @Volatile private var halt = false
    private val ui = Handler(Looper.getMainLooper())
    private var specs = JSONArray()
    private var rts = JSONArray()

    private fun add(k: String, t: String) { ui.post { log.add(Line(k, t)) } }
    fun stop() { halt = true }
    fun setWs(m: String) { st.put("ws", m); wsMode = m }
    fun saveSettings(m: Map<String, String>) {
        if (m["atoken"] != st.get("atoken")) { st.put("rtoken", ""); st.put("exp", "0") }
        m.forEach { (k, v) -> st.put(k, v.trim()) }
    }

    fun send(t: String) {
        if (busy || (t.isBlank() && attached.isEmpty())) return
        val files = attached.toList(); attached.clear()
        add("u", (if (files.isNotEmpty()) "📎 " + files.joinToString { it.name } + "\n" else "") + t); busy = true; halt = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (t.startsWith("/") && files.isEmpty()) cmd(t.trim())
                else {
                    val imgs = ArrayList<String>()
                    val msg = if (files.isEmpty()) t else describe(files, imgs) + "\n\n" + t.ifBlank { "Look at the attached file(s) and tell me what they contain." }
                    agent.run(msg, imgs, { k, s -> add(k, s) }) { halt }
                }
            } catch (e: Exception) {
                add("s", "⚠ " + (e.message ?: e.toString()))
            } finally {
                busy = false; rt = colab.runtime; wsMode = st.get("ws")
            }
        }
    }

    // ───────── attachments ─────────
    private fun displayName(u: Uri): String {
        var n: String? = null
        runCatching { app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) n = it.getString(0) } }
        return (n ?: u.lastPathSegment ?: "file").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    fun attach(us: List<Uri>) = io {
        for (u in us) {
            var f = File(attDir, displayName(u))
            if (f.exists()) f = File(attDir, (System.currentTimeMillis() % 100000).toString() + "_" + f.name)
            app.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() > 48_000_000) { f.delete(); add("s", "⚠ ${f.name}: over 48 MB, skipped"); continue }
            ui.post { attached.add(f) }
        }
    }

    fun detach(f: File) { attached.remove(f); f.delete() }

    private fun mimeOf(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> ""
    }

    private fun hint(path: String, ext: String) = when (ext) {
        "pdf" -> "read text: bash `pdftotext -layout $path - | head -150` (needs poppler; Termux: pkg install poppler)"
        "docx", "odt" -> "read text: bash `unzip -p $path word/document.xml content.xml 2>/dev/null | sed 's/<[^>]*>/ /g' | head -c 4000`"
        "xlsx" -> "read text: bash `unzip -p $path xl/sharedStrings.xml | sed 's/<[^>]*>/ /g' | head -c 4000` or python openpyxl"
        "pptx" -> "read text: bash `unzip -p $path 'ppt/slides/slide*.xml' | sed 's/<[^>]*>/ /g' | head -c 4000`"
        "zip", "jar", "apk" -> "list: bash `unzip -l $path`; extract: bash `unzip -o $path -d extracted`"
        "tar", "gz", "tgz", "xz", "bz2" -> "list: bash `tar -tf $path | head -50`"
        "csv", "tsv", "json", "jsonl", "log", "md", "txt" -> "read with the read tool, or bash `head -c 3000 $path`"
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "image: bash `file $path` (a text-only model cannot see pixels; use OCR e.g. tesseract if installed)"
        "mp3", "wav", "ogg", "m4a", "mp4", "mkv", "webm" -> "media: bash `ffprobe $path` if ffmpeg is installed"
        else -> "inspect: bash `file $path && head -c 500 $path | xxd | head`"
    }

    private fun describe(files: List<File>, imgs: MutableList<String>): String {
        val sb = StringBuilder("[Attached files saved in the workspace:\n")
        for (f in files) {
            val bytes = f.readBytes()
            val saved = ws().putBytes("uploads/" + f.name, bytes)
            val ext = f.name.substringAfterLast('.', "").lowercase()
            add("s", "✓ saved $saved (${bytes.size} B)")
            sb.append("- $saved (${bytes.size} bytes). ${hint(saved, ext)}\n")
            val isText = bytes.take(1000).none { it.toInt() == 0 } && bytes.isNotEmpty()
            if (isText && bytes.size <= 2500 && mimeOf(f.name).isEmpty()) sb.append("  content:\n```\n${String(bytes)}\n```\n")
            val mime = mimeOf(f.name)
            if (mime.isNotEmpty() && st.get("vision") == "1" && bytes.size < 1_500_000)
                imgs.add("data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            f.delete()
        }
        return sb.append("]").toString()
    }

    private fun list(a: JSONArray) = if (a.length() == 0) "(none)" else
        (0 until a.length()).joinToString("\n") { "${it + 1}. " + a.get(it).toString().take(150) }

    private fun fmt(r: JSONObject): String {
        val s = listOf("stdout", "stderr", "result").map { r.optString(it) }.filter { it.isNotBlank() }.joinToString("\n")
        val e = r.optString("executionError")
        return clip(s + if (e.isBlank()) "" else "\n⚠ $e")
    }

    private fun cmd(line: String) {
        val p = line.split(Regex("\\s+"), 2); val a = p.getOrElse(1) { "" }.trim()
        when (p[0]) {
            "/help" -> add("s", HELP)
            "/login" -> { colab.signIn { openUrl(it) }; add("s", "✓ signed in") }
            "/specs" -> { specs = colab.specs(); add("s", list(specs) + "\nPick: /new N") }
            "/new" -> {
                if (specs.length() == 0) specs = colab.specs()
                val n = a.toIntOrNull()
                if (n == null || n < 1 || n > specs.length()) { add("s", list(specs) + "\nPick: /new N"); return }
                val e = specs.getJSONObject(n - 1)
                add("s", "Creating runtime… (may take a minute)")
                add("s", "✓ " + colab.create(e.optJSONObject("runtimeSpec") ?: e))
            }
            "/runtimes" -> { rts = colab.runtimes(); add("s", list(rts)) }
            "/use" -> {
                if (rts.length() == 0) rts = colab.runtimes()
                val n = a.toIntOrNull() ?: error("usage: /use N")
                colab.use(rts.getJSONObject(n - 1).getString("name")); add("s", "✓ " + colab.runtime)
            }
            "/status" -> {
                val j = JSONObject(colab.info().toString()); j.optJSONObject("connectionInfo")?.remove("token")
                add("s", clip(j.toString(2), 1500))
            }
            "/stop" -> { colab.stop(); add("s", "✓ runtime deleted") }
            "/usage" -> add("s", colab.usage().toString(2))
            "/exec" -> add("o", fmt(colab.exec(a)))
            "/sh" -> add("o", ws().bash(a, 300))
            "/install" -> add("o", ws().bash("(uv pip install --system -q $a || pip install -q $a) 2>&1 | tail -5", 600))
            "/ls" -> add("o", ws().ls(a.ifEmpty { "." }))
            "/cat" -> add("o", clip(ws().read(a, 0, 400)))
            "/rm" -> add("s", ws().bash("rm -rf '" + a.replace("'", "") + "' && echo removed", 30))
            "/upload" -> { require(a.isNotEmpty()) { "usage: /upload /content/file" }; pick = "up" to a }
            "/download" -> { require(a.isNotEmpty()) { "usage: /download /content/file" }; pick = "down" to a }
            "/ws" -> {
                st.put("ws", if (a == "local" || a == "termux") a else "colab"); add("s", "workspace: " + st.get("ws"))
                if (a == "termux") { ui.post { requestTermux() }; add("s", "Test the link with /termux") }
            }
            "/termux" -> {
                if (a == "setup") add("s", Termux.SETUP + "\nUseful packages (run in Termux): pkg install -y python curl poppler unzip ripgrep git")
                else { add("s", "Testing Termux…"); val r = termux.check(); add("o", r); if (r.startsWith("✗")) add("s", Termux.SETUP) }
            }
            "/mode" -> { st.put("tmode", if (a == "native") "native" else "text"); add("s", "tool mode: " + st.get("tmode")) }
            "/search" -> add("o", clip(Web.search(a, st.get("searx"))))
            "/fetch" -> add("o", clip(Web.fetch(a), 4000))
            "/init" -> agent.run("Explore this project with ls, glob and read. Then create AGENTS.md (max 40 lines): what the project is, how to run and test it, folder layout, coding conventions. Keep it factual.",
                emptyList(), { k, s -> add(k, s) }) { halt }
            "/tools" -> {
                val t = colab.tools()
                add("o", (0 until t.length()).joinToString("\n") {
                    val o = t.getJSONObject(it)
                    o.getString("name") + " " + (o.optJSONObject("inputSchema")?.optJSONObject("properties")?.keys()?.asSequence()?.toList() ?: "")
                })
            }
            "/mcp" -> {
                val q = a.split(" ", limit = 2)
                add("o", clip(colab.call(q[0], JSONObject(q.getOrElse(1) { "{}" })).toString(2)))
            }
            "/export" -> {
                val f = File(app.getExternalFilesDir(null), "colabcode-log.md")
                f.writeText(log.joinToString("\n\n") { "**${it.kind}**\n${it.text}" }); add("s", "Saved ${f.path}")
            }
            "/clear" -> { agent.clear(); ui.post { log.clear() } }
            else -> add("s", "Unknown command. /help")
        }
    }

    private fun io(f: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { try { f() } catch (e: Exception) { add("s", "⚠ " + e.message) } }
    }

    fun doUpload(u: Uri) {
        val r = pick?.second ?: return; pick = null
        io { val b = app.contentResolver.openInputStream(u)!!.use { it.readBytes() }; colab.upload(r, b); add("s", "✓ uploaded ${b.size} B → $r") }
    }

    fun doDownload(u: Uri) {
        val r = pick?.second ?: return; pick = null
        io { val b = colab.download(r); app.contentResolver.openOutputStream(u)!!.use { it.write(b) }; add("s", "✓ saved ${b.size} B") }
    }
}
