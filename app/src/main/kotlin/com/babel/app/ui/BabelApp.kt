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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.babel.app.R
import com.babel.core.model.ApiKey
import com.babel.core.model.LanguageTag
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.ChatPreset
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
import com.babel.domain.translation.ProbeResult
import com.babel.domain.translation.probeResultOf
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.RecognizerModelState

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

    RefreshOnResume(viewModel::refreshOnReturn)

    var showLanguagePicker by remember { mutableStateOf(false) }
    var showRemoteSetup by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Without this the page is simply cut off wherever the content
                // is taller than the screen, with no way to reach the rest.
                // Manga mode sits last, so on a landscape or short screen its
                // button was off the bottom edge and unreachable — reported
                // from a device, reproduced at 1920x1080.
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(
                    if (state.readyToTranslate) {
                        R.string.home_status_ready
                    } else {
                        R.string.home_status_setup_needed
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PermissionCard(
                title = stringResource(R.string.permission_accessibility_title),
                explanation = stringResource(R.string.permission_accessibility_explanation),
                granted = state.accessibilityGranted,
                onOpenSettings = { context.openAccessibilitySettings() },
            )

            PermissionCard(
                title = stringResource(R.string.permission_overlay_title),
                explanation = stringResource(R.string.permission_overlay_explanation),
                granted = state.overlayGranted,
                onOpenSettings = { context.openOverlaySettings() },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.language_section_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = state.targetLanguage?.value
                            ?: stringResource(R.string.language_none_selected),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.language_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { showLanguagePicker = true }) {
                        Text(stringResource(R.string.language_action_change))
                    }
                }
            }

            RuntimeCard(
                state = state,
                onPause = viewModel::pauseTranslation,
                onResume = viewModel::resumeTranslation,
                onUseLocal = viewModel::disableRemoteTranslation,
                onOpenOverlaySettings = { context.openOverlaySettings() },
            )

            CaptureCard(
                state = state.captureState,
                unseen = state.capturingButUnseen,
                model = state.recognizerModel,
                modelBytes = state.recognizerModelBytes,
                metered = state.meteredConnection,
                onStart = viewModel::startMangaMode,
                onStop = viewModel::stopMangaMode,
                onDownloadModel = viewModel::downloadRecognizerModel,
                onOpenAccessibilitySettings = { context.openAccessibilitySettings() },
                onOpenOverlaySettings = { context.openOverlaySettings() },
            )

            RemoteTranslationCard(
                settings = state.settings,
                onConfigure = { showRemoteSetup = true },
                onDisable = viewModel::disableRemoteTranslation,
            )

            // Last, and quiet. It exists because the app redistributes somebody
            // else's model and owes them attribution where a user can find it,
            // not because anyone opens it.
            TextButton(onClick = { showLicences = true }) {
                Text(stringResource(R.string.licences_action_open))
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

    if (showLicences) {
        LicencesDialog(onDismiss = { showLicences = false })
    }

    if (showRemoteSetup) {
        val probe by viewModel.probe.collectAsStateWithLifecycle()
        RemoteTranslationDialog(
            current = state.settings.remote,
            probe = probe,
            onCheck = viewModel::checkRemote,
            onProbeChanged = viewModel::clearProbe,
            onSave = {
                viewModel.enableRemoteTranslation(it)
                showRemoteSetup = false
            },
            onDismiss = {
                viewModel.clearProbe()
                showRemoteSetup = false
            },
        )
    }
}

/**
 * The switch that lets recognised text leave the device.
 *
 * Written to be refused as easily as accepted. On-device translation was
 * measured against a hosted model and tops out around half the distance
 * (`docs/milestones/v2.md`), so the offer is a real one — but what it costs is
 * the whole privacy posture of the app, and the card says so in the same breath
 * rather than in a settings screen nobody opens (ADR 010).
 */
@Composable
private fun RemoteTranslationCard(
    settings: BabelSettings,
    onConfigure: () -> Unit,
    onDisable: () -> Unit,
) {
    val on = settings.usesRemoteTranslation

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.remote_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (on) R.string.remote_state_on else R.string.remote_state_off,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(R.string.remote_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (on) {
                // Shown only while it applies. A standing warning about
                // something that is switched off teaches people to skip
                // warnings.
                Text(
                    text = stringResource(R.string.remote_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onConfigure) {
                    Text(
                        stringResource(
                            if (on) {
                                R.string.remote_action_change
                            } else {
                                R.string.remote_action_configure
                            },
                        ),
                    )
                }
                if (on) {
                    OutlinedButton(onClick = onDisable) {
                        Text(stringResource(R.string.remote_action_disable))
                    }
                }
            }
        }
    }
}

/**
 * Where the remote route is set up, including which service is behind it.
 *
 * The two services ask for different things, and the dialog shows only what the
 * chosen one needs: a chat endpoint wants an address and a model name, DeepL
 * wants a key and nothing else. Offering all four fields for both would make
 * three of them look required when they are not.
 */
@Composable
private fun RemoteTranslationDialog(
    current: RemoteProviderSettings,
    probe: ProbeState,
    onCheck: (RemoteProviderSettings) -> Unit,
    onProbeChanged: () -> Unit,
    onSave: (RemoteProviderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var service: RemoteService by remember { mutableStateOf(current.service) }
    var endpoint by remember { mutableStateOf(current.endpoint) }
    var model by remember { mutableStateOf(current.model) }
    var preset: ChatPreset by remember { mutableStateOf(ChatPreset.matching(current)) }
    // Deliberately not seeded from the stored key: showing a credential back in
    // a text field is how it ends up in a screenshot. Blank means "keep it",
    // and the two services keep their own, so switching cannot overwrite one
    // with the other.
    var chatKey by remember { mutableStateOf("") }
    var deepLKey by remember { mutableStateOf("") }

    // Exactly what pressing save would store, which is also what decides whether
    // save can be pressed. Asking the model rather than restating its rule is
    // what keeps the button and the gate that routes the text from disagreeing.
    val draft = RemoteProviderSettings(
        service = service,
        endpoint = endpoint.trim(),
        model = model.trim(),
        chatKey = chatKey.trim().takeIf { it.isNotEmpty() }?.let(::ApiKey) ?: current.chatKey,
        deepLKey = deepLKey.trim().takeIf { it.isNotEmpty() }?.let(::ApiKey) ?: current.deepLKey,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteService.entries.forEach { option ->
                        FilterChip(
                            selected = service == option,
                            onClick = {
                                service = option
                                onProbeChanged()
                            },
                            label = { Text(stringResource(option.labelRes())) },
                        )
                    }
                }
                Text(
                    text = stringResource(
                        when (service) {
                            RemoteService.CHAT -> R.string.remote_dialog_hint
                            RemoteService.DEEPL -> R.string.remote_dialog_hint_deepl
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (service == RemoteService.CHAT) {
                    // The address is the part users get wrong: a service's own
                    // documentation gives a base URL, and a base URL alone
                    // answers 404. Picking the service fills in the full path.
                    // It stays editable, and `Custom` stays in the list, so a
                    // server of one's own is still a first-class configuration
                    // (ADR 010).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatPreset.ALL.forEach { option ->
                            FilterChip(
                                selected = preset == option,
                                onClick = {
                                    preset = option
                                    if (option != ChatPreset.CUSTOM) {
                                        endpoint = option.endpoint
                                        model = option.defaultModel
                                    }
                                    onProbeChanged()
                                },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = {
                            endpoint = it
                            preset = ChatPreset.matching(draft.copy(endpoint = it.trim()))
                            onProbeChanged()
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.remote_field_endpoint)) },
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            onProbeChanged()
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.remote_field_model)) },
                    )
                }
                OutlinedTextField(
                    value = if (service == RemoteService.CHAT) chatKey else deepLKey,
                    onValueChange = {
                        if (service == RemoteService.CHAT) chatKey = it else deepLKey = it
                        onProbeChanged()
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.remote_field_key)) },
                    supportingText = keyHint(service, current)?.let { hint ->
                        { Text(stringResource(hint)) }
                    },
                )
                ProbeRow(
                    state = probe,
                    enabled = draft.isConfigured,
                    onCheck = { onCheck(draft) },
                )
                Text(
                    text = stringResource(R.string.remote_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                // Turning it on with nowhere to send to would fail every request
                // and read as a bug rather than as a blank field. What counts as
                // "somewhere" differs per service, so the settings model is
                // asked instead of the answer being written out twice.
                enabled = draft.isConfigured,
            ) {
                Text(stringResource(R.string.remote_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_dialog_cancel))
            }
        },
    )
}

/**
 * The check, and what it found.
 *
 * Advisory rather than a gate: saving stays possible whatever this says,
 * because somebody configuring an endpoint on a train should not be stopped by
 * having no network. What it buys is that a wrong key stops being invisible —
 * without it the only symptom is that translations never appear, which looks
 * the same as every other failure.
 */
@Composable
private fun ProbeRow(state: ProbeState, enabled: Boolean, onCheck: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onCheck, enabled = enabled && state != ProbeState.Checking) {
            Text(stringResource(R.string.remote_action_check))
        }
        val message = when (state) {
            ProbeState.Idle -> null
            ProbeState.Checking -> R.string.probe_checking
            is ProbeState.Done -> state.result.messageRes()
        }
        message?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = if (state is ProbeState.Done && state.result is ProbeResult.Ok) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/**
 * Each outcome says what to change, not that something went wrong.
 *
 * "It failed" sends a user back to the same four fields with no idea which one
 * is at fault; a status tells them which, and the statuses are the only thing
 * that distinguishes a wrong key from a wrong address from a model that does
 * not exist on that account.
 */
private fun ProbeResult.messageRes(): Int = when (this) {
    ProbeResult.Ok -> R.string.probe_ok
    ProbeResult.KeyRejected -> R.string.probe_key_rejected
    ProbeResult.EndpointNotFound -> R.string.probe_endpoint_not_found
    ProbeResult.RequestRejected -> R.string.probe_request_rejected
    ProbeResult.QuotaExhausted -> R.string.probe_quota
    is ProbeResult.Unreachable -> R.string.probe_unreachable
    is ProbeResult.Unexpected -> R.string.probe_unexpected
    ProbeResult.NotConfigured -> R.string.probe_not_configured
    ProbeResult.LanguageUnsupported -> R.string.probe_language_unsupported
}

private fun RemoteService.labelRes(): Int = when (this) {
    RemoteService.CHAT -> R.string.remote_service_chat
    RemoteService.DEEPL -> R.string.remote_service_deepl
}

/**
 * What to say under the key field, which is three different things.
 *
 * A stored key is kept when the field is left blank; a chat endpoint may not
 * want a key at all; DeepL wants only that, so it says nothing and lets the
 * label speak.
 */
private fun keyHint(service: RemoteService, current: RemoteProviderSettings): Int? = when {
    current.apiKey.isPresent -> R.string.remote_field_key_kept
    service == RemoteService.CHAT -> R.string.remote_field_key_optional
    else -> null
}

/**
 * Says whether the comic recogniser is on the device, and offers to fetch it.
 *
 * Deliberately not phrased as a fault. Manga mode works without this: the
 * balloon detector ships with the app, so balloons are found correctly and
 * interface text is left alone either way. What the download buys is reading
 * the lettering accurately instead of approximately (ADR 011) — a better tier,
 * not a repair.
 */
@Composable
private fun RecognizerModelRow(
    model: RecognizerModelState,
    modelBytes: Long,
    metered: Boolean,
    onDownload: () -> Unit,
) {
    when (model) {
        RecognizerModelState.Installed -> Text(
            text = stringResource(R.string.model_state_installed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is RecognizerModelState.Running -> {
            val percent = if (model.total > 0) {
                (model.bytes * 100 / model.total).toInt()
            } else {
                0
            }
            Text(
                text = stringResource(R.string.model_state_running, percent),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        RecognizerModelState.Absent -> {
            Text(
                text = stringResource(R.string.model_state_absent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (metered) {
                // Said before the button, not after: 117MB on a metered
                // connection is the kind of thing people want to know first.
                Text(
                    text = stringResource(R.string.model_metered_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.model_action_download, modelBytes.asMegabytes()))
            }
        }

        is RecognizerModelState.Failed -> {
            Text(
                text = stringResource(
                    when (model.cause) {
                        RecognizerModelState.Failed.Cause.NETWORK -> R.string.model_failed_network
                        RecognizerModelState.Failed.Cause.CORRUPT -> R.string.model_failed_corrupt
                        RecognizerModelState.Failed.Cause.UNEXPECTED ->
                            R.string.model_failed_unexpected
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            // Offered even after a checksum failure. Retrying will not fix that
            // one, and the message says so — but a button that vanishes leaves
            // someone stuck with no way to try after fixing their side.
            OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.model_action_retry))
            }
        }
    }
}

/**
 * The route a translation would take right now.
 *
 * Derived rather than stored: `usesRemoteTranslation` is the same question the
 * privacy gate asks, and answering it twice is how a screen comes to claim one
 * thing while the pipeline does another.
 */
private fun BabelSettings.activeRouteRes(): Int = when {
    !usesRemoteTranslation -> R.string.route_active_local
    remote.service == RemoteService.DEEPL -> R.string.route_active_deepl
    else -> R.string.route_active_chat
}

/** Megabytes, because nobody reads bytes. */
private fun Long.asMegabytes(): Int = (this / 1_000_000).toInt()

/**
 * Shows the attribution the bundled model obliges Babel to carry.
 *
 * `NOTICE.txt` and the licence text travel in the assets of `:platform:capture`,
 * beside the model they describe, and a library module's assets merge into the
 * package — so reading them from here needs no copy and cannot drift from the
 * file that ships.
 */
@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notice = remember {
        runCatching {
            context.assets.open("licenses/NOTICE.txt").use { it.readBytes().decodeToString() }
        }.getOrElse {
            // Not fatal, and not silent either: the obligation is to make the
            // attribution reachable, so a failure to read it should say so
            // rather than show an empty box.
            context.getString(R.string.licences_unavailable)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.licences_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = notice, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.licences_action_close))
            }
        },
    )
}

/**
 * Pausing translation.
 *
 * The coordinator has had `pause`/`start` since V1; nothing in the interface
 * ever called them, so the only way to stop translating was to switch the
 * accessibility service off in system settings.
 *
 * "Pause" rather than "off" because that is what happens: translations already
 * drawn stay until the content changes. A label promising a clear screen would
 * be worse than no switch.
 */
@Composable
private fun RuntimeCard(
    state: HomeUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onUseLocal: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.runtime_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        when {
                            // Asked before the others: a paused or running
                            // coordinator that cannot draw is neither, from
                            // where the user is sitting.
                            state.translatingButUnseen -> R.string.runtime_state_unseen
                            state.translationPaused -> R.string.runtime_state_paused
                            state.canTogglePause -> R.string.runtime_state_running
                            else -> R.string.runtime_state_off
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(R.string.runtime_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Which engine is actually answering. Two services can now both be
            // configured, and `service` alone decides which one runs — without
            // saying so, the only way to find out is to read a translation and
            // guess at its style.
            Text(
                text = stringResource(state.settings.activeRouteRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A failure that will not fix itself, named. Here and nowhere else:
            // not over other apps, not a notification, not a toast — somebody
            // only needs this at the moment they open Babel wondering why
            // nothing is being translated. One success takes it away again.
            state.providerFailure?.let { failure ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(probeResultOf(failure).messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    // Offered rather than taken: falling back on its own would
                    // change the translation quality silently, which is the
                    // problem this line exists to end. It is also not certain
                    // to work — the on-device engine may have no model for this
                    // language, which is a failure of its own.
                    if (state.settings.usesRemoteTranslation) {
                        TextButton(onClick = onUseLocal) {
                            Text(stringResource(R.string.runtime_failure_action_local))
                        }
                    }
                }
            }
            if (state.translatingButUnseen) {
                // Says that the work is happening and being discarded, rather
                // than the vaguer "not ready" — which reads as "not running"
                // and sends people looking in the wrong place.
                Text(
                    text = stringResource(R.string.overlay_missing_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onOpenOverlaySettings) {
                    Text(stringResource(R.string.overlay_missing_action))
                }
            }
            // No button unless there is a running pipeline to act on: resuming
            // a stopped coordinator would start it with no text source.
            if (state.canTogglePause) {
                OutlinedButton(onClick = if (state.translationPaused) onResume else onPause) {
                    Text(
                        stringResource(
                            if (state.translationPaused) {
                                R.string.runtime_action_resume
                            } else {
                                R.string.runtime_action_pause
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Manga mode. Separate from the permission cards because it is a mode the user
 * turns on here, not a permission granted in system settings.
 *
 * It used to be a session with a consent dialog in front of it. Taking pictures
 * through the already-authorised accessibility service removed the dialog, the
 * recording indicator and the foreground service along with it (ADR 009), which
 * is why this is now just a button.
 */
@Composable
private fun CaptureCard(
    state: CaptureState,
    unseen: Boolean,
    model: RecognizerModelState,
    modelBytes: Long,
    metered: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.capture_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        when {
                            // Same reason as the text card: reading a page and
                            // translating it is not "on" if none of it lands.
                            unseen -> R.string.capture_state_unseen
                            state == CaptureState.IDLE -> R.string.capture_state_idle
                            state == CaptureState.ACTIVE -> R.string.capture_state_active
                            state == CaptureState.UNAVAILABLE ->
                                R.string.capture_state_unavailable
                            state == CaptureState.UNSUPPORTED ->
                                R.string.capture_state_unsupported
                            else -> R.string.capture_state_failed
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(R.string.capture_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val blocked = state == CaptureState.UNAVAILABLE || state == CaptureState.UNSUPPORTED
            if (!blocked) {
                RecognizerModelRow(
                    model = model,
                    modelBytes = modelBytes,
                    metered = metered,
                    onDownload = onDownloadModel,
                )
            }
            if (blocked) {
                // Named separately: one of these is fixed by a visit to system
                // settings and the other cannot be fixed at all, and a single
                // sentence covering both put a version requirement first on
                // every device that already satisfies it.
                Text(
                    text = stringResource(
                        if (state == CaptureState.UNSUPPORTED) {
                            R.string.capture_unsupported_reason
                        } else {
                            R.string.capture_unavailable_reason
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (state == CaptureState.UNAVAILABLE) {
                    OutlinedButton(onClick = onOpenAccessibilitySettings) {
                        Text(stringResource(R.string.capture_action_open_settings))
                    }
                }
            } else if (state == CaptureState.ACTIVE) {
                if (unseen) {
                    Text(
                        text = stringResource(R.string.overlay_missing_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onOpenOverlaySettings) {
                        Text(stringResource(R.string.overlay_missing_action))
                    }
                }
                OutlinedButton(onClick = onStop) {
                    Text(stringResource(R.string.capture_action_stop))
                }
            } else {
                OutlinedButton(onClick = onStart) {
                    Text(stringResource(R.string.capture_action_start))
                }
            }
        }
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
                    text = stringResource(
                        if (granted) {
                            R.string.permission_state_granted
                        } else {
                            R.string.permission_state_missing
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Shown even once granted. Turning the service back off is only
            // possible in system settings, and without this the app offered no
            // route there at all — a switch with no off.
            OutlinedButton(onClick = onOpenSettings) {
                Text(
                    stringResource(
                        if (granted) {
                            R.string.permission_action_manage
                        } else {
                            R.string.permission_action_open_settings
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Choosing what text is translated into.
 *
 * A plain [Column] that scrolls, rather than a `LazyColumn`. The lazy one did
 * not scroll by touch inside an `AlertDialog`: dragging moved the list about
 * eight entries and then stopped, while keyboard focus traversal walked the
 * whole way down. On a phone that put everything after `id` — **`ja`, `ko`,
 * `ru` and `zh` among them** — out of reach, and `zh` only worked at all
 * because it is the system default.
 *
 * Fifty-odd short rows need no virtualisation, and the licences dialog in this
 * same file already scrolls this way, so this is the pattern that is known to
 * work here rather than a guess at what the lazy one wanted.
 */
@Composable
private fun LanguagePickerDialog(
    languages: List<LanguageTag>,
    onSelect: (LanguageTag?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_section_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onSelect(null) }) {
                    Text(stringResource(R.string.language_follow_system))
                }
                languages.forEach { tag ->
                    TextButton(onClick = { onSelect(tag) }) {
                        Text(tag.value)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.language_action_cancel))
            }
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
