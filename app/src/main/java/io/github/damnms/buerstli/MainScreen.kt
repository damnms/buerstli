package io.github.damnms.buerstli

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch as SwitchControl
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.damnms.buerstli.ble.Connection
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.ModeSpec
import io.github.damnms.buerstli.domain.ParameterKind
import io.github.damnms.buerstli.domain.Access
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlin.math.roundToInt

class Actions(
    val requestPermission: () -> Unit,
    val find: () -> Unit,
    val disconnect: () -> Unit,
    val edit: (mode: Int, kind: ParameterKind, value: Int) -> Unit,
    val save: (mode: Int) -> Unit,
    val discard: (mode: Int) -> Unit,
    val activateMode: (mode: Int) -> Unit,
    val editDuration: (seconds: Int) -> Unit,
    val saveDuration: () -> Unit,
    val discardDuration: () -> Unit,
    val setSwitch: (Switch, Boolean) -> Unit,
    val startCapture: () -> Unit,
    val finishCapture: () -> Unit,
    val captureConnected: () -> Unit,
)

class DurationUi(val draft: Int?, val save: SaveStatus?)

@Composable
fun MainScreen(
    connection: Connection,
    brushState: BrushState?,
    drafts: Drafts,
    saveStatus: Map<Int, SaveStatus>,
    duration: DurationUi,
    switchSave: Map<Switch, SaveStatus>,
    capture: io.github.damnms.buerstli.capture.CaptureState,
    logLines: List<String>,
    permissionGranted: Boolean,
    permissionDenied: Boolean,
    actions: Actions,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            if (!permissionGranted) {
                PermissionCard(permissionDenied, actions.requestPermission)
            } else {
                ConnectionContent(connection, brushState, drafts, saveStatus, duration, switchSave, capture, actions)
            }
            if (logLines.isNotEmpty()) LogCard(logLines)
            Spacer(Modifier.height(8.dp))
            Footer()
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PermissionCard(denied: Boolean, onRequest: () -> Unit) {
    InfoCard {
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.permission_text
                else R.string.permission_text_legacy,
            ),
        )
        if (denied) Text(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRequest) { Text(stringResource(R.string.grant)) }
    }
}

@Composable
private fun ConnectionContent(
    connection: Connection,
    brushState: BrushState?,
    drafts: Drafts,
    saveStatus: Map<Int, SaveStatus>,
    duration: DurationUi,
    switchSave: Map<Switch, SaveStatus>,
    capture: io.github.damnms.buerstli.capture.CaptureState,
    actions: Actions,
) {
    // A running or finished report takes over the screen, whatever the connection does meanwhile.
    val captureBrand = when (capture) {
        is io.github.damnms.buerstli.capture.CaptureState.Running -> capture.brand
        is io.github.damnms.buerstli.capture.CaptureState.Done -> capture.report.brand
        is io.github.damnms.buerstli.capture.CaptureState.Failed -> capture.brand
        io.github.damnms.buerstli.capture.CaptureState.Idle -> null
    }
    if (captureBrand != null) {
        CaptureContent(captureBrand, capture, actions)
        return
    }
    when (connection) {
        is Connection.FoundUnsupported -> CaptureContent(connection.brand, capture, actions)
        Connection.Idle -> FindButton(actions.find)
        Connection.Scanning -> Busy(stringResource(R.string.scanning), stringResource(R.string.scanning_hint))
        is Connection.Connecting -> Busy(
            stringResource(R.string.connecting, connection.name),
            stringResource(R.string.connecting_hint) +
                if (connection.driver.mayNeedPairing) "\n\n" + stringResource(R.string.capture_pairing_hint) else "",
        )
        is Connection.Connected -> {
            BrushHeader(connection.name, connection.driver)
            if (connection.driver.supportLevel == SupportLevel.EXPERIMENTAL) ExperimentalNote(actions)
            if (brushState == null) {
                Busy(stringResource(R.string.waiting_for_status), null)
            } else {
                BrushView(connection.driver, brushState, drafts, saveStatus, duration, switchSave, editable = true, actions)
            }
            OutlinedButton(onClick = actions.disconnect) { Text(stringResource(R.string.disconnect)) }
            TextButton(onClick = actions.captureConnected) { Text(stringResource(R.string.capture_this_brush)) }
        }
        is Connection.Disconnected -> {
            Message(
                stringResource(R.string.disconnected),
                stringResource(R.string.drafts_kept),
                stringResource(R.string.try_again),
                actions.find,
            )
            brushState?.let { BrushView(connection.driver, it, drafts, saveStatus, duration, switchSave, editable = false, actions) }
        }
        Connection.NotFound -> Message(
            stringResource(R.string.not_found),
            stringResource(R.string.not_found_hint),
            stringResource(R.string.try_again),
            actions.find,
        )
        Connection.BluetoothOff -> Message(stringResource(R.string.bluetooth_off), null, stringResource(R.string.try_again), actions.find)
        Connection.NoBluetooth -> Message(stringResource(R.string.no_bluetooth), null, null, null)
        is Connection.Unsupported -> Message(
            stringResource(R.string.unsupported, connection.name), null, stringResource(R.string.try_again), actions.find,
        )
        is Connection.Failed -> Message(
            stringResource(R.string.error, connection.reason), null, stringResource(R.string.try_again), actions.find,
        )
    }
}

@Composable
private fun BrushHeader(name: String, driver: ToothbrushDriver) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${driver.model.brand} ${driver.model.name}", style = MaterialTheme.typography.titleMedium)
            Text(name, style = MaterialTheme.typography.bodySmall)
        }
        AssistChip(
            onClick = {},
            label = {
                Text(
                    stringResource(
                        if (driver.supportLevel == SupportLevel.VERIFIED) R.string.verified else R.string.experimental,
                    ),
                )
            },
        )
    }
}

@Composable
private fun BrushView(
    driver: ToothbrushDriver,
    state: BrushState,
    drafts: Drafts,
    saveStatus: Map<Int, SaveStatus>,
    duration: DurationUi,
    switchSave: Map<Switch, SaveStatus>,
    editable: Boolean,
    actions: Actions,
) {
    val switches = driver.capabilities.switches
    InfoCard {
        state.batteryPercent?.let { battery ->
            Text(stringResource(R.string.battery), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { battery / 100f }, modifier = Modifier.weight(1f).height(10.dp))
                Text("$battery %", fontWeight = FontWeight.Bold)
            }
        }
        if (Switch.MOTOR in switches) {
            state.switches[Switch.MOTOR]?.let { running ->
                MotorButton(running, switches[Switch.MOTOR] == Access.READ_WRITE && editable, switchSave[Switch.MOTOR], actions)
            }
        }
    }
    InfoReadings(state)
    val toggles = listOf(Switch.REMINDER_30S, Switch.HIGH_FREQUENCY, Switch.FLIGHT_MODE)
        .filter { it in switches && state.switches[it] != null }
    if (toggles.isNotEmpty()) {
        InfoCard {
            toggles.forEach { sw ->
                SwitchRow(
                    switch = sw,
                    on = state.switches.getValue(sw),
                    writable = switches[sw] == Access.READ_WRITE,
                    editable = editable,
                    status = switchSave[sw],
                    actions = actions,
                )
            }
        }
    }
    val durationSpec = driver.capabilities.brushingDuration
    val onBrushDuration = state.brushingDurationSeconds
    if (durationSpec != null && onBrushDuration != null) {
        DurationCard(durationSpec, onBrushDuration, duration, editable, actions)
    }
    driver.capabilities.modes.forEach { spec ->
        ModeCard(
            spec = spec,
            active = spec.index == state.activeModeIndex,
            unused = spec.requires != null && state.switches[spec.requires] != true,
            onBrush = state.modes[spec.index],
            drafts = drafts,
            status = saveStatus[spec.index],
            editable = editable,
            actions = actions,
        )
    }
}

@Composable
private fun ModeCard(
    spec: ModeSpec,
    active: Boolean,
    unused: Boolean,
    onBrush: io.github.damnms.buerstli.domain.ModeSettings?,
    drafts: Drafts,
    status: SaveStatus?,
    editable: Boolean,
    actions: Actions,
) {
    val shown = drafts.current(spec.index, onBrush) ?: return
    val dirty = drafts.isDirty(spec.index, onBrush)
    val saving = status is SaveStatus.Saving

    Card(
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (spec.highFrequency) stringResource(R.string.mode_hf, spec.index + 1)
                    else stringResource(R.string.mode_n, spec.index + 1),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (active) {
                    Text(stringResource(R.string.active), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (unused) Text(stringResource(R.string.mode_hf_off_hint), style = MaterialTheme.typography.bodySmall)
            spec.parameters.forEach { p ->
                val value = shown[p.kind] ?: p.range.first
                Text("${stringResource(labelOf(p.kind))}: $value", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = value.toFloat(),
                    onValueChange = { actions.edit(spec.index, p.kind, it.roundToInt()) },
                    valueRange = p.range.first.toFloat()..p.range.last.toFloat(),
                    steps = (p.range.last - p.range.first - 1).coerceAtLeast(0),
                    enabled = editable && !saving && !unused,
                )
            }
            if (!active && !dirty && editable && !unused) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { actions.activateMode(spec.index) }, enabled = !saving) {
                        Text(stringResource(R.string.mode_use))
                    }
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
            if (dirty && editable && !unused) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { actions.save(spec.index) }, enabled = !saving) { Text(stringResource(R.string.save_and_use)) }
                    TextButton(onClick = { actions.discard(spec.index) }, enabled = !saving) { Text(stringResource(R.string.discard)) }
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
            (status as? SaveStatus.Done)?.let { SaveResult(it.result) }
        }
    }
}

@Composable
private fun DurationCard(
    spec: io.github.damnms.buerstli.domain.DurationSpec,
    onBrush: Int,
    ui: DurationUi,
    editable: Boolean,
    actions: Actions,
) {
    val shown = ui.draft ?: onBrush
    val saving = ui.save is SaveStatus.Saving
    InfoCard {
        Text(stringResource(R.string.duration_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.duration_value, shown / 60, shown % 60),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = shown.toFloat(),
            onValueChange = {
                val snapped = spec.seconds.first +
                    ((it - spec.seconds.first) / spec.stepSeconds).roundToInt() * spec.stepSeconds
                actions.editDuration(snapped)
            },
            valueRange = spec.seconds.first.toFloat()..spec.seconds.last.toFloat(),
            steps = ((spec.seconds.last - spec.seconds.first) / spec.stepSeconds - 1).coerceAtLeast(0),
            enabled = editable && !saving,
        )
        Text(stringResource(R.string.duration_hint), style = MaterialTheme.typography.bodySmall)
        if (ui.draft != null && editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = actions.saveDuration, enabled = !saving) { Text(stringResource(R.string.save)) }
                TextButton(onClick = actions.discardDuration, enabled = !saving) { Text(stringResource(R.string.discard)) }
                if (saving) CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
        (ui.save as? SaveStatus.Done)?.let { SaveResult(it.result) }
    }
}

@Composable
private fun ExperimentalNote(actions: Actions) {
    InfoCard {
        Text(stringResource(R.string.experimental_note), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = actions.captureConnected) { Text(stringResource(R.string.capture_this_brush)) }
    }
}

/** Read-only values that some brushes report (Sonicare): shown only when present. */
@Composable
private fun InfoReadings(state: BrushState) {
    val rows = buildList {
        state.activity?.let {
            add(R.string.info_activity to stringResource(
                when (it) {
                    io.github.damnms.buerstli.domain.Activity.OFF -> R.string.activity_off
                    io.github.damnms.buerstli.domain.Activity.STANDBY -> R.string.activity_standby
                    io.github.damnms.buerstli.domain.Activity.BRUSHING -> R.string.activity_brushing
                    io.github.damnms.buerstli.domain.Activity.PAUSED -> R.string.activity_paused
                    io.github.damnms.buerstli.domain.Activity.CHARGING -> R.string.activity_charging
                },
            ))
        }
        state.modeName?.let {
            add(R.string.info_mode to when (it) {
                io.github.damnms.buerstli.domain.ModeName.CLEAN -> "Clean"
                io.github.damnms.buerstli.domain.ModeName.WHITE_PLUS -> "White+"
                io.github.damnms.buerstli.domain.ModeName.GUM_HEALTH -> "Gum Health"
                io.github.damnms.buerstli.domain.ModeName.DEEP_CLEAN_PLUS -> "Deep Clean+"
                io.github.damnms.buerstli.domain.ModeName.SENSITIVE -> "Sensitive"
                io.github.damnms.buerstli.domain.ModeName.TONGUE_CARE -> "Tongue Care"
            })
        }
        state.intensity?.let {
            add(R.string.info_intensity to stringResource(
                when (it) {
                    io.github.damnms.buerstli.domain.Intensity.LOW -> R.string.intensity_low
                    io.github.damnms.buerstli.domain.Intensity.MEDIUM -> R.string.intensity_medium
                    io.github.damnms.buerstli.domain.Intensity.HIGH -> R.string.intensity_high
                },
            ))
        }
        state.brushingSeconds?.let { s ->
            val target = state.brushingDurationSeconds
            val text = "%d:%02d".format(s / 60, s % 60) + (target?.let { " / %d:%02d".format(it / 60, it % 60) } ?: "")
            add(R.string.info_brushing_time to text)
        }
        state.brushHeadWearPercent?.let { add(R.string.info_head_wear to stringResource(R.string.info_head_wear_value, it)) }
    }
    if (rows.isEmpty()) return
    InfoCard {
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MotorButton(running: Boolean, enabled: Boolean, status: SaveStatus?, actions: Actions) {
    val busy = status is SaveStatus.Saving
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (running) {
            OutlinedButton(onClick = { actions.setSwitch(Switch.MOTOR, false) }, enabled = enabled && !busy) {
                Text(stringResource(R.string.motor_stop))
            }
        } else {
            Button(onClick = { actions.setSwitch(Switch.MOTOR, true) }, enabled = enabled && !busy) {
                Text(stringResource(R.string.motor_start))
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp))
    }
    Text(stringResource(R.string.motor_hint), style = MaterialTheme.typography.bodySmall)
    (status as? SaveStatus.Done)?.result?.takeIf { it != CommandResult.Confirmed }?.let { SaveResult(it) }
}

@Composable
private fun SwitchRow(
    switch: Switch,
    on: Boolean,
    writable: Boolean,
    editable: Boolean,
    status: SaveStatus?,
    actions: Actions,
) {
    val busy = status is SaveStatus.Saving
    val (title, hint) = when (switch) {
        Switch.REMINDER_30S -> R.string.switch_reminder to R.string.switch_reminder_hint
        Switch.HIGH_FREQUENCY -> R.string.switch_hf to R.string.switch_hf_hint
        Switch.FLIGHT_MODE -> R.string.flight_mode to R.string.switch_flight_hint
        Switch.MOTOR -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp))
        SwitchControl(
            checked = on,
            onCheckedChange = { actions.setSwitch(switch, it) },
            enabled = writable && editable && !busy,
        )
    }
    (status as? SaveStatus.Done)?.result?.takeIf { it != CommandResult.Confirmed }?.let { SaveResult(it) }
}

@Composable
private fun SaveResult(result: CommandResult) {
    val (text, isError) = when (result) {
        CommandResult.Confirmed -> stringResource(R.string.saved) to false
        CommandResult.NotApplied -> stringResource(R.string.not_applied) to true
        is CommandResult.Failed -> stringResource(R.string.save_failed) to true
        is CommandResult.Unavailable -> stringResource(R.string.unavailable_hf) to true
        is CommandResult.Invalid, is CommandResult.UnknownMode, CommandResult.Unsupported ->
            stringResource(R.string.invalid) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

private fun labelOf(kind: ParameterKind) = when (kind) {
    ParameterKind.STRENGTH -> R.string.strength
    ParameterKind.RANGE -> R.string.range
    ParameterKind.SPEED -> R.string.speed
}

@Composable
private fun FindButton(onFind: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onFind, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.find_brush), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.find_brush_sub),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Busy(title: String, hint: String?) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        hint?.let { Text(it) }
    }
}

@Composable
private fun Message(text: String, hint: String?, action: String?, onAction: (() -> Unit)?) {
    InfoCard {
        Text(text, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it) }
        if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open = !open }) { Text(stringResource(R.string.log_title)) }
            if (open) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) {
                    Text(stringResource(R.string.log_copy))
                }
            }
        }
        if (open) {
            Text(stringResource(R.string.log_hint), style = MaterialTheme.typography.bodySmall)
            Text(
                lines.takeLast(80).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Footer() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.footer_stats),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.footer_legal), style = MaterialTheme.typography.bodySmall)
    }
}
