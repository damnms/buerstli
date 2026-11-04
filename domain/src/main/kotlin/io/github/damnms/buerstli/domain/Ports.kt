package io.github.damnms.buerstli.domain

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** What a scan saw. Drivers decide from this whether a device is "their" brush. */
data class Advertisement(
    val name: String?,
    val serviceUuids: List<UUID> = emptyList(),
    /** Manufacturer-specific data by Bluetooth company id. */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    /** Service data by service UUID (e.g. Xiaomi's MiBeacon under 0xFE95). */
    val serviceData: Map<UUID, ByteArray> = emptyMap(),
    /** Bluetooth address, "AA:BB:CC:DD:EE:FF". Some brushes need it to decrypt their broadcasts. */
    val address: String? = null,
)

/**
 * A key the user can enter for a brush that broadcasts encrypted (e.g. Xiaomi's bindkey).
 * Bürstli only reads with it; it never sends the key anywhere.
 */
data class KeySpec(
    val bytes: Int,
    /** Where the user learns how to get the key. */
    val helpUrl: String,
)

/**
 * Port to a connected brush. Implemented by the Bluetooth adapter in the app and by fakes
 * in tests. Operations suspend until the brush answered and throw IOException on failure.
 */
interface BrushLink {
    suspend fun read(characteristic: UUID): ByteArray
    suspend fun write(characteristic: UUID, data: ByteArray)
    /** Notifications or indications, whichever the characteristic offers. */
    suspend fun enableNotifications(characteristic: UUID)
}

/** The link of a passive session: there is no connection, so every operation fails. */
object NoLink : BrushLink {
    private fun none(): Nothing = throw java.io.IOException("passive driver: no connection")
    override suspend fun read(characteristic: UUID): ByteArray = none()
    override suspend fun write(characteristic: UUID, data: ByteArray) = none()
    override suspend fun enableNotifications(characteristic: UUID) = none()
}

/** Everything Bürstli knows about one brush model. One driver per protocol. */
interface ToothbrushDriver {
    val model: BrushModel
    val supportLevel: SupportLevel
    val capabilities: Capabilities

    fun recognizes(advertisement: Advertisement): Boolean

    /** Non-null if the user can enter a key to read this brush's encrypted broadcasts. */
    val key: KeySpec? get() = null

    /** Some brushes need Bluetooth pairing; Android then asks the user once. */
    val mayNeedPairing: Boolean get() = false

    /**
     * A passive driver never connects: everything comes from the brush's advertisements, which the
     * adapter feeds into [BrushSession.onAdvertisement]. Its session is opened with [NoLink].
     */
    val passive: Boolean get() = false

    /** Passive drivers: true if the session may connect for a moment ([BrushSession.poll]). */
    val connectsBriefly: Boolean get() = false

    /**
     * Experimental drivers that change something on the brush after all, by the maintainer's
     * exception to ADR-6 (e.g. Sonicare). The UI must not claim they only read.
     */
    val changesBrush: Boolean get() = false

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

    /** Called by the adapter for every advertisement of this brush (passive drivers). */
    fun onAdvertisement(advertisement: Advertisement) {}

    /** Drivers with a [ToothbrushDriver.key]: the key stored for this brush, null if none. */
    fun setKey(key: ByteArray?) {}

    /**
     * What the session saw that the raw log can't show (e.g. decrypted data), for the diagnostics
     * log. Returns each note once; the adapter asks after every advertisement (passive sessions).
     */
    fun takeNotes(): List<String> = emptyList()

    /**
     * Passive sessions only: true while the session needs a value the brush doesn't broadcast
     * (e.g. Oral-B's battery level). The adapter then stops listening for a moment, connects,
     * calls [poll] and disconnects again. How often it tries is up to the adapter.
     */
    val wantsPoll: Boolean get() = false

    /** Reads what [wantsPoll] asked for over a short connection. Throws IOException on failure. */
    suspend fun poll(link: BrushLink) {}

    suspend fun execute(command: BrushCommand): CommandResult
}

/** A brush we can recognize but have no driver for yet. */
class KnownBrush(val brand: String, val matches: (Advertisement) -> Boolean)

sealed interface Recognition {
    data class Supported(val driver: ToothbrushDriver) : Recognition
    data class Unsupported(val brand: String) : Recognition
    data object Unknown : Recognition
}

/** Finds the driver for a scanned device. Order matters: first match wins, drivers first. */
class DriverRegistry(
    val drivers: List<ToothbrushDriver>,
    val knownUnsupported: List<KnownBrush> = emptyList(),
) {
    fun driverFor(advertisement: Advertisement): ToothbrushDriver? =
        drivers.firstOrNull { it.recognizes(advertisement) }

    fun recognize(advertisement: Advertisement): Recognition {
        driverFor(advertisement)?.let { return Recognition.Supported(it) }
        knownUnsupported.firstOrNull { it.matches(advertisement) }?.let { return Recognition.Unsupported(it.brand) }
        return Recognition.Unknown
    }
}
