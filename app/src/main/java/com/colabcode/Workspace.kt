package com.colabcode

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

interface Workspace {
    val label: String
    fun read(path: String, off: Int, lim: Int): String
    fun write(path: String, content: String): String
    fun edit(path: String, old: String, new: String, all: Boolean): String
    fun ls(path: String): String
    fun glob(pattern: String): String
    fun grep(pattern: String, path: String, include: String): String
    fun bash(cmd: String, timeoutSec: Int): String
    /** Save raw bytes (uploads). Relative paths resolve against the workspace root. */
    fun putBytes(path: String, bytes: ByteArray): String
}

/** App-private sandbox on the phone (Android sh/toybox). */
class Local(private val root: File) : Workspace {
    override val label = "Android app sandbox (sh, cwd = app dir)"
    private fun f(p: String) = if (p.startsWith("/")) File(p) else File(root, p)

    override fun read(path: String, off: Int, lim: Int): String {
        if (f(path).isDirectory) return ls(path)
        return f(path).readLines().drop(off).take(lim).mapIndexed { i, s -> "${off + i + 1}\t${s.take(2000)}" }.joinToString("\n")
    }

    override fun write(path: String, content: String): String {
        val x = f(path); x.parentFile?.mkdirs(); x.writeText(content); return "ok ${content.length} chars"
    }

    override fun edit(path: String, old: String, new: String, all: Boolean): String {
        if (old.isEmpty()) return "ERR old_str empty"
        val x = f(path); val t = x.readText(); val n = t.split(old).size - 1
        if (n == 0) return "ERR old_str not found"
        if (n > 1 && !all) return "ERR $n matches; add context or all=true"
        x.writeText(t.replace(old, new)); return "ok $n replaced"
    }

    override fun ls(path: String) = (f(path).listFiles() ?: emptyArray()).sortedBy { it.name }
        .joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }

    override fun glob(pattern: String): String {
        val m = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return root.walkTopDown().filter { it.isFile && m.matches(it.relativeTo(root).toPath()) }.take(300)
            .joinToString("\n") { it.relativeTo(root).path }
    }

    override fun grep(pattern: String, path: String, include: String): String {
        val rx = Regex(pattern)
        val inc = if (include.isEmpty()) null else FileSystems.getDefault().getPathMatcher("glob:$include")
        val out = ArrayList<String>()
        for (x in f(path).walkTopDown().filter { it.isFile && it.length() < 1_000_000 }) {
            if (inc != null && !inc.matches(Paths.get(x.name))) continue
            runCatching {
                x.useLines { ls ->
                    ls.forEachIndexed { i, l ->
                        if (out.size < 200 && rx.containsMatchIn(l)) out.add("${x.relativeTo(root)}:${i + 1}:${l.take(200)}")
                    }
                }
            }
        }
        return out.joinToString("\n")
    }

    override fun putBytes(path: String, bytes: ByteArray): String {
        val x = f(path); x.parentFile?.mkdirs(); x.writeBytes(bytes); return x.path
    }

    override fun bash(cmd: String, timeoutSec: Int): String {
        val pb = ProcessBuilder("sh", "-c", cmd).directory(root).redirectErrorStream(true)
        pb.environment()["HOME"] = root.path
        val p = pb.start()
        val sb = StringBuffer()
        val t = Thread { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } }.apply { start() }
        val done = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        t.join(2000)
        return sb.toString() + if (done) "[exit ${p.exitValue()}]" else "ERR timeout"
    }
}

/** Colab Linux runtime: every op is one execute_code call running this shim. */
class Remote(private val colab: Colab) : Workspace {
    override val label = "Colab Linux runtime (Ubuntu, cwd /content, root, Python+pip)"

    private val shim = """
import os,json,base64,subprocess,glob,re,fnmatch
a=json.loads(base64.b64decode("@@"))
o=a["op"];p=a.get("path","")
os.chdir("/content")
def r():
    if o=="read":
        L=open(p,errors="replace").read().split("\n");s=a.get("off",0);n=a.get("lim",2000)
        return "\n".join("%d\t%s"%(s+i+1,x) for i,x in enumerate(L[s:s+n]))
    if o=="write":
        d=os.path.dirname(p)
        if d: os.makedirs(d,exist_ok=True)
        open(p,"w").write(a["content"]);return "ok %d chars"%len(a["content"])
    if o=="edit":
        t=open(p).read();n=t.count(a["old"])
        if not a["old"]:return "ERR old_str empty"
        if n==0:return "ERR old_str not found"
        if n>1 and not a.get("all"):return "ERR %d matches; add context or all=true"%n
        open(p,"w").write(t.replace(a["old"],a["new"]));return "ok %d replaced"%n
    if o=="ls":
        q=p or "."
        return "\n".join(sorted(x+("/" if os.path.isdir(os.path.join(q,x)) else "") for x in os.listdir(q)))
    if o=="glob":
        return "\n".join(sorted(glob.glob(a["pat"],recursive=True))[:300])
    if o=="grep":
        rx=re.compile(a["pat"]);out=[]
        for dp,dn,fn in os.walk(p or "."):
            dn[:]=[d for d in dn if not d.startswith(".")]
            for f in fn:
                if a.get("inc") and not fnmatch.fnmatch(f,a["inc"]):continue
                fp=os.path.join(dp,f)
                try:
                    for i,l in enumerate(open(fp,errors="replace"),1):
                        if rx.search(l):out.append("%s:%d:%s"%(fp,i,l.rstrip()[:200]))
                        if len(out)>=200:return "\n".join(out)
                except Exception:pass
        return "\n".join(out)
    if o=="bash":
        try:
            x=subprocess.run(a["cmd"],shell=True,capture_output=True,text=True,errors="replace",timeout=a.get("t",120),cwd="/content")
            return x.stdout+x.stderr+"\n[exit %d]"%x.returncode
        except subprocess.TimeoutExpired:
            return "ERR timeout"
try: R=r()
except Exception as e: R="ERR "+repr(e)
print("<<R>>"+base64.b64encode((R or "")[:40000].encode()).decode()+"<<E>>")
"""

    private fun run(a: JSONObject): String {
        val b64 = Base64.encodeToString(a.toString().toByteArray(), Base64.NO_WRAP)
        val r = colab.exec(shim.replace("@@", b64))
        val out = r.optString("stdout")
        val m = Regex("<<R>>(.*?)<<E>>", RegexOption.DOT_MATCHES_ALL).find(out)
        return if (m != null) String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
        else "ERR " + clip(out + r.optString("stderr") + r.optString("executionError"), 1500)
    }

    private fun op(n: String) = JSONObject().put("op", n)
    override fun read(path: String, off: Int, lim: Int) = run(op("read").put("path", path).put("off", off).put("lim", lim))
    override fun write(path: String, content: String) = run(op("write").put("path", path).put("content", content))
    override fun edit(path: String, old: String, new: String, all: Boolean) =
        run(op("edit").put("path", path).put("old", old).put("new", new).put("all", all))
    override fun ls(path: String) = run(op("ls").put("path", path))
    override fun glob(pattern: String) = run(op("glob").put("pat", pattern))
    override fun grep(pattern: String, path: String, include: String) =
        run(op("grep").put("pat", pattern).put("path", path).put("inc", include))
    override fun bash(cmd: String, timeoutSec: Int) = run(op("bash").put("cmd", cmd).put("t", timeoutSec))
    override fun putBytes(path: String, bytes: ByteArray): String {
        val abs = if (path.startsWith("/")) path else "/content/$path"
        colab.upload(abs, bytes); return abs
    }
}
