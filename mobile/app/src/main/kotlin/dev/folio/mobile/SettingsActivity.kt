package dev.folio.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.PrefsStore
import dev.folio.mobile.ui.theme.FolioMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FolioMobileTheme {
                SettingsScreen()
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsStore = remember { PrefsStore(AppFileStore(context.filesDir)) }
    var prefs by remember { mutableStateOf(ReadingPrefs()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        prefs = withContext(Dispatchers.IO) { prefsStore.loadReadingPrefs() }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                busy = true
                status = try {
                    withContext(Dispatchers.IO) { restoreBackup(context, it) }
                    "Restore complete"
                } catch (t: Throwable) {
                    "Restore failed: ${t.message}"
                }
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("light", "warm", "dark", "true-black", "system").forEach { key ->
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val updated = prefs.copy(theme = key)
                                prefs = updated
                                withContext(Dispatchers.IO) { prefsStore.saveReadingPrefs(updated) }
                            }
                        }
                    ) { Text(if (prefs.theme == key) "• $key" else key) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text(storageInfo(context), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))
            Text("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = try {
                                withContext(Dispatchers.IO) { exportBackup(context) }
                                "Backup saved to Downloads"
                            } catch (t: Throwable) {
                                "Backup failed: ${t.message}"
                            }
                            busy = false
                        }
                    }
                ) { Text("Export backup") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) }
                ) { Text("Restore backup") }
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            if (busy) {
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun storageInfo(context: Context): String {
    val dir = context.filesDir
    val stat = StatFs(dir.absolutePath)
    val total = stat.totalBytes
    val free = stat.availableBytes
    val used = total - free
    return "Used: ${used / (1024 * 1024)} MB of ${total / (1024 * 1024)} MB"
}

private fun exportBackup(context: Context): String {
    val src = File(context.filesDir, "library")
    if (!src.exists()) throw IllegalStateException("No library")
    val outDir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
    val name = "folio-backup-${System.currentTimeMillis()}.zip"
    val out = File(outDir, name)
    ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
        src.walkTopDown().forEach { f ->
            if (f.isFile) {
                val rel = f.relativeTo(src.parentFile!!).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(rel))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
    return out.absolutePath
}

private fun restoreBackup(context: Context, uri: Uri) {
    val dest = File(context.filesDir, "library")
    dest.mkdirs()
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(context.filesDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    } ?: throw IllegalStateException("Cannot read backup")
}