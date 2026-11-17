package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.BuildConfig
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.components.BackButton
import io.github.smalltailqwq.shallowdrop.ui.components.DsIcon
import io.github.smalltailqwq.shallowdrop.ui.motion.pressClickable
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedCardColor
import io.github.smalltailqwq.shallowdrop.ui.theme.groupedPageColor

internal data class LicenseEntry(val name: String, val license: String, val files: List<String>)

private const val APACHE_LICENSE = "third-party/codex/LICENSE"
internal val thirdPartyLicenses = listOf(
    LicenseEntry("OpenAI Codex · 95946fa", "Apache-2.0", listOf("third-party/codex/NOTICE", APACHE_LICENSE)),
    LicenseEntry("KaTeX · 0.16.22", "MIT", listOf("markdown/katex/LICENSE")),
    LicenseEntry("KaTeX fonts · 0.16.22", "OFL-1.1", listOf("third-party/katex-fonts/LICENSE")),
    LicenseEntry("mhchem · 3.3.0", "MIT / Apache-2.0", listOf("third-party/mhchem/NOTICE", "markdown/katex/LICENSE", APACHE_LICENSE)),
    LicenseEntry("AndroidX", "Apache-2.0", listOf("third-party/androidx/NOTICE", APACHE_LICENSE)),
    LicenseEntry("Kotlin / kotlinx", "Apache-2.0", listOf("third-party/kotlin/NOTICE", APACHE_LICENSE)),
    LicenseEntry("OkHttp / Okio", "Apache-2.0", listOf("third-party/square/NOTICE", APACHE_LICENSE)),
    LicenseEntry("CommonMark Java · 0.32.0", "BSD-2-Clause", listOf("third-party/commonmark/NOTICE", "third-party/commonmark/LICENSE")),
    LicenseEntry("Autolink Java · 0.12.0", "MIT", listOf("third-party/autolink/NOTICE", "third-party/autolink/LICENSE")),
    LicenseEntry("AndroidX DataStore / Protobuf · 1.3.0", "BSD-3-Clause", listOf("third-party/protobuf/NOTICE", "third-party/protobuf/LICENSE")),
    LicenseEntry("Guava ListenableFuture · 1.0", "Apache-2.0", listOf("third-party/guava/NOTICE", APACHE_LICENSE)),
    LicenseEntry("JetBrains annotations / JSpecify", "Apache-2.0", listOf("third-party/annotations/NOTICE", APACHE_LICENSE)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val assets = LocalContext.current.assets
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.license), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = groupedPageColor()),
            )
        },
        containerColor = groupedPageColor(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.license_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                item(key = "project") {
                    ExpandableLicense("ShallowDrop · " + BuildConfig.VERSION_NAME, "MIT") {
                        resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
                    }
                }
                items(thirdPartyLicenses, key = { it.name }) { entry ->
                    ExpandableLicense(entry.name, entry.license) {
                        entry.files.joinToString("\n\n") { path -> assets.open(path).bufferedReader().use { it.readText() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableLicense(name: String, license: String, loadText: () -> String) {
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.license_expanded else R.string.license_collapsed)
    Surface(shape = RoundedCornerShape(16.dp), color = groupedCardColor()) {
        Column {
            Row(
                Modifier.fillMaxWidth().semantics { role = Role.Button; stateDescription = state }
                    .pressClickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DsIcon(
                    R.drawable.ic_chevron_down_outline_14,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp).size(14.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            if (expanded) {
                val text = remember(name, loadText) { loadText() }
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}
