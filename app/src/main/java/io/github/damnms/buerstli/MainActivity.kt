package io.github.damnms.buerstli

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BuerstliTheme { App() } }
    }
}

val requiredPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
fun App(vm: BrushViewModel = viewModel()) {
    val context = LocalContext.current
    val connection by vm.connection.collectAsStateWithLifecycle()
    val brushState by vm.brushState.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val saveStatus by vm.saveStatus.collectAsStateWithLifecycle()
    val durationDraft by vm.durationDraft.collectAsStateWithLifecycle()
    val durationSave by vm.durationSave.collectAsStateWithLifecycle()
    val logLines by vm.diagnostics.lines.collectAsStateWithLifecycle()
    val switchSave by vm.switchSave.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()

    fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasPermissions()) }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        denied = !granted
        if (granted) vm.find()
    }

    MainScreen(
        connection = connection,
        brushState = brushState,
        drafts = drafts,
        saveStatus = saveStatus,
        duration = DurationUi(durationDraft, durationSave),
        switchSave = switchSave,
        capture = capture,
        nearby = nearby,
        logLines = logLines,
        permissionGranted = granted,
        permissionDenied = denied,
        actions = Actions(
            requestPermission = { launcher.launch(requiredPermissions) },
            find = vm::find,
            disconnect = vm::disconnect,
            edit = vm::edit,
            save = vm::save,
            discard = vm::discard,
            activateMode = vm::activateMode,
            editDuration = vm::editDuration,
            saveDuration = vm::saveDuration,
            discardDuration = vm::discardDuration,
            setSwitch = vm::setSwitch,
            startCapture = vm::startCapture,
            finishCapture = vm::finishCapture,
            finishRecording = vm::finishRecording,
            captureConnected = vm::captureConnected,
            openPicker = vm::openPicker,
            closePicker = vm::closePicker,
            pick = vm::pick,
        ),
    )
}

private val Teal = Color(0xFF0F766E)

@Composable
fun BuerstliTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF5EEAD4))
        else -> lightColorScheme(primary = Teal)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
