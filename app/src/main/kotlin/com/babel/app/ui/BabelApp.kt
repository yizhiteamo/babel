package com.babel.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.babel.core.model.LanguageTag

/**
 * Root of the app's own UI.
 *
 * It observes state and sends the user to system settings; it never touches
 * acquisition or overlay logic. Translation itself runs with the accessibility
 * service, so there is no start button here — enabling the service is the
 * start, which is also what the user can turn off from outside the app.
 */
@Composable
fun BabelApp(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    RefreshOnResume(viewModel::refreshCapabilities)

    var showLanguagePicker by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Babel", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = if (state.readyToTranslate) {
                    "Ready. Open any app and its text will be translated in place."
                } else {
                    "Two permissions are needed before translation can run."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PermissionCard(
                title = "Accessibility service",
                explanation = "Lets Babel read the text currently on screen. Translation runs " +
                    "for as long as this service is enabled.",
                granted = state.accessibilityGranted,
                onOpenSettings = { context.openAccessibilitySettings() },
            )

            PermissionCard(
                title = "Display over other apps",
                explanation = "Lets Babel draw the translation over the original text.",
                granted = state.overlayGranted,
                onOpenSettings = { context.openOverlaySettings() },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Translate into", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = state.targetLanguage?.value ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Following the system language by default. This is the " +
                            "translation target, not the language of this app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { showLanguagePicker = true }) {
                        Text("Change")
                    }
                }
            }
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            languages = viewModel.supportedLanguages(),
            onSelect = {
                viewModel.selectTargetLanguage(it)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    explanation: String,
    granted: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (granted) "Granted" else "Not granted",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                OutlinedButton(onClick = onOpenSettings) {
                    Text("Open settings")
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    languages: List<LanguageTag>,
    onSelect: (LanguageTag?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translate into") },
        text = {
            LazyColumn {
                item {
                    TextButton(onClick = { onSelect(null) }) {
                        Text("Follow system language")
                    }
                }
                items(languages) { tag ->
                    TextButton(onClick = { onSelect(tag) }) {
                        Text(tag.value)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Both permissions are granted outside the app, so nothing notifies us. */
@Composable
private fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun Context.openAccessibilitySettings() {
    startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.openOverlaySettings() {
    startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
