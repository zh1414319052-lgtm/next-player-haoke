package dev.anilbeesetti.nextplayer.settings.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ClickablePreferenceItem
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LibrariesScreen(viewModel: LibrariesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LibrariesScreenContent(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibrariesScreenContent(
    state: LibrariesUiState,
    onAction: (LibrariesAction) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showLicense by rememberSaveable { mutableStateOf(false) }

    if (showLicense) {
        val license by produceState<String?>(initialValue = null, context) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.resources.openRawResource(R.raw.next_player_gpl).bufferedReader().use { it.readText() }
                }.getOrElse { context.getString(R.string.open_source_gpl_failed) }
            }
        }
        NextDialog(
            title = { Text(stringResource(R.string.open_source_gpl)) },
            content = {
                Text(
                    text = license ?: stringResource(R.string.open_source_gpl_loading),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) {
                    Text(stringResource(R.string.open_source_gpl_close))
                }
            },
            onDismissRequest = { showLicense = false },
        )
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.open_source_licenses),
                navigationIcon = {
                    FilledTonalIconButton(onClick = { onAction(LibrariesAction.NavigateUp) }) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "next_player_attribution") {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.open_source_attribution),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.open_source_modifications),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.open_source_warranty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "next_player_gpl") {
                ClickablePreferenceItem(
                    title = stringResource(R.string.open_source_gpl),
                    onClick = { showLicense = true },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }
            item(key = "third_party_libraries") {
                ListSectionTitle(text = stringResource(R.string.libraries))
            }
            itemsIndexed(state.libraries, key = { _, library -> library.uniqueId }) { index, library ->
                NextSegmentedListItem(
                    content = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = library.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            library.artifactVersion?.let {
                                Text(text = it)
                            }
                        }
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = library.developers.takeIf { it.isNotEmpty() }
                                    ?.mapNotNull { it.name }
                                    ?.joinToString(", ")
                                    ?: library.organization?.name ?: "",
                            )
                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                library.licenses.forEach {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(text = it.name, modifier = Modifier.padding(horizontal = 2.dp))
                                    }
                                }
                            }
                        }
                    },
                    isFirstItem = index == 0,
                    isLastItem = index == state.libraries.lastIndex,
                    onClick = {
                        library.website?.takeIf { it.isNotBlank() }?.let {
                            uriHandler.openUriOrShowToast(uri = it, context = context)
                        }
                    },
                )
            }
        }
    }
}
