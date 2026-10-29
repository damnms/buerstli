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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlin.math.roundToInt

class Actions(
    val requestPermission: () -> Unit,
    val find: () -> Unit,
    val disconnect: () -> Unit,
    val edit: (mode: Int, kind: ParameterKind, value: Int) -> Unit,
    val save: (mode: Int) -> Unit,
    val discard: (mode: Int) -> Unit,
)

@Composable
fun MainScreen(
    connection: Connection,
    brushState: BrushState?,
    drafts: Drafts,
    saveStatus: Map<Int, SaveStatus>,
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
                ConnectionContent(connection, brushState, drafts, saveStatus, actions)
            }
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
    actions: Actions,
) {
    when (connection) {
        Connection.Idle -> FindButton(actions.find)
        Connection.Scanning -> Busy(stringResource(R.string.scanning), stringResource(R.string.scanning_hint))
        is Connection.Connecting -> Busy(
            stringResource(R.string.connecting, connection.name),
            stringResource(R.string.connecting_hint),
        )
        is Connection.Connected -> {
            BrushHeader(connection.name, connection.driver)
            if (brushState == null) {
                Busy(stringResource(R.string.waiting_for_status), null)
            } else {
                BrushView(connection.driver, brushState, drafts, saveStatus, editable = true, actions)
            }
            OutlinedButton(onClick = actions.disconnect) { Text(stringResource(R.string.disconnect)) }
        }
        is Connection.Disconnected -> {
            Message(
                stringResource(R.string.disconnected),
                stringResource(R.string.drafts_kept),
                stringResource(R.string.try_again),
                actions.find,
            )
            brushState?.let { BrushView(connection.driver, it, drafts, saveStatus, editable = false, actions) }
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
    editable: Boolean,
    actions: Actions,
) {
    InfoCard {
        state.batteryPercent?.let { battery ->
            Text(stringResource(R.string.battery), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { battery / 100f }, modifier = Modifier.weight(1f).height(10.dp))
                Text("$battery %", fontWeight = FontWeight.Bold)
            }
        }
        state.flightMode?.let {
            Text("${stringResource(R.string.flight_mode)}: ${stringResource(if (it) R.string.on else R.string.off)}")
        }
    }
    driver.capabilities.modes.forEach { spec ->
        ModeCard(
            spec = spec,
            active = spec.index == state.activeModeIndex,
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
            spec.parameters.forEach { p ->
                val value = shown[p.kind] ?: p.range.first
                Text("${stringResource(labelOf(p.kind))}: $value", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = value.toFloat(),
                    onValueChange = { actions.edit(spec.index, p.kind, it.roundToInt()) },
                    valueRange = p.range.first.toFloat()..p.range.last.toFloat(),
                    steps = (p.range.last - p.range.first - 1).coerceAtLeast(0),
                    enabled = editable && !saving,
                )
            }
            if (dirty && editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { actions.save(spec.index) }, enabled = !saving) { Text(stringResource(R.string.save)) }
                    TextButton(onClick = { actions.discard(spec.index) }, enabled = !saving) { Text(stringResource(R.string.discard)) }
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
            (status as? SaveStatus.Done)?.let { SaveResult(it.result) }
        }
    }
}

@Composable
private fun SaveResult(result: CommandResult) {
    val (text, isError) = when (result) {
        CommandResult.Confirmed -> stringResource(R.string.saved) to false
        CommandResult.NotApplied -> stringResource(R.string.not_applied) to true
        is CommandResult.Failed -> stringResource(R.string.save_failed) to true
        is CommandResult.Invalid, is CommandResult.UnknownMode -> stringResource(R.string.invalid) to true
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
