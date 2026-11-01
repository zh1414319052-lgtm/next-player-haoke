package dev.anilbeesetti.nextplayer.settings.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog

@Composable
internal fun CustomSkipDialog(
    preferences: PlayerPreferences,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var intro by rememberSaveable { mutableStateOf(preferences.customIntroSeconds.toString()) }
    var outro by rememberSaveable { mutableStateOf(preferences.customOutroSeconds.toString()) }
    val range = 0..PlayerPreferences.MAX_CUSTOM_SKIP_SECONDS
    val introValue = intro.toIntOrNull()
    val outroValue = outro.toIntOrNull()
    val introValid = introValue != null && introValue in range
    val outroValid = outroValue != null && outroValue in range
    NextDialog(
        title = { Text(stringResource(R.string.custom_skip_durations)) },
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.custom_skip_help))
                OutlinedTextField(
                    value = intro,
                    onValueChange = { intro = it },
                    label = { Text(stringResource(R.string.custom_intro_seconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !introValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = outro,
                    onValueChange = { outro = it },
                    label = { Text(stringResource(R.string.custom_outro_seconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !outroValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!introValid || !outroValid) Text(stringResource(R.string.custom_skip_error, range.last))
            }
        },
        confirmButton = {
            TextButton(enabled = introValid && outroValid, onClick = { onSave(checkNotNull(introValue), checkNotNull(outroValue)) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )
}
