package com.colabcode

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TermuxRes(val out: String, val err: String, val code: Int, val truncated: Boolean, val fail: String?)

/**
 * Runs commands inside the Termux app via its RUN_COMMAND intent.
 * Needs: Termux installed + opened once, `allow-external-apps=true` in ~/.termux/termux.properties,
 * and the "Run commands in Termux environment" permission granted to ColabCode.
 */
class Termux(private val ctx: Context, private val needPerm: () -> Unit = {}) : Workspace {
    override val label = "Termux on this phone (bash, pkg, cwd = ~/colabcode; each command starts fresh in that dir)"
    private val prefix = "/data/data/com.termux/files/usr"
    private val home = "/data/data/com.termux/files/home"
    private val seq = AtomicInteger()

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun p(s: String) = q(if (s == "~" || s.startsWith("~/")) home + s.drop(1) else s)

    fun exec(script: String, timeoutSec: Int = 120): TermuxRes {
        val action = "com.colabcode.TERMUX_RESULT." + seq.incrementAndGet()
        val latch = CountDownLatch(1)
        var res = TermuxRes("", "", -1, false, "no result")
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val b = i.getBundleExtra("result")
                res = if (b == null) TermuxRes("", "", -1, false, "Termux returned no result bundle")
                else {
                    val errCode = b.getInt("err", -1)
                    val fail = if (errCode > 0) "Termux error $errCode: ${b.getString("errmsg", "")}" else null
                    val o = b.getString("stdout", "") ?: ""
                    TermuxRes(o, b.getString("stderr", "") ?: "", b.getInt("exitCode", -1),
                        b.getInt("stdout_original_length", o.length) > o.length, fail)
                }
                latch.countDown()
            }
        }
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(rcv, f)
        try {
            val cb = Intent(action).setPackage(ctx.packageName)
            val fl = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, seq.get(), cb, fl)
            val full = "mkdir -p ~/colabcode && cd ~/colabcode && timeout ${timeoutSec}s bash -c ${q(script)}"
            val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            i.putExtra("com.termux.RUN_COMMAND_PATH", "$prefix/bin/bash")
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", full))
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            try {
                ctx.startService(i)
            } catch (e: SecurityException) {
                needPerm()
                return TermuxRes("", "", -1, false, SETUP)
            } catch (e: Exception) {
                return TermuxRes("", "", -1, false, "Cannot start Termux (${e.message}). $SETUP")
            }
            if (!latch.await(timeoutSec + 15L, TimeUnit.SECONDS)) return TermuxRes("", "", -1, false, "timeout waiting for Termux. $SETUP")
            return res
        } finally {
            runCatching { ctx.unregisterReceiver(rcv) }
        }
    }

    private fun fmt(r: TermuxRes, withExit: Boolean = true): String {
        if (r.fail != null) return "ERR ${r.fail}"
        val s = r.out + (if (r.err.isNotBlank()) (if (r.out.isNotEmpty() && !r.out.endsWith("\n")) "\n" else "") + r.err else "")
        return s + (if (r.truncated) "\n[output truncated by Termux]" else "") + if (withExit) "\n[exit ${r.code}]" else ""
    }

    fun check(): String {
        val r = exec("echo termux-ok; uname -m; command -v python python3 curl pdftotext unzip rg git 2>/dev/null; ls ~/storage 2>/dev/null | head -3", 30)
        return if (r.fail != null) "✗ ${r.fail}" else "✓ " + r.out.trim()
    }

    override fun read(path: String, off: Int, lim: Int) = fmt(exec(
        "f=${p(path)}; if [ -d \"\$f\" ]; then ls -1Ap \"\$f\"; elif [ -f \"\$f\" ]; then " +
            "awk -v s=$off -v n=$lim 'NR>s && NR<=s+n {printf \"%d\\t%s\\n\", NR, substr(\$0,1,2000)}' \"\$f\"; else echo 'ERR no such file'; fi"), false)

    private fun putB64(path: String, bytes: ByteArray): String {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val chunks = if (b64.isEmpty()) listOf("") else b64.chunked(60_000)
        for ((i, c) in chunks.withIndex()) {
            val redir = if (i == 0) ">" else ">>"
            val pre = if (i == 0) "mkdir -p \"\$(dirname ${p(path)})\" && " else ""
            val r = exec("$pre printf %s '$c' | base64 -d $redir ${p(path)}", 60)
            if (r.fail != null) return "ERR ${r.fail}"
            if (r.code != 0) return "ERR " + (r.err.ifBlank { "write failed (exit ${r.code})" })
        }
        return "ok"
    }

    override fun write(path: String, content: String): String {
        val r = putB64(path, content.toByteArray())
        return if (r == "ok") "ok ${content.length} chars" else r
    }

    override fun putBytes(path: String, bytes: ByteArray): String {
        val r = putB64(path, bytes)
        if (r != "ok") error(r)
        return path
    }

    override fun edit(path: String, old: String, new: String, all: Boolean): String {
        if (old.isEmpty()) return "ERR old_str empty"
        val r = exec("f=${p(path)}; [ -f \"\$f\" ] || { echo NOFILE; exit 3; }; base64 -w0 \"\$f\"", 60)
        if (r.fail != null) return "ERR ${r.fail}"
        if (r.code == 3) return "ERR no such file"
        if (r.truncated) return "ERR file too large to edit through Termux; use bash with sed/python"
        val t = String(Base64.decode(r.out.trim(), Base64.DEFAULT))
        val n = t.split(old).size - 1
        if (n == 0) return "ERR old_str not found (copy it exactly from a fresh read)"
        if (n > 1 && !all) return "ERR $n matches; add more context lines or all=true"
        val w = write(path, t.replace(old, new))
        return if (w.startsWith("ERR")) w else "ok $n replaced"
    }

    override fun ls(path: String) = fmt(exec("ls -1Ap -- ${p(path.ifEmpty { "." })}"), false)

    override fun glob(pattern: String) = fmt(exec(
        "shopt -s globstar nullglob dotglob; pat=${q(pattern)}; for f in \$pat; do [ -f \"\$f\" ] && echo \"\$f\"; done | grep -v '^\\.git/' | head -300"), false)

    override fun grep(pattern: String, path: String, include: String) = fmt(exec(
        "grep -rnE -I --exclude-dir=.git --exclude-dir=node_modules ${if (include.isEmpty()) "" else "--include=" + q(include)} -e ${q(pattern)} -- ${p(path.ifEmpty { "." })} | head -200 | cut -c1-300"), false)

    override fun bash(cmd: String, timeoutSec: Int) = fmt(exec(cmd, timeoutSec))

    companion object {
        const val SETUP = "Termux setup: (1) open Termux once; (2) in Termux run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings; " +
            "(3) Android Settings → Apps → ColabCode → Permissions → Additional permissions → allow 'Run commands in Termux environment'. Then /termux to test."
    }
}
