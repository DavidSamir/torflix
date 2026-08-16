package com.torfilx.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.ConnectionTestResult
import com.torfilx.core.model.QualityPreference
import com.torfilx.core.ui.component.KeyboardLayout
import com.torfilx.core.ui.component.OnScreenKeyboard
import com.torfilx.core.ui.component.SearchField
import com.torfilx.core.ui.component.TvButton
import com.torfilx.core.ui.component.TvChip
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors

/** Which text field the on-screen keyboard is currently editing. */
private enum class EditingField { NONE, SERVER_URL, TOKEN, AUDIO_LANGUAGE, SUBTITLE_LANGUAGE }

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dimens = LocalTorfilxDimens.current
    var editing by remember { mutableStateOf(EditingField.NONE) }
    var buffer by remember { mutableStateOf("") }
    var keyboardLayout by remember { mutableStateOf(KeyboardLayout.LATIN) }

    fun startEditing(field: EditingField, initial: String) {
        editing = field
        buffer = initial
        keyboardLayout = if (field == EditingField.SERVER_URL) KeyboardLayout.NUMBERS else KeyboardLayout.LATIN
    }

    fun commit() {
        when (editing) {
            EditingField.SERVER_URL -> viewModel.setServerUrl(buffer)
            EditingField.TOKEN -> viewModel.setApiToken(buffer)
            EditingField.AUDIO_LANGUAGE -> viewModel.setAudioLanguage(buffer)
            EditingField.SUBTITLE_LANGUAGE -> viewModel.setSubtitleLanguage(buffer)
            EditingField.NONE -> Unit
        }
        editing = EditingField.NONE
        buffer = ""
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.Background)
            .padding(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .focusGroup()
                .focusRestorer(),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "title") {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TorfilxColors.TextPrimary,
                )
            }

            item(key = "server") {
                SettingsSection("Media server") {
                    SettingsValueRow(
                        label = "Server address",
                        value = state.settings.serverUrl.ifBlank { "Not set" },
                        onClick = { startEditing(EditingField.SERVER_URL, state.settings.serverUrl) },
                    )
                    SettingsValueRow(
                        label = "API token",
                        value = if (state.tokenSet) "Set" else "Not set",
                        onClick = { startEditing(EditingField.TOKEN, "") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            text = if (state.isTesting) "Testing…" else "Test connection",
                            onClick = viewModel::testConnection,
                            enabled = !state.isTesting,
                        )
                        TvChip(
                            text = if (state.demoMode) "Demo library: on" else "Demo library: off",
                            selected = state.demoMode,
                            onClick = { viewModel.setDemoMode(!state.demoMode) },
                        )
                    }
                    state.connectionTest?.let { result ->
                        Text(
                            text = when (result) {
                                is ConnectionTestResult.Success ->
                                    "Connected to ${result.info.name} ${result.info.version} " +
                                        "(API v${result.info.apiVersion})"

                                is ConnectionTestResult.Failure -> result.reason
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (result is ConnectionTestResult.Success) {
                                TorfilxColors.Success
                            } else {
                                TorfilxColors.Error
                            },
                        )
                    }
                }
            }

            item(key = "playback") {
                SettingsSection("Playback") {
                    SettingsToggleRow(
                        label = "Autoplay next episode",
                        checked = state.settings.autoplayNextEpisode,
                        onToggle = viewModel::setAutoplayNext,
                    )
                    SettingsToggleRow(
                        label = "Skip intros automatically",
                        checked = state.settings.skipIntroAutomatically,
                        onToggle = viewModel::setSkipIntroAutomatically,
                    )
                    SettingsToggleRow(
                        label = "Match display frame rate",
                        description = "Switches the TV to 24/50/60 Hz to stop film judder.",
                        checked = state.settings.frameRateMatching,
                        onToggle = viewModel::setFrameRateMatching,
                    )
                    SettingsToggleRow(
                        label = "Tunneled playback",
                        description = "Recommended for 4K/HDR. Turn off if your AV receiver glitches.",
                        checked = state.settings.tunneledPlayback,
                        onToggle = viewModel::setTunneledPlayback,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QualityPreference.entries.forEach { preference ->
                            TvChip(
                                text = preference.label(),
                                selected = state.settings.quality == preference,
                                onClick = { viewModel.setQuality(preference) },
                            )
                        }
                    }
                }
            }

            item(key = "languages") {
                SettingsSection("Language") {
                    SettingsValueRow(
                        label = "Preferred audio language",
                        value = state.settings.preferredAudioLanguage ?: "Original",
                        onClick = {
                            startEditing(
                                EditingField.AUDIO_LANGUAGE,
                                state.settings.preferredAudioLanguage.orEmpty(),
                            )
                        },
                    )
                    SettingsValueRow(
                        label = "Preferred subtitle language",
                        value = state.settings.preferredSubtitleLanguage ?: "None",
                        onClick = {
                            startEditing(
                                EditingField.SUBTITLE_LANGUAGE,
                                state.settings.preferredSubtitleLanguage.orEmpty(),
                            )
                        },
                    )
                    SettingsToggleRow(
                        label = "Subtitles on by default",
                        checked = state.settings.subtitlesEnabledByDefault,
                        onToggle = viewModel::setSubtitlesEnabled,
                    )
                }
            }

            item(key = "maintenance") {
                SettingsSection("Maintenance") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(text = "Clear cached library", onClick = viewModel::clearCache, primary = false)
                        TvButton(text = "Export logs", onClick = viewModel::exportLogs, primary = false)
                    }
                    state.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelLarge,
                            color = TorfilxColors.TextSecondary,
                        )
                    }
                }
            }
        }

        if (editing != EditingField.NONE) {
            Column(
                modifier = Modifier.width(400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = when (editing) {
                        EditingField.SERVER_URL -> "Server address (e.g. 192.168.1.10:8096)"
                        EditingField.TOKEN -> "API token"
                        EditingField.AUDIO_LANGUAGE -> "Audio language code (e.g. en, he)"
                        EditingField.SUBTITLE_LANGUAGE -> "Subtitle language code"
                        EditingField.NONE -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TorfilxColors.TextPrimary,
                )
                SearchField(value = buffer, placeholder = "Type using the keys below")
                OnScreenKeyboard(
                    onCharacter = { character ->
                        buffer += if (editing == EditingField.SERVER_URL ||
                            editing == EditingField.AUDIO_LANGUAGE ||
                            editing == EditingField.SUBTITLE_LANGUAGE
                        ) {
                            character.lowercaseChar()
                        } else {
                            character
                        }
                    },
                    onBackspace = { buffer = buffer.dropLast(1) },
                    onClear = { buffer = "" },
                    layout = keyboardLayout,
                    onLayoutChange = { keyboardLayout = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(text = "Save", onClick = { commit() })
                    TvButton(
                        text = "Cancel",
                        onClick = { editing = EditingField.NONE },
                        primary = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.TextPrimary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TorfilxColors.TextPrimary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = TorfilxColors.TextSecondary,
            )
        }
        TvButton(text = "Change", onClick = onClick, primary = false)
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TorfilxColors.TextPrimary,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = TorfilxColors.TextSecondary,
                )
            }
        }
        TvChip(
            text = if (checked) "On" else "Off",
            selected = checked,
            onClick = { onToggle(!checked) },
        )
    }
}

private fun QualityPreference.label(): String = when (this) {
    QualityPreference.AUTO -> "Quality: Auto"
    QualityPreference.DIRECT_ONLY -> "Quality: Direct only"
    QualityPreference.CAP_1080P -> "Quality: Max 1080p"
}
