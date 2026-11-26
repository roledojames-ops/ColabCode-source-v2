package com.colabcode

import org.json.JSONArray
import org.json.JSONObject

/** One tool: used to build both the native OpenAI schema and the text-mode prompt. params = (name, type, required) */
class Spec(val name: String, val desc: String, val params: List<Triple<String, String, Boolean>>, val usage: String)

class Call(val name: String, val args: Map<String, String>, val thought: String, val end: Int)

/**
 * OpenCode-style agent loop over any OpenAI-compatible /chat/completions endpoint.
 *
 * Two tool protocols (Settings → "Tool mode"):
 *  - text   (default): the model writes <tool name="..." attr="..">BODY</tool>. Works with small models whose
 *                       chat template has no tool support (e.g. Llama-3-8B). One call per turn, strict few-shot prompt.
 *  - native: standard OpenAI `tools` / `tool_calls`.
 */
class Agent(private val st: Store, private val w: () -> Workspace, private val tx: () -> Termux) {
    private val msgs = JSONArray()
    private var todo = ""
    private var lastTask: JSONObject? = null

    fun clear() { while (msgs.length() > 0) msgs.remove(0); todo = ""; lastTask = null }

    // ───────────────────────── tool specs ─────────────────────────
    private fun p(n: String, t: String = "string", r: Boolean = false) = Triple(n, t, r)

    private val specs = listOf(
        Spec("read", "Read a file with line numbers (or list a directory). Use offset/limit for big files.",
            listOf(p("path", r = true), p("offset", "integer"), p("limit", "integer")),
            """read path="src/app.py" offset="0" limit="150""""),
        Spec("write", "Create or overwrite a whole file. Prefer edit for existing files.",
            listOf(p("path", r = true), p("content", r = true)),
            """write path="notes.txt"   (BODY = full file content)"""),
        Spec("edit", "Replace exact text old_str with new_str in an existing file. old_str must match exactly and be unique.",
            listOf(p("path", r = true), p("old_str", r = true), p("new_str", r = true), p("all", "boolean")),
            "edit path=\"f.py\"   (BODY = one or more blocks, see EDIT BODY FORMAT)"),
        Spec("ls", "List a directory", listOf(p("path")), """ls path=".""""),
        Spec("glob", "Find files by glob pattern (supports **)", listOf(p("pattern", r = true)), """glob pattern="**/*.py""""),
        Spec("grep", "Regex search inside files", listOf(p("pattern", r = true), p("path"), p("include")),
            """grep pattern="def main" path="." include="*.py""""),
        Spec("bash", "Run a shell command in the workspace. Each call starts in the workspace root (use cd x && ...).",
            listOf(p("command", r = true), p("timeout", "integer")),
            """bash timeout="60"   (BODY = the shell command)"""),
        Spec("fetch", "Download a web page/API as text (HTML is converted to text).",
            listOf(p("url", r = true), p("method"), p("headers"), p("body"), p("max", "integer")),
            """fetch url="https://example.com" max="4000""""),
        Spec("search", "Search the web. Returns titles, URLs, snippets. Then use fetch on a URL.",
            listOf(p("query", r = true)), """search query="python asyncio timeout""""),
        Spec("todo", "Write/update your task checklist (shown to you every turn). Lines: '- [ ] step' or '- [x] done'.",
            listOf(p("list", r = true)), """todo   (BODY = checklist lines)"""),
        Spec("termux", "Run a command on the user's phone in Termux (pkg, termux-notification, termux-clipboard-get, termux-open-url, ...).",
            listOf(p("command", r = true), p("timeout", "integer")), """termux   (BODY = command)""")
    )

    private fun visible() = specs.filter { it.name != "termux" || w() !is Termux }

    private fun nativeTools() = JSONArray().also { a ->
        for (s in visible()) {
            val props = JSONObject(); val req = JSONArray()
            s.params.forEach { (n, t, r) -> props.put(n, JSONObject().put("type", t)); if (r) req.put(n) }
            a.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", s.name).put("description", s.desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))))
        }
    }

    // ───────────────────────── prompts ─────────────────────────
    private fun extras(agentsMd: String) =
        (if (todo.isNotBlank()) "\n\nYOUR CURRENT TODO:\n$todo" else "") +
            (if (agentsMd.isNotBlank()) "\n\nPROJECT NOTES (AGENTS.md):\n${agentsMd.take(1200)}" else "")

    private fun textPrompt(agentsMd: String): String {
        val tools = visible().joinToString("\n") { "- " + it.usage + "  → " + it.desc }
        return """You are ColabCode, a careful coding agent. Workspace: ${w().label}.
You work ONLY by calling tools. You cannot see files or the internet except through tools.

TOOL CALL FORMAT (exactly like this):
<tool name="NAME" attr="value"> optional BODY </tool>

RULES
1. Every reply = one line "THINK: ..." (max 2 short sentences: what you know, what you do next) + ONE tool call. Then STOP. The result comes in the next message.
2. When the task is finished (or you only need to talk), answer in plain text with NO tool call.
3. Never guess file contents, command output or web facts. Look first (read / ls / grep / search / fetch), then act.
4. Read a file before editing it. Edit with small exact blocks. After changes, verify (run it / read it again).
5. If a tool returns ERR, read the message, fix the arguments and try a DIFFERENT call. Never repeat the same failing call.
6. For tasks with 3+ steps, call todo first, then update it as you go.
7. Never write <result>. Never write more than one <tool>.

TOOLS
$tools

EDIT BODY FORMAT (old text must be copied exactly from a fresh read):
<<<<<<< OLD
exact existing text
=======
replacement text
>>>>>>> NEW

EXAMPLE 1
user: add a --verbose flag to cli.py
THINK: I must read cli.py before editing it.
<tool name="read" path="cli.py"/>
(result comes back; then)
THINK: The parser is built on line 12, I will add the flag right after it.
<tool name="edit" path="cli.py">
<<<<<<< OLD
p.add_argument("-o")
=======
p.add_argument("-o")
p.add_argument("--verbose", action="store_true")
>>>>>>> NEW
</tool>

EXAMPLE 2
user: what is the newest stable Node.js?
THINK: This changes over time, so I should search the web.
<tool name="search" query="latest stable Node.js release"/>

EXAMPLE 3
user: run the tests
THINK: I will run the test suite and look at the output.
<tool name="bash" timeout="120">python -m pytest -x -q</tool>""" + extras(agentsMd)
    }

    private fun nativePrompt(agentsMd: String) =
        "Coding agent in: ${w().label}. Use the tools to read, edit and run code. Look before you act (read/ls/grep/search), " +
            "read a file before editing it (prefer edit over write), verify your work, and never guess results. " +
            "Each bash call starts in the workspace root. For 3+ step tasks use todo. Be terse." + extras(agentsMd)

    // ───────────────────────── parsing (text mode) ─────────────────────────
    private val tagRx = Regex("<tool\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>", RegexOption.IGNORE_CASE)
    private val attrRx = Regex("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    private fun parseText(c: String): Call? {
        val m = tagRx.find(c) ?: return null
        val attrs = mutableMapOf<String, String>()
        attrRx.findAll(m.groupValues[1]).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val name = (attrs.remove("name") ?: Regex("^\\s*(\\w+)").find(m.groupValues[1])?.groupValues?.get(1) ?: return null).lowercase()
        var end = m.range.last + 1
        var body = ""
        if (m.groupValues[2] != "/") {
            val rest = c.substring(end)
            val close = Regex("</tool\\s*>", RegexOption.IGNORE_CASE).find(rest)
            body = (if (close != null) rest.substring(0, close.range.first) else rest).removePrefix("\n").trimEnd()
            end += if (close != null) close.range.last + 1 else rest.length
        }
        if (body.isNotEmpty()) attrs["body"] = body
        return Call(name, attrs, c.substring(0, m.range.first).trim(), end)
    }

    /** Fallback: model emitted {"name":..,"args":{..}} instead of the tag format. */
    private fun parseJson(c: String): Call? {
        val i = c.indexOf('{'); val j = c.lastIndexOf('}')
        if (i < 0 || j <= i) return null
        val o = runCatching { JSONObject(c.substring(i, j + 1)) }.getOrNull() ?: return null
        val name = (o.optString("name").ifEmpty { o.optString("tool").ifEmpty { o.optString("function") } }).lowercase()
        if (name.isEmpty()) return null
        val a = o.optJSONObject("args") ?: o.optJSONObject("arguments") ?: o.optJSONObject("parameters") ?: JSONObject()
        return Call(name, asMap(a), c.substring(0, i).trim(), c.length)
    }

    private fun asMap(o: JSONObject): Map<String, String> = o.keys().asSequence().associateWith { if (o.isNull(it)) "" else o.get(it).toString() }

    private fun blocks(body: String) = Regex("<<<<<<<[^\\n]*\\n(.*?)\\n=======\\n(.*?)\\n?>>>>>>>[^\\n]*", RegexOption.DOT_MATCHES_ALL)
        .findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()

    // ───────────────────────── tool execution ─────────────────────────
    private fun exec(name: String, a: Map<String, String>): String = try {
        val ws = w()
        fun s(vararg k: String) = k.firstNotNullOfOrNull { a[it]?.takeIf { v -> v.isNotEmpty() } } ?: ""
        fun need(vararg k: String) = s(*k).ifEmpty { error("missing argument '${k[0]}'") }
        when (name) {
            "read" -> {
                val off = s("offset").toIntOrNull() ?: 0; val lim = s("limit").toIntOrNull() ?: 150
                val r = ws.read(need("path", "file"), off, lim)
                if (r.lines().size >= lim && !r.startsWith("ERR")) r + "\n[more lines may follow: read again with offset=${off + lim}]" else r
            }
            "write" -> ws.write(need("path", "file"), (a["content"] ?: a["body"] ?: error("missing file content (put it in the tool BODY)")).let { if (a["content"] == null && it.isNotEmpty()) it + "\n" else it })
            "edit" -> {
                val path = need("path", "file")
                val pairs = if (a.containsKey("old_str")) listOf(a["old_str"]!! to (a["new_str"] ?: "")) else blocks(a["body"] ?: "")
                if (pairs.isEmpty()) error("edit needs a block: <<<<<<< OLD / ======= / >>>>>>> NEW")
                val all = a["all"]?.lowercase() == "true"
                pairs.mapIndexed { i, (o, n) -> ws.edit(path, o, n, all).let { r -> if (pairs.size > 1) "block ${i + 1}: $r" else r } }.joinToString("\n")
            }
            "ls" -> ws.ls(s("path").ifEmpty { "." })
            "glob" -> ws.glob(need("pattern", "body"))
            "grep" -> ws.grep(need("pattern", "body"), s("path").ifEmpty { "." }, s("include"))
            "bash" -> ws.bash(need("command", "cmd", "body"), (s("timeout").toIntOrNull() ?: 120).coerceIn(1, 600))
            "termux" -> tx().bash(need("command", "cmd", "body"), (s("timeout").toIntOrNull() ?: 120).coerceIn(1, 600))
            "fetch" -> Web.fetch(need("url"), s("method"), s("headers"), s("body"), s("max").toIntOrNull() ?: 4000)
            "search" -> Web.search(need("query", "q", "body"), st.get("searx"))
            "todo" -> { todo = need("list", "body"); "ok, todo saved:\n$todo" }
            else -> "ERR unknown tool '$name'. Available: " + visible().joinToString { it.name }
        }
    } catch (e: Exception) { "ERR " + (e.message ?: e.toString()) }

    // ───────────────────────── context budget ─────────────────────────
    private fun num(k: String, d: Int) = st.get(k).toIntOrNull() ?: d
    private fun est(m: JSONObject) = (m.toString().length / 3.2).toInt() + 6

    private fun payload(sys: String, native: Boolean): JSONArray {
        val ctxT = num("ctx", 7000); val maxTok = num("maxtok", 1024)
        val n = msgs.length()
        val list = ArrayList<JSONObject>()
        for (i in 0 until n) {
            var m = msgs.getJSONObject(i)
            val c = m.optString("content")
            val oldResult = m.optString("role") == "tool" || (m.optString("role") == "user" && c.startsWith("<result"))
            if (i < n - 6 && oldResult && c.length > 300) m = JSONObject(m.toString()).put("content", c.take(300) + "…[old output trimmed]")
            list.add(m)
        }
        val sysMsg = JSONObject().put("role", "system").put("content", sys)
        var total = list.sumOf { est(it) }
        val budget = ctxT - maxTok - est(sysMsg)
        while (total > budget && list.size > 1) total -= est(list.removeAt(0))
        while (list.isNotEmpty() && list[0].optString("role") != "user") list.removeAt(0)
        lastTask?.let { t -> if (list.none { it === t }) list.add(0, t) }
        val out = JSONArray().put(sysMsg)
        var prev: JSONObject? = null
        for (m in list) {
            val role = m.optString("role")
            val plain = (role == "user" || role == "assistant") && !m.has("tool_calls") && m.opt("content") is String
            if (!native && plain && prev != null && prev.optString("role") == role && prev.opt("content") is String) {
                prev.put("content", prev.getString("content") + "\n\n" + m.getString("content")); continue
            }
            val cp = JSONObject(m.toString()); out.put(cp); prev = if (plain) cp else null
        }
        return out
    }

    private fun looksUnfinished(c: String) = c.length < 700 &&
        Regex("(?i)\\b(let me|i will|i'll|i am going to|i'm going to|next,? i|now i|first,? i|let's)\\b").containsMatchIn(c.takeLast(300))

    // ───────────────────────── main loop ─────────────────────────
    fun run(user: String, images: List<String>, emit: (String, String) -> Unit, stop: () -> Boolean) {
        while (msgs.length() > 60) { msgs.remove(0); while (msgs.length() > 0 && msgs.getJSONObject(0).optString("role") != "user") msgs.remove(0) }
        val native = st.get("tmode") == "native"
        val agentsMd = runCatching { w().read("AGENTS.md", 0, 40) }.getOrDefault("").takeIf { !it.startsWith("ERR") } ?: ""
        val content: Any = if (images.isNotEmpty() && st.get("vision") == "1")
            JSONArray().put(JSONObject().put("type", "text").put("text", user)).also { a ->
                images.forEach { a.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", it))) } }
        else user
        val task = JSONObject().put("role", "user").put("content", content)
        lastTask = task; msgs.put(task)

        val maxOut = (num("ctx", 7000) / 2).coerceIn(1500, 6000)
        val seen = ArrayList<String>(); var nudges = 0

        repeat(30) {
            if (stop()) { emit("s", "stopped"); return }
            val sys = if (native) nativePrompt(agentsMd) else textPrompt(agentsMd)
            val body = JSONObject().put("model", st.get("model")).put("messages", payload(sys, native))
                .put("max_tokens", num("maxtok", 1024)).put("temperature", st.get("temp").toDoubleOrNull() ?: 0.2)
            if (native) body.put("tools", nativeTools()) else body.put("stop", JSONArray().put("</tool>").put("<result>"))
            val (c, t) = Http.req("POST", st.get("base").trimEnd('/') + "/chat/completions",
                mapOf("Authorization" to "Bearer " + st.get("key"), "Content-Type" to "application/json"), body.toString())
            if (c !in 200..299) { emit("s", "⚠ LLM HTTP $c: ${clip(t, 600)}"); return }
            val m = JSONObject(t).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val text = if (m.isNull("content")) "" else m.getString("content")

            if (native) {
                val am = JSONObject().put("role", "assistant").put("content", if (m.isNull("content")) JSONObject.NULL else text)
                val tcs = m.optJSONArray("tool_calls")?.takeIf { it.length() > 0 }
                if (tcs != null) am.put("tool_calls", tcs)
                msgs.put(am)
                if (text.isNotBlank()) emit(if (tcs != null) "k" else "a", text)
                if (tcs == null) return
                for (i in 0 until tcs.length()) {
                    val tc = tcs.getJSONObject(i); val fn = tc.getJSONObject("function")
                    val args = runCatching { asMap(JSONObject(fn.optString("arguments").ifBlank { "{}" })) }.getOrDefault(emptyMap())
                    val out = runTool(fn.getString("name"), args, seen, maxOut, emit)
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", tc.getString("id")).put("content", out))
                }
                return@repeat
            }

            // ── text mode ──
            val call = parseText(text) ?: parseJson(text)
            if (call == null) {
                msgs.put(JSONObject().put("role", "assistant").put("content", text.ifBlank { "(empty)" }))
                if (nudges < 2 && (text.isBlank() || looksUnfinished(text))) {
                    nudges++
                    if (text.isNotBlank()) emit("k", text)
                    msgs.put(JSONObject().put("role", "user").put("content",
                        "You wrote no <tool> call. If you still need to act, reply with THINK: ... and ONE <tool name=\"...\" .../> call now. If the task is complete, give the final answer."))
                    return@repeat
                }
                emit("a", text); return
            }
            msgs.put(JSONObject().put("role", "assistant").put("content", text.substring(0, call.end.coerceAtMost(text.length))))
            if (call.thought.isNotBlank()) emit("k", call.thought)
            val out = runTool(call.name, call.args, seen, maxOut, emit)
            msgs.put(JSONObject().put("role", "user").put("content",
                "<result tool=\"${call.name}\">\n$out\n</result>\nNext: THINK + one <tool> call, or the final answer if the task is done."))
        }
        emit("s", "step limit reached")
    }

    private fun runTool(name: String, a: Map<String, String>, seen: MutableList<String>, maxOut: Int, emit: (String, String) -> Unit): String {
        emit("t", "▸ $name " + a.entries.joinToString(" ") { it.key + "=" + it.value.take(90).replace("\n", " ⏎ ") }.take(220))
        val sig = name + a.toString()
        val repeated = seen.takeLast(2).count { it == sig } >= 2
        seen.add(sig)
        val out = if (repeated) "ERR you already made this exact call twice. Change your approach: different arguments or a different tool, or give your final answer."
        else clip(exec(name, a), maxOut)
        emit("o", out.take(400))
        if (name == "todo") emit("s", "☑ todo updated")
        return out
    }
}
