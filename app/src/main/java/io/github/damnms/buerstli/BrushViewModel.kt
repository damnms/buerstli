package io.github.damnms.buerstli

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class BrushViewModel(app: Application) : AndroidViewModel(app) {
    private val connection = BrushConnection(app)
    val state: StateFlow<ConnState> = connection.state

    fun find() = connection.find()
    fun disconnect() = connection.disconnect()

    override fun onCleared() = connection.close()
}
