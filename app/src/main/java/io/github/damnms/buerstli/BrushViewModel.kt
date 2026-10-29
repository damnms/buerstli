package io.github.damnms.buerstli

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.damnms.buerstli.ble.BleConnector
import io.github.damnms.buerstli.ble.Connection
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.ParameterKind
import io.github.damnms.buerstli.domain.Switch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SaveStatus {
    data object Saving : SaveStatus
    data class Done(val result: CommandResult) : SaveStatus
}

class BrushViewModel(app: Application) : AndroidViewModel(app) {

    val diagnostics = Diagnostics()
    private val connector = BleConnector(app, Drivers.registry, viewModelScope, diagnostics)
    val connection: StateFlow<Connection> = connector.state

    @OptIn(ExperimentalCoroutinesApi::class)
    val brushState: StateFlow<BrushState?> = connection
        .flatMapLatest { c ->
            when (c) {
                is Connection.Connected -> c.session.state
                is Connection.Disconnected -> flowOf(c.lastState)
                else -> flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _drafts = MutableStateFlow(Drafts())
    val drafts: StateFlow<Drafts> = _drafts.asStateFlow()

    private val _saveStatus = MutableStateFlow<Map<Int, SaveStatus>>(emptyMap())
    val saveStatus: StateFlow<Map<Int, SaveStatus>> = _saveStatus.asStateFlow()

    /** Unsaved brushing duration in seconds, null = no edit. */
    private val _durationDraft = MutableStateFlow<Int?>(null)
    val durationDraft: StateFlow<Int?> = _durationDraft.asStateFlow()

    private val _durationSave = MutableStateFlow<SaveStatus?>(null)
    val durationSave: StateFlow<SaveStatus?> = _durationSave.asStateFlow()

    private val _switchSave = MutableStateFlow<Map<Switch, SaveStatus>>(emptyMap())
    val switchSave: StateFlow<Map<Switch, SaveStatus>> = _switchSave.asStateFlow()

    fun find() = connector.find()

    /** Switches apply at once: a toggle with a separate save button would be silly. */
    fun setSwitch(switch: Switch, on: Boolean) {
        val connected = connection.value as? Connection.Connected ?: return
        _switchSave.update { it + (switch to SaveStatus.Saving) }
        viewModelScope.launch {
            diagnostics.log("set $switch: $on")
            val result = connected.session.execute(BrushCommand.SetSwitch(switch, on))
            diagnostics.log("set $switch: $result")
            _switchSave.update { it + (switch to SaveStatus.Done(result)) }
        }
    }

    fun editDuration(seconds: Int) {
        val onBrush = brushState.value?.brushingDurationSeconds
        _durationDraft.value = if (seconds == onBrush) null else seconds
        _durationSave.value = null
    }

    fun discardDuration() {
        _durationDraft.value = null
        _durationSave.value = null
    }

    fun saveDuration() {
        val connected = connection.value as? Connection.Connected ?: return
        val seconds = _durationDraft.value ?: return
        _durationSave.value = SaveStatus.Saving
        viewModelScope.launch {
            diagnostics.log("save brushing time: $seconds s")
            val result = connected.session.execute(BrushCommand.ChangeBrushingDuration(seconds))
            diagnostics.log("save brushing time: $result")
            if (result == CommandResult.Confirmed) _durationDraft.value = null
            _durationSave.value = SaveStatus.Done(result)
        }
    }
    fun disconnect() = connector.disconnect()

    fun edit(mode: Int, kind: ParameterKind, value: Int) {
        val onBrush = brushState.value?.modes?.get(mode) ?: return
        _drafts.update { it.edit(mode, onBrush, kind, value) }
        _saveStatus.update { it - mode }
    }

    fun discard(mode: Int) {
        _drafts.update { it.clear(mode) }
        _saveStatus.update { it - mode }
    }

    fun save(mode: Int) {
        val connected = connection.value as? Connection.Connected ?: return
        val settings = _drafts.value.current(mode, null) ?: return
        _saveStatus.update { it + (mode to SaveStatus.Saving) }
        viewModelScope.launch {
            diagnostics.log("save mode ${mode + 1}: ${settings.values}")
            val result = connected.session.execute(BrushCommand.ChangeModeSettings(mode, settings))
            diagnostics.log("save mode ${mode + 1}: $result")
            if (result == CommandResult.Confirmed) _drafts.update { it.clear(mode) }
            _saveStatus.update { it + (mode to SaveStatus.Done(result)) }
        }
    }

    override fun onCleared() = connector.close()
}
