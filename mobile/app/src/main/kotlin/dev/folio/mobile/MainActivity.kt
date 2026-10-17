package dev.folio.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.importer.TxtImporter
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.reader.ReaderActivity
import dev.folio.mobile.ui.theme.FolioMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library screen (temporary v1 harness): loads the manifest, offers a debug
 * action to import a bundled sample text (original prose, no copyrighted
 * content) and opens docs in the reader. The real library UI
 * (covers / collections / search) lands in M4.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FolioMobileTheme {
                LibraryScreen()
            }
        }
    }
}

private fun appLibrary(context: Context): LibraryManager =
    LibraryManager(AppFileStore(context.filesDir))

private suspend fun importSample(context: Context) {
    withContext(Dispatchers.IO) {
        // Original prose written for Folio's smoke tests (no copyrighted content).
        val sample = buildString {
            appendLine("The Reader's Lantern")
            appendLine()
            appendLine("A folio test chapter, kept deliberately short so the import runs fast.")
            appendLine()
            appendLine("Once the lantern was lit, the pages seemed to step out of the dark, one column at a time. The reader turned them slowly, watching the margins breathe.")
            appendLine()
            appendLine("She had carried this little library through three cities: a bag of old paperbacks, a folding stand, and one worry — that the words might someday stop fitting together. They never did.")
            appendLine()
            appendLine("The second chapter is where the reading comfort settings earn their keep: line height, margins, and the letters set free on the page.")
            appendLine()
            appendLine("By the third evening she was quoting the book to the mirror, which is, the lantern agreed, the whole point of owning one.")
        }
        val result = TxtImporter.import(
            sample.toByteArray(Charsets.UTF_8),
            "sample.txt"
        )
        appLibrary(context).addDocument(result)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<DocMeta>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            docs = withContext(Dispatchers.IO) { appLibrary(context).listDocuments() }
        } catch (t: Throwable) {
            error = t.message
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Folio") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                error?.let {
                    Text("Error: $it", color = MaterialTheme.colorScheme.error)
                }
                if (docs.isEmpty()) {
                    Text(
                        "Library is empty.\n" +
                            "Real import UI (EPUB / TXT / HTML / PDF) lands in a later phase.\n" +
                            "Use the sample action below to smoke-test the reader.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .weight(1f, fill = false)
                    ) {
                        items(docs, key = { it.id }) { doc ->
                            ListItem(
                                headlineContent = { Text(doc.title) },
                                supportingContent = { Text(doc.author ?: doc.slug) },
                                leadingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier.clickable {
                                    context.startActivity(
                                        ReaderActivity.createIntent(context, doc.id)
                                    )
                                }
                            )
                        }
                    }
                }
            }
            Button(
                enabled = !importing,
                onClick = {
                    scope.launch {
                        importing = true
                        try {
                            importSample(context)
                            docs = withContext(Dispatchers.IO) {
                                appLibrary(context).listDocuments()
                            }
                        } catch (t: Throwable) {
                            error = t.message
                        } finally {
                            importing = false
                        }
                    }
                }
            ) {
                Text(if (importing) "Importing…" else "Import sample text (debug)")
            }
        }
    }
}
