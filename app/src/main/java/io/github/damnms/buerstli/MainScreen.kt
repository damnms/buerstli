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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    state: ConnState,
    permissionGranted: Boolean,
    permissionDenied: Boolean,
    onRequestPermission: () -> Unit,
    onFind: () -> Unit,
    onDisconnect: () -> Unit,
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
            when {
                !permissionGranted -> PermissionCard(permissionDenied, onRequestPermission)
                else -> StateContent(state, onFind, onDisconnect)
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
                else R.string.permission_text_legacy
            )
        )
        if (denied) {
            Text(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onRequest) { Text(stringResource(R.string.grant)) }
    }
}

@Composable
private fun StateContent(state: ConnState, onFind: () -> Unit, onDisconnect: () -> Unit) {
    when (state) {
        ConnState.Idle -> FindButton(onFind)
        ConnState.Scanning -> Busy(stringResource(R.string.scanning), stringResource(R.string.scanning_hint))
        is ConnState.Connecting -> Busy(
            stringResource(R.string.connecting, state.name),
            stringResource(R.string.connecting_hint),
        )
        is ConnState.Connected -> {
            Text(state.name, style = MaterialTheme.typography.titleMedium)
            if (state.status == null) {
                Busy(stringResource(R.string.waiting_for_status), null)
            } else {
                StatusView(state.status)
            }
            OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
            state.rawHex?.let { RawPacket(it) }
        }
        is ConnState.Disconnected -> {
            Message(stringResource(R.string.disconnected), null, stringResource(R.string.try_again), onFind)
            state.lastStatus?.let { StatusView(it) }
        }
        ConnState.NotFound -> Message(
            stringResource(R.string.not_found), stringResource(R.string.not_found_hint),
            stringResource(R.string.try_again), onFind,
        )
        ConnState.BluetoothOff -> Message(stringResource(R.string.bluetooth_off), null, stringResource(R.string.try_again), onFind)
        ConnState.NoBluetooth -> Message(stringResource(R.string.no_bluetooth), null, null, null)
        is ConnState.Unsupported -> Message(
            stringResource(R.string.unsupported, state.name), null, stringResource(R.string.try_again), onFind,
        )
        is ConnState.Failed -> Message(stringResource(R.string.error, state.reason), null, stringResource(R.string.try_again), onFind)
    }
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
            CircularProgressIndicator(Modifier.height(24.dp))
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
private fun StatusView(status: BrushProtocol.Status) {
    InfoCard {
        Text(stringResource(R.string.battery), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(
                progress = { status.batteryPercent / 100f },
                modifier = Modifier.weight(1f).height(10.dp),
            )
            Text("${status.batteryPercent} %", fontWeight = FontWeight.Bold)
        }
        Text(
            "${stringResource(R.string.flight_mode)}: " +
                stringResource(if (status.flightMode) R.string.on else R.string.off)
        )
    }

    status.modes.forEachIndexed { i, mode ->
        val active = i == status.activeModeIndex
        val title = if (i == BrushProtocol.HF_MODE_INDEX) stringResource(R.string.mode_hf)
        else stringResource(R.string.mode_n, i + 1)
        Card(
            colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else CardDefaults.cardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (active) Text(stringResource(R.string.active), fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "${stringResource(R.string.strength)} ${mode.strength} · " +
                        "${stringResource(R.string.range)} ${mode.range} · " +
                        "${stringResource(R.string.speed)} ${mode.speed}"
                )
            }
        }
    }
    Text(stringResource(R.string.read_only_note), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RawPacket(hex: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = !open }) { Text(stringResource(R.string.raw_title)) }
        if (open) Text(hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp))
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
        Text(stringResource(R.string.footer_stats), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.footer_legal), style = MaterialTheme.typography.bodySmall)
    }
}
