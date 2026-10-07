package com.babel.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.babel.app.R
import com.babel.app.capability.openAccessibilitySettings
import com.babel.app.tile.TileRequestResult
import com.babel.app.tile.canRequestTile
import com.babel.app.tile.requestAddBabelTile
import com.babel.core.model.ApiKey
import com.babel.core.model.LanguageTag
import com.babel.core.model.SourceLanguageMode
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
    var showSourcePicker by remember { mutableStateOf(false) }
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

            // Setup first while anything is missing: nothing below works yet.
            // Once both are granted they become two rows under "system".
            if (!state.readyToTranslate) {
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
            }

            // Sections of rows, so a later phase adds a row or a section
            // rather than one more full-width card with its own paragraph.
            TranslationSection(
                state = state,
                onTextChange = { on ->
                    if (on) viewModel.resumeTranslation() else viewModel.pauseTranslation()
                },
                onMangaChange = { on ->
                    if (on) viewModel.startMangaMode() else viewModel.stopMangaMode()
                },
                onTileAnswered = viewModel::onTileRequestAnswered,
                onUseLocal = viewModel::disableRemoteTranslation,
                onDownloadModel = viewModel::downloadRecognizerModel,
                onOpenAccessibilitySettings = { context.openAccessibilitySettings() },
                onOpenOverlaySettings = { context.openOverlaySettings() },
            )

            Section(title = stringResource(R.string.section_settings)) {
                ValueRow(
                    title = stringResource(R.string.source_language_title),
                    value = when (val mode = state.settings.sourceLanguageMode) {
                        is SourceLanguageMode.Manual -> mode.language.value
                        SourceLanguageMode.AutoDetect -> stringResource(R.string.source_language_auto_value)
                    },
                    onClick = { showSourcePicker = true },
                )
                ValueRow(
                    title = stringResource(R.string.language_section_title),
                    value = state.targetLanguage?.value ?: stringResource(R.string.language_none_selected),
                    onClick = { showLanguagePicker = true },
                )
                RemoteTranslationRow(
                    on = state.settings.usesRemoteTranslation,
                    onConfigure = { showRemoteSetup = true },
                    onDisable = viewModel::disableRemoteTranslation,
                )
            }

            if (state.readyToTranslate) {
                Section(title = stringResource(R.string.section_system)) {
                    ValueRow(
                        title = stringResource(R.string.permission_accessibility_title),
                        value = stringResource(R.string.permission_state_granted),
                        onClick = { context.openAccessibilitySettings() },
                    )
                    ValueRow(
                        title = stringResource(R.string.permission_overlay_title),
                        value = stringResource(R.string.permission_state_granted),
                        onClick = { context.openOverlaySettings() },
                    )
                    BackgroundSurvivalRow(
                        autoRestore = state.autoRestore,
                        grantCommand = "adb shell pm grant ${context.packageName} " +
                            "android.permission.WRITE_SECURE_SETTINGS",
                        onOpenBattery = { context.openBatterySettings() },
                        onOpenAppSettings = { context.openAppSettings() },
                    )
                }
            }

            // Last, and quiet. It exists because the app redistributes somebody
            // else's model and owes them attribution where a user can find it,
            // not because anyone opens it.
            TextButton(onClick = { showLicences = true }) {
                Text(stringResource(R.string.licences_action_open))
            }
        }
    }

    if (showSourcePicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.source_language_title),
            explanation = stringResource(R.string.source_language_explanation),
            defaultLabel = stringResource(R.string.source_language_auto),
            languages = viewModel.supportedLanguages(),
            onSelect = {
                viewModel.selectSourceLanguage(it)
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false },
        )
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.language_section_title),
            explanation = stringResource(R.string.language_explanation),
            defaultLabel = stringResource(R.string.language_follow_system),
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
            // Scrolls, because this is the tallest dialog in the app — chips, a
            // paragraph of hint, three fields, the probe row and the warning —
            // and `AlertDialog` does not scroll its text slot. Without it the
            // bottom is simply cut off at a large font size.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChipRow {
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
                    ChipRow {
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
                                label = { Text(presetLabel(option)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
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
 * A row of chips that wraps rather than losing the ones that do not fit.
 *
 * A [Row] compresses its children when they are too wide — which is how
 * `Ollama (local)` came to be set as three lines — and then simply clips what
 * is still over. On a 1440px phone that took `Custom` off the screen entirely,
 * and `Custom` is the option that keeps "point it at something of your own" a
 * first-class configuration (ADR 010, [ChatPreset.CUSTOM]). A layout cannot be
 * allowed to withdraw a documented choice.
 *
 * Wrapping rather than scrolling sideways: there are four chips, they are one
 * choice, and a chip that has to be discovered by swiping is only marginally
 * better than one that is clipped.
 *
 * [FlowRow] is still experimental in Compose Foundation 1.7 (it settles in 1.8),
 * so the opt-in lives here and nowhere else: one wrapper is the whole exposure,
 * and the call sites stay ordinary.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    // A plain lambda rather than `FlowRowScope.() -> Unit`: putting the scope in
    // the signature would make every caller touch the experimental API too,
    // which is the opposite of containing it. Nothing here needs the scope.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
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
 * A preset's name in the interface language.
 *
 * `ChatPreset` lives in the pure-Kotlin domain, which cannot reach resources,
 * so the two names that are words rather than brands are mapped here by id.
 * The rest are vendor names and are the same in every language.
 */
@Composable
private fun presetLabel(preset: ChatPreset): String = when (preset.id) {
    ChatPreset.CUSTOM.id -> stringResource(R.string.remote_preset_custom)
    ChatPreset.OLLAMA.id -> stringResource(R.string.remote_preset_ollama)
    else -> preset.label
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
 * A titled group of rows: the unit the home screen is built from.
 *
 * Every control used to be a card of its own with a paragraph and a full-width
 * button, which made the screen long and put the switches people use most at
 * the bottom. Rows keep each control to a line or two, and a later phase adds
 * a row or a section here rather than another card.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

/** A switch with a title and a line under it; [extra] holds whatever needs more. */
@Composable
private fun SwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    extra: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingColor,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        extra()
    }
}

/** A setting and its current value; the whole row opens it. */
@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = " ›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A warning or error under a row, with the one thing that fixes it. */
@Composable
private fun RowNotice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * One switch per path, then the quick-settings tile.
 *
 * There was a switch for everything above the two, mirroring the tile; with
 * each path on its own switch it said nothing they did not, so the home screen
 * kept only these. The tile stays — it is the switch for when Babel is not on
 * screen — and is introduced here because it was missed where it lived:
 * HyperOS shows tiles without names.
 */
@Composable
private fun TranslationSection(
    state: HomeUiState,
    onTextChange: (Boolean) -> Unit,
    onMangaChange: (Boolean) -> Unit,
    onTileAnswered: (Boolean) -> Unit,
    onUseLocal: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    val context = LocalContext.current
    Section(title = stringResource(R.string.section_translation)) {

        // Text: "pause" underneath, and the switch only while there is a
        // running pipeline to act on — resuming a stopped coordinator would
        // start it with no text source.
        SwitchRow(
            title = stringResource(R.string.runtime_section_title),
            supporting = stringResource(
                when {
                    state.translatingButUnseen -> R.string.runtime_state_unseen
                    state.translationPaused -> R.string.text_row_paused
                    state.canTogglePause -> state.settings.activeRouteRes()
                    else -> R.string.runtime_state_off
                },
            ),
            checked = state.canTogglePause && !state.translationPaused,
            enabled = state.canTogglePause,
            onCheckedChange = onTextChange,
            extra = {
                // Named here and nowhere else: somebody needs it at the moment
                // they open Babel wondering why nothing is being translated.
                state.providerFailure?.let { failure ->
                    RowNotice(
                        text = stringResource(probeResultOf(failure).messageRes()),
                        action = if (state.settings.usesRemoteTranslation) {
                            stringResource(R.string.runtime_failure_action_local)
                        } else {
                            null
                        },
                        onAction = onUseLocal,
                    )
                }
                if (state.translatingButUnseen) {
                    RowNotice(
                        text = stringResource(R.string.overlay_missing_explanation),
                        action = stringResource(R.string.overlay_missing_action),
                        onAction = onOpenOverlaySettings,
                    )
                }
            },
        )

        val capture = state.captureState
        val blocked = capture == CaptureState.UNAVAILABLE || capture == CaptureState.UNSUPPORTED
        SwitchRow(
            title = stringResource(R.string.capture_section_title),
            supporting = stringResource(
                when {
                    state.capturingButUnseen -> R.string.capture_state_unseen
                    capture == CaptureState.ACTIVE -> R.string.capture_state_active
                    capture == CaptureState.IDLE -> R.string.manga_row_idle
                    capture == CaptureState.UNAVAILABLE -> R.string.capture_state_unavailable
                    capture == CaptureState.UNSUPPORTED -> R.string.capture_state_unsupported
                    else -> R.string.capture_state_failed
                },
            ),
            checked = capture == CaptureState.ACTIVE,
            enabled = !blocked,
            onCheckedChange = onMangaChange,
            extra = {
                when {
                    // Two different things: one is fixed in system settings,
                    // the other cannot be fixed on this device at all.
                    capture == CaptureState.UNSUPPORTED ->
                        RowNotice(text = stringResource(R.string.capture_unsupported_reason))
                    capture == CaptureState.UNAVAILABLE -> RowNotice(
                        text = stringResource(R.string.capture_unavailable_reason),
                        action = stringResource(R.string.capture_action_open_settings),
                        onAction = onOpenAccessibilitySettings,
                    )
                    else -> {
                        // Installed says nothing worth a line; anything else
                        // needs the user.
                        if (state.recognizerModel != RecognizerModelState.Installed) {
                            RecognizerModelRow(
                                model = state.recognizerModel,
                                modelBytes = state.recognizerModelBytes,
                                metered = state.meteredConnection,
                                onDownload = onDownloadModel,
                            )
                        }
                        if (state.capturingButUnseen) {
                            RowNotice(
                                text = stringResource(R.string.overlay_missing_explanation),
                                action = stringResource(R.string.overlay_missing_action),
                                onAction = onOpenOverlaySettings,
                            )
                        }
                    }
                }
            },
        )

        // The tile only works with the service on, so it is offered only then.
        if (state.accessibilityGranted) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(text = stringResource(R.string.tile_row_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(
                        if (state.tileAdded) R.string.tile_hint_added else R.string.tile_hint_missing,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // No remove button once added: no app can remove its own tile.
                if (!state.tileAdded && canRequestTile) {
                    TextButton(
                        onClick = {
                            context.requestAddBabelTile { result ->
                                Toast.makeText(context, result.messageRes(), Toast.LENGTH_SHORT).show()
                                onTileAnswered(
                                    result == TileRequestResult.ADDED ||
                                        result == TileRequestResult.ALREADY_ADDED,
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.tile_card_action))
                    }
                }
            }
        }
    }
}

private fun TileRequestResult.messageRes(): Int = when (this) {
    TileRequestResult.ADDED -> R.string.tile_result_added
    TileRequestResult.ALREADY_ADDED -> R.string.tile_result_already
    TileRequestResult.DECLINED -> R.string.tile_result_declined
    TileRequestResult.UNSUPPORTED -> R.string.tile_result_unsupported
}

/**
 * The switch that lets recognised text leave the device.
 *
 * On-device translation tops out around half the distance of a hosted model
 * (`docs/milestones/v2.md`), so the offer is real — but it costs the app's
 * privacy posture, and the row says so in the same breath while it applies,
 * rather than in a screen nobody opens (ADR 010). Switching on opens the setup,
 * because a remote route with no endpoint would send nothing.
 */
@Composable
private fun RemoteTranslationRow(on: Boolean, onConfigure: () -> Unit, onDisable: () -> Unit) {
    SwitchRow(
        title = stringResource(R.string.remote_section_title),
        supporting = stringResource(if (on) R.string.remote_warning else R.string.remote_row_off),
        supportingColor = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        checked = on,
        onCheckedChange = { wanted -> if (wanted) onConfigure() else onDisable() },
        extra = {
            if (on) {
                TextButton(onClick = onConfigure) { Text(stringResource(R.string.remote_action_change)) }
            }
        },
    )
}

/**
 * Staying alive and coming back, folded into a row.
 *
 * Phrased as a symptom first: "a cleaner turns accessibility off" means
 * something to somebody who has just watched translation stop; "allow
 * background activity" does not. The detail — two buttons, because battery has
 * a standard intent and autostart has none, and a command for a computer — is
 * for the one time somebody needs it.
 */
@Composable
private fun BackgroundSurvivalRow(
    autoRestore: Boolean,
    grantCommand: String,
    onOpenBattery: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.background_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(
                        if (autoRestore) R.string.background_summary_restore_on else R.string.background_summary_restore_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (expanded) " ˄" else " ˅",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                text = stringResource(R.string.background_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!autoRestore) {
                Text(
                    text = stringResource(R.string.background_restore_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Selectable, because it is meant to be copied to a computer.
                SelectionContainer {
                    Text(
                        text = grantCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenBattery) {
                    Text(stringResource(R.string.background_action_battery))
                }
                OutlinedButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.background_action_app))
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
/**
 * @param title which language is being chosen — the two cards ask different
 *   questions of the same list.
 * @param defaultLabel what the first entry says, because "follow the system"
 *   and "detect it" are the two different ways of not choosing. Both pass null
 *   to [onSelect].
 */
private fun LanguagePickerDialog(
    title: String,
    explanation: String,
    defaultLabel: String,
    languages: List<LanguageTag>,
    onSelect: (LanguageTag?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Here rather than on the home screen: it answers the question
                // somebody has while choosing, and nobody else reads it.
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onSelect(null) }) {
                    Text(defaultLabel)
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


/**
 * The battery exemption, which on some ROMs is what keeps the service alive.
 *
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the standard route and
 * HyperOS honours it. It is not the whole story there — autostart is a separate
 * switch with no public intent — which is why the card sends people to the app's
 * own settings page for the rest rather than pretending one button covers it.
 */
private fun Context.openBatterySettings() {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(request) }.onFailure {
        // Some builds refuse or do not carry that activity. The app's own
        // settings page always exists and has the same switches behind it.
        openAppSettings()
    }
}

/** The app's own page in system settings, where every ROM puts its own switches. */
private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun Context.openOverlaySettings() {
    startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
