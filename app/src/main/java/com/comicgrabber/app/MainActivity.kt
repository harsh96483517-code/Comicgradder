package com.comicgrabber.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App(vm) } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var showUrl by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val jobs by vm.engine.jobs.collectAsState()
    val page = vm.page
    val count = vm.selected.size
    val unfinished = jobs.count { it.status != Status.DONE && it.status != Status.FAILED }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke()
        else Toast.makeText(ctx, "Storage permission is required to save files", Toast.LENGTH_LONG).show()
        pending = null
    }

    /** Android 10+ needs no permission (MediaStore); Android 7–9 needs WRITE_EXTERNAL_STORAGE. */
    fun withStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) action() else {
            pending = action
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val pendingDl = vm.pendingDownload
    LaunchedEffect(pendingDl) {
        if (pendingDl != null) {
            withStorage {
                vm.engine.enqueue(listOf(pendingDl))
                Toast.makeText(ctx, "Queued: ${pendingDl.title}", Toast.LENGTH_SHORT).show()
            }
            vm.consumePending()
        }
    }

    fun uncheckAllInPage() {
        web?.evaluateJavascript("window.__cgSelectAll && window.__cgSelectAll(false)", null)
    }

    fun startSelected() = withStorage {
        vm.downloadSelected()
        uncheckAllInPage()
        Toast.makeText(ctx, "Added to queue", Toast.LENGTH_SHORT).show()
    }

    BackHandler {
        if (web?.canGoBack() == true) web?.goBack() else (ctx as? Activity)?.finish()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Comic Grabber")
                        Text(
                            page.url.substringAfter("://").substringBefore("/").ifBlank { "…" },
                            style = MaterialTheme.typography.labelSmall, maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton({ web?.loadUrl(vm.startUrl) }) { Icon(Icons.Default.Home, "Home") }
                    IconButton({ web?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton({ showUrl = true }) { Icon(Icons.Default.Edit, "Change start URL") }
                    IconButton({ showQueue = true }) {
                        BadgedBox(badge = { if (unfinished > 0) Badge { Text("$unfinished") } }) {
                            Icon(Icons.Default.Download, "Queue")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (page.type == "listing") {
                BottomAppBar(
                    actions = {
                        TextButton({ web?.evaluateJavascript("window.__cgSelectAll && window.__cgSelectAll(true)", null) }) {
                            Text("Select all")
                        }
                        TextButton({ vm.selected.clear(); uncheckAllInPage() }, enabled = count > 0) { Text("Clear") }
                    },
                    floatingActionButton = {
                        if (count > 0) ExtendedFloatingActionButton(
                            onClick = { startSelected() },
                            icon = { Icon(Icons.Default.Download, null) },
                            text = { Text("Download Selected ($count)") }
                        )
                    }
                )
            }
        },
        floatingActionButton = {
            if (page.type == "comic") ExtendedFloatingActionButton(
                onClick = { withStorage { vm.downloadCurrent() } },
                icon = { Icon(Icons.Default.Download, null) },
                text = { Text("Download this comic") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (jobs.isNotEmpty()) ProgressCard(jobs) { showQueue = true }
            BrowserView(Modifier.weight(1f).fillMaxWidth(), vm) { web = it }
        }
    }

    if (showUrl) {
        var text by remember { mutableStateOf(vm.startUrl) }
        AlertDialog(
            onDismissRequest = { showUrl = false },
            title = { Text("Start URL") },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("https://…") })
            },
            confirmButton = {
                TextButton({
                    web?.loadUrl(vm.setStart(text)); showUrl = false
                }) { Text("Go") }
            },
            dismissButton = {
                TextButton({ text = MainViewModel.DEFAULT_URL }) { Text("Reset") }
            }
        )
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton({ vm.engine.cancelAll() }, enabled = unfinished > 0) { Text("Cancel all") }
                OutlinedButton({ vm.engine.clearFinished() }) { Text("Clear finished") }
            }
            if (jobs.isEmpty()) Text("Queue is empty", Modifier.padding(16.dp))
            LazyColumn {
                items(jobs, key = { it.id }) { j ->
                    ListItem(
                        headlineContent = { Text(j.comic.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text("${j.status.name.lowercase()} · ${j.message}", maxLines = 2)
                                if (j.status == Status.DOWNLOADING)
                                    LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
                            }
                        },
                        trailingContent = {
                            if (j.status == Status.DOWNLOADING || j.status == Status.RESOLVING)
                                CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressCard(jobs: List<DlJob>, onClick: () -> Unit) {
    val finished = jobs.count { it.status == Status.DONE || it.status == Status.FAILED }
    val current = jobs.firstOrNull { it.status == Status.DOWNLOADING || it.status == Status.RESOLVING }
    val overall = (finished + (current?.progress ?: 0f)) / jobs.size
    Surface(onClick = onClick, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (current != null) "${finished + 1}/${jobs.size} · ${current.comic.title}"
                else "Finished $finished/${jobs.size} (${jobs.count { it.status == Status.FAILED }} failed)",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            current?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}
