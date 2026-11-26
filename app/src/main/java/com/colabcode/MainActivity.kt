package com.colabcode

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BG = Color(0xFF0B1020)

class MainActivity : ComponentActivity() {
    private val vm: VM by viewModels()
    private val termuxPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val us = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { us.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.let { us.addAll(it) }
        }
        if (us.isNotEmpty()) vm.attach(us)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.openUrl = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        vm.requestTermux = { termuxPerm.launch("com.termux.permission.RUN_COMMAND") }
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = BG) { if (vm.showSettings) Settings(vm) else Chat(vm) }
            }
        }
    }
}

@Composable
fun Chat(vm: VM) {
    var input by remember { mutableStateOf("") }
    val ls = rememberLazyListState()
    LaunchedEffect(vm.log.size) { if (vm.log.isNotEmpty()) ls.scrollToItem(vm.log.size - 1) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { vm.doUpload(u) } else { vm.pick = null } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) { vm.doDownload(u) } else { vm.pick = null } }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> if (us.isNotEmpty()) vm.attach(us) }
    LaunchedEffect(vm.pick) { vm.pick?.let { (k, p) -> if (k == "up") open.launch(arrayOf("*/*")) else save.launch(p.substringAfterLast('/')) } }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ColabCode · " + when (vm.wsMode) { "local" -> "local"; "termux" -> "termux"; else -> vm.rt.ifEmpty { "no runtime" } },
                color = Color(0xFFF9AB00), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton({ vm.showSettings = true }) { Text("⚙") }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = ls, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(vm.log) { l ->
                    val c = when (l.kind) {
                        "u" -> Color(0xFF22D3EE); "t" -> Color(0xFFF9AB00); "o" -> Color(0xFF9CA3AF); "s" -> Color(0xFFFDE68A); "k" -> Color(0xFF6B7280); else -> Color.White
                    }
                    Text(l.text, color = c, fontSize = 13.sp, fontFamily = if (l.kind == "a" || l.kind == "u") FontFamily.Default else FontFamily.Monospace)
                }
            }
        }
        if (vm.attached.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
            vm.attached.toList().forEach { f -> TextButton({ vm.detach(f) }) { Text("📎 " + f.name + " ✕", fontSize = 11.sp) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ pickMany.launch(arrayOf("*/*")) }) { Text("📎") }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask, or /help") }, maxLines = 4)
            Button({ if (vm.busy) vm.stop() else { vm.send(input); input = "" } }) { Text(if (vm.busy) "■" else "▶") }
        }
    }
}

@Composable
fun Settings(vm: VM) {
    val keys = listOf(
        "base" to "LLM base URL (OpenAI-compatible)", "key" to "LLM API key", "model" to "Model",
        "cid" to "Google OAuth client ID (Desktop type)", "csec" to "OAuth client secret", "scope" to "OAuth scope",
        "atoken" to "…or paste a Google access token",
        "tmode" to "Tool mode: text (small models) or native", "ctx" to "Model context window (tokens, Llama-3-8B = 8192 → use 7000)",
        "maxtok" to "Max reply tokens", "temp" to "Temperature (0.2 recommended)",
        "searx" to "SearXNG URL for web search (optional, else DuckDuckGo)", "vision" to "Send images to model? 1 = yes, 0 = no"
    )
    val secret = setOf("key", "csec", "atoken")
    val f = remember { mutableStateMapOf<String, String>().apply { keys.forEach { put(it.first, vm.st.get(it.first)) } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (k, label) ->
            OutlinedTextField(f[k] ?: "", { f[k] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
                visualTransformation = if (k in secret) PasswordVisualTransformation() else VisualTransformation.None)
        }
        Text("Workspace", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("colab", "local", "termux").forEach { m -> Button({ vm.setWs(m) }) { Text(if (vm.wsMode == m) "● $m" else m) } }
        }
        Button({ vm.saveSettings(f.toMap()); vm.showSettings = false }) { Text("Save") }
    }
}
