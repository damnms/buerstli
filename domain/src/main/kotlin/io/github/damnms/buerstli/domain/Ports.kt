package io.github.damnms.buerstli.domain

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** What a scan saw. Drivers decide from this whether a device is "their" brush. */
data class Advertisement(val name: String?)

/**
 * Port to a connected brush. Implemented by the Bluetooth adapter in the app and by fakes
 * in tests. Operations suspend until the brush answered and throw IOException on failure.
 */
interface BrushLink {
    suspend fun read(characteristic: UUID): ByteArray
    suspend fun write(characteristic: UUID, data: ByteArray)
    suspend fun enableNotifications(characteristic: UUID)
}

/** Everything Bürstli knows about one brush model. One driver per protocol. */
interface ToothbrushDriver {
    val model: BrushModel
    val supportLevel: SupportLevel
    val capabilities: Capabilities

    fun recognizes(advertisement: Advertisement): Boolean

    /** Starts talking to a connected brush. */
    fun open(link: BrushLink): BrushSession
}

/** A live conversation with one brush. */
interface BrushSession {
    val state: StateFlow<BrushState>

    /** Handshake: subscribe, read initial state. */
    suspend fun start()

    /** Called by the adapter for every notification the brush sends. */
    fun onNotification(characteristic: UUID, value: ByteArray)

    suspend fun execute(command: BrushCommand): CommandResult
}

/** Finds the driver for a scanned device. Order matters: first match wins. */
class DriverRegistry(val drivers: List<ToothbrushDriver>) {
    fun driverFor(advertisement: Advertisement): ToothbrushDriver? =
        drivers.firstOrNull { it.recognizes(advertisement) }
}
