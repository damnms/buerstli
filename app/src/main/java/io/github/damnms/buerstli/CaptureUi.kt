package io.github.damnms.buerstli

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.damnms.buerstli.capture.CaptureState

private const val NEW_BRUSH_ISSUE = "https://github.com/damnms/buerstli/issues/new?template=new-brush.yml"

/** Shown when Bürstli found a brush it knows but can't control yet. Kept simple on purpose. */
@Composable
fun CaptureContent(brand: String, state: CaptureState, actions: Actions) {
    when (state) {
        CaptureState.Idle -> Intro(brand, actions)
        is CaptureState.Running -> Running(state)
        is CaptureState.Done -> Done(brand, state, actions)
        is CaptureState.Failed -> Box {
            Text(stringResource(R.string.capture_failed, state.reason), style = MaterialTheme.typography.titleMedium)
            Button(onClick = actions.finishCapture) { Text(stringResource(R.string.capture_finish)) }
        }
    }
}

@Composable
private fun Intro(brand: String, actions: Actions) {
    Box {
        Text(
            stringResource(R.string.found_unsupported_title, brand),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.found_unsupported_text))
        Text(stringResource(R.string.capture_steps))
        Button(onClick = actions.startCapture, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.capture_start), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = actions.finishCapture) { Text(stringResource(R.string.capture_cancel)) }
    }
}

@Composable
private fun Running(state: CaptureState.Running) {
    Box {
        val text = when (state.phase) {
            CaptureState.Phase.ADVERTISEMENTS -> R.string.capture_phase_ads
            CaptureState.Phase.CONNECTING -> R.string.capture_phase_connecting
            CaptureState.Phase.READING -> R.string.capture_phase_reading
            CaptureState.Phase.NOTIFICATIONS -> R.string.capture_phase_notifications
        }
        Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
        if (state.secondsLeft > 0) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Text(stringResource(R.string.capture_seconds, state.secondsLeft))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        Text(stringResource(R.string.capture_pairing_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Done(brand: String, state: CaptureState.Done, actions: Actions) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text = state.report.render()
    var show by rememberSaveable { mutableStateOf(false) }
    val subject = stringResource(R.string.capture_share_subject, brand)

    Box {
        Text(stringResource(R.string.capture_done_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.capture_done_text))
        if (state.report.problems.isNotEmpty()) {
            Text(stringResource(R.string.capture_partial), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, null))
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.capture_send), style = MaterialTheme.typography.titleMedium) }
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NEW_BRUSH_ISSUE)))
                } catch (_: ActivityNotFoundException) {
                    // No browser: the report is in the clipboard anyway.
                }
            },
        ) { Text(stringResource(R.string.capture_github)) }
        Text(stringResource(R.string.capture_github_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { show = !show }) { Text(stringResource(R.string.capture_show)) }
        if (show) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        TextButton(onClick = actions.finishCapture) { Text(stringResource(R.string.capture_finish)) }
    }
}

@Composable
private fun Box(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}
