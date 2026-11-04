package io.github.damnms.buerstli.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.damnms.buerstli.AddressMask
import io.github.damnms.buerstli.Diagnostics
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.hex
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.DriverRegistry
import io.github.damnms.buerstli.domain.NoLink
import io.github.damnms.buerstli.domain.Recognition
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

sealed interface Connection {
    data object Idle : Connection
    data object NoBluetooth : Connection
    data object BluetoothOff : Connection
    data object Scanning : Connection
    data object NotFound : Connection
    data class Connecting(val name: String, val driver: ToothbrushDriver) : Connection
    data class Connected(
        val name: String,
        val driver: ToothbrushDriver,
        val session: BrushSession,
        val device: BluetoothDevice,
    ) : Connection
    data class Unsupported(val name: String) : Connection
    data class Disconnected(val name: String, val driver: ToothbrushDriver, val lastState: BrushState?) : Connection
    data class Failed(val reason: String) : Connection

    /** A brush we know but can't talk to yet; the user can record a report for it. */
    data class FoundUnsupported(val name: String?, val brand: String, val device: BluetoothDevice) : Connection
}

/**
 * Adapter: scanning and connecting with Android's Bluetooth stack. Knows nothing about
 * any brand; drivers from the [DriverRegistry] decide which device is a brush and how to
 * talk to it. Callers must hold the Bluetooth permissions before calling [find].
 */
@SuppressLint("MissingPermission")
class BleConnector(
    context: Context,
    private val registry: DriverRegistry,
    private val scope: CoroutineScope,
    private val diag: Diagnostics,
) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow<Connection>(Connection.Idle)
    val state: StateFlow<Connection> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var link: AndroidBrushLink? = null
    private var session: BrushSession? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Job? = null
    private var unsupportedGrace: Job? = null

    fun find() {
        val a = adapter ?: run { _state.value = Connection.NoBluetooth; return }
        if (!a.isEnabled) { _state.value = Connection.BluetoothOff; return }
        val scanner = a.bluetoothLeScanner ?: run { _state.value = Connection.BluetoothOff; return }

        stopScan()
        closeGatt()
        diag.mark("find: scanning")
        _state.value = Connection.Scanning

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val ad = result.toAdvertisement()
                val name = ad.name
                when (val recognition = registry.recognize(ad)) {
                    is Recognition.Supported -> scope.launch {
                        if (scanCallback == null) return@launch // someone else won the race
                        val driver = recognition.driver
                        val shown = name?.let { AddressMask(result.device.address).text(it) }
                        diag.log("found $shown (${result.rssi} dBm) -> ${driver.model.brand} ${driver.model.name}")
                        stopScan()
                        if (driver.passive) listen(result.device, name ?: result.device.address, driver, ad)
                        else connect(result.device, name ?: result.device.address, driver)
                    }
                    is Recognition.Unsupported -> scope.launch {
                        if (scanCallback == null || unsupportedGrace != null) return@launch
                        val shown = name?.let { AddressMask(result.device.address).text(it) }
                        diag.log("found $shown (${result.rssi} dBm): ${recognition.brand}, no driver yet")
                        // Give a supported brush nearby a moment to show up before offering the report.
                        unsupportedGrace = scope.launch {
                            delay(UNSUPPORTED_GRACE_MS)
                            if (scanCallback != null) {
                                stopScan()
                                _state.value = Connection.FoundUnsupported(name, recognition.brand, result.device)
                            }
                        }
                    }
                    Recognition.Unknown -> Unit
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    stopScan()
                    diag.log("scan FAILED: error $errorCode")
                    _state.value = Connection.Failed("scan error $errorCode")
                }
            }
        }
        scanCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scanTimeout = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (scanCallback != null) {
                diag.log("nothing found, giving up")
                stopScan()
                _state.value = Connection.NotFound
            }
        }
    }

    fun disconnect() {
        diag.log("disconnect requested")
        stopScan()
        if (listening != null) {
            stopListening(sleeping = true)
            return
        }
        gatt?.disconnect() ?: run { _state.value = Connection.Idle }
    }

    /** A brush the owner picked by hand: offer the report for it, like for a known brush. */
    fun offerReport(device: BluetoothDevice, name: String) {
        stopScan()
        stopListening(sleeping = false)
        closeGatt()
        diag.log("picked by hand: ${AddressMask(device.address).text(name)}")
        _state.value = Connection.FoundUnsupported(name, name, device)
    }

    /** Back to the start screen, e.g. after a report was recorded. */
    fun reset() {
        stopScan()
        stopListening(sleeping = false)
        closeGatt()
        _state.value = Connection.Idle
    }

    fun close() {
        stopScan()
        stopListening(sleeping = false)
        closeGatt()
    }

    // --- passive drivers: they listen to advertisements, and may connect for a moment (poll) ---

    /** The brush a passive driver listens to. */
    private class Listening(
        val device: BluetoothDevice,
        val name: String,
        val driver: ToothbrushDriver,
        val session: BrushSession,
    )

    private var listening: Listening? = null
    private var passiveScan: ScanCallback? = null
    private var silence: Job? = null
    private var lastAdHex: String? = null
    private var polling: Job? = null
    private var pollGatt: BluetoothGatt? = null
    private var pollAttempts = 0
    private var lastPollAt = 0L

    private fun listen(device: BluetoothDevice, name: String, driver: ToothbrushDriver, first: Advertisement) {
        if (adapter?.bluetoothLeScanner == null) { _state.value = Connection.BluetoothOff; return }
        val s = driver.open(NoLink)
        val l = Listening(device, name, driver, s)
        session = s
        listening = l
        pollAttempts = 0
        lastPollAt = 0L
        diag.log("listening (passive, no connection)")
        _state.value = Connection.Connected(name, driver, s, device)
        startPassiveScan(l)
        feed(l, first)
    }

    private fun feed(l: Listening, ad: Advertisement) {
        val mask = AddressMask(l.device.address)
        val hex = ad.manufacturerData.entries.joinToString(" ") { (id, data) -> "%04X:".format(id) + mask.hex(data) }
        if (hex != lastAdHex) {
            lastAdHex = hex
            diag.log("advertisement $hex")
        }
        l.session.onAdvertisement(ad)
        restartSilence(l)
        maybePoll(l)
    }

    private fun startPassiveScan(l: Listening) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val self = this
                val ad = result.toAdvertisement()
                scope.launch { if (listening === l && passiveScan === self) feed(l, ad) }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    diag.log("passive scan FAILED: error $errorCode")
                    stopListening(sleeping = false)
                    _state.value = Connection.Failed("scan error $errorCode")
                }
            }
        }
        passiveScan = cb
        val filter = ScanFilter.Builder().setDeviceAddress(l.device.address).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    private fun stopPassiveScan() {
        val cb = passiveScan ?: return
        passiveScan = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: IllegalStateException) {
            // Bluetooth was turned off meanwhile.
        }
    }

    /**
     * The session needs something the brush doesn't broadcast: stop listening, connect for a moment,
     * let the session read it, disconnect and listen again. The brush doesn't advertise while
     * connected, so the silence timer pauses too. A few tries per listening, then it's left alone.
     */
    private fun maybePoll(l: Listening) {
        if (polling != null || !l.session.wantsPoll || pollAttempts >= MAX_POLL_ATTEMPTS) return
        val now = SystemClock.elapsedRealtime()
        if (pollAttempts > 0 && now - lastPollAt < POLL_RETRY_MS) return
        pollAttempts++
        lastPollAt = now
        polling = scope.launch {
            val me = coroutineContext[Job]
            stopPassiveScan()
            silence?.cancel()
            diag.log("short connection ($pollAttempts/$MAX_POLL_ATTEMPTS) to read what the brush doesn't broadcast")
            try {
                withTimeout(POLL_TIMEOUT_MS) { shortConnection(l.device, l.session) { link -> l.session.poll(link) } }
                diag.log("short connection done")
            } catch (_: TimeoutCancellationException) {
                diag.log("short connection: no answer within ${POLL_TIMEOUT_MS / 1000} s")
            } catch (e: IOException) {
                diag.log("short connection FAILED: ${e.message}")
            } finally {
                if (polling === me) polling = null
                if (listening === l) {
                    startPassiveScan(l)
                    restartSilence(l)
                }
            }
        }
    }

    private suspend fun shortConnection(device: BluetoothDevice, s: BrushSession, block: suspend (BrushLink) -> Unit) {
        val ready = CompletableDeferred<AndroidBrushLink>()
        val cb = PollCallback(ready, s)
        val g = device.connectLe(appContext, cb) ?: throw IOException("Bluetooth refused the connection")
        pollGatt = g
        try {
            block(ready.await())
        } finally {
            cb.link?.fail("closed")
            g.disconnect()
            g.close()
            if (pollGatt === g) pollGatt = null
        }
    }

    /** No advertisement for a while: the brush went to sleep or out of range. */
    private fun restartSilence(l: Listening) {
        silence?.cancel()
        silence = scope.launch {
            delay(PASSIVE_SILENCE_MS)
            diag.log("no advertisements for ${PASSIVE_SILENCE_MS / 1000} s")
            stopListening(sleeping = false)
            _state.value = Connection.Disconnected(l.name, l.driver, l.session.state.value)
        }
    }

    private fun stopListening(sleeping: Boolean) {
        silence?.cancel()
        silence = null
        val l = listening ?: return
        listening = null
        polling?.cancel()
        polling = null
        pollGatt?.let {
            it.disconnect()
            it.close()
        }
        pollGatt = null
        stopPassiveScan()
        lastAdHex = null
        if (sleeping) {
            val c = _state.value as? Connection.Connected
            if (c != null) _state.value = Connection.Disconnected(c.name, c.driver, l.session.state.value)
        }
        session = null
    }

    /** GATT callbacks of a short connection: connect, discover, answer reads, read requests and subscriptions. */
    private inner class PollCallback(
        private val ready: CompletableDeferred<AndroidBrushLink>,
        private val session: BrushSession,
    ) : BluetoothGattCallback() {
        @Volatile var link: AndroidBrushLink? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            diag.log("short connection: state $newState (GATT status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
                return
            }
            link?.fail("disconnected")
            ready.completeExceptionally(IOException("not connected (GATT status $status)"))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(IOException("service discovery failed (GATT status $status)"))
                return
            }
            val l = AndroidBrushLink(g, diag)
            link = l
            ready.complete(l)
        }

        // Read requests some brushes need first (e.g. Oral-B: which session to read).
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            result(status, ByteArray(0))

        // Subscriptions, for values a brush only sends as a notification (e.g. Oral-B's brush head reminder).
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            result(status, ByteArray(0))

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            diag.log("short connection: notification ${value.hex()}")
            session.onNotification(ch.uuid, value)
        }

        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = ch.value ?: return
                diag.log("short connection: notification ${value.hex()}")
                session.onNotification(ch.uuid, value)
            }
        }

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            result(status, value)

        // Android 12 and older
        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) result(status, ch.value ?: ByteArray(0))
        }

        private fun result(status: Int, value: ByteArray) {
            if (status == BluetoothGatt.GATT_SUCCESS) link?.complete(value) else link?.fail("GATT status $status")
        }
    }

    private fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        unsupportedGrace?.cancel()
        unsupportedGrace = null
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: IllegalStateException) {
            // Bluetooth was turned off meanwhile.
        }
    }

    private fun closeGatt() {
        link?.fail("closed")
        gatt?.close()
        gatt = null
        link = null
        session = null
    }

    private fun connect(device: BluetoothDevice, name: String, driver: ToothbrushDriver) {
        _state.value = Connection.Connecting(name, driver)
        diag.log("connecting")
        gatt = device.connectLe(appContext, Callback(name, driver))
    }

    private inner class Callback(val name: String, val driver: ToothbrushDriver) : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "connected"
                BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                else -> "state $newState"
            }
            diag.log("link $stateName (GATT status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                diag.log("discovering services")
                g.discoverServices()
                return
            }
            val last = session?.state?.value
            link?.fail("disconnected")
            g.close()
            scope.launch {
                if (gatt === g) {
                    gatt = null
                    link = null
                    session = null
                }
                _state.value = Connection.Disconnected(name, driver, last)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            diag.log("services discovered: ${g.services.size} (GATT status $status)")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = Connection.Unsupported(name)
                g.disconnect()
                return
            }
            val l = AndroidBrushLink(g, diag)
            val s = driver.open(l)
            link = l
            session = s
            scope.launch {
                try {
                    s.start()
                    diag.log("ready")
                    _state.value = Connection.Connected(name, driver, s, g.device)
                } catch (e: IOException) {
                    diag.log("handshake FAILED: ${e.message}")
                    _state.value = Connection.Unsupported(name)
                    g.disconnect()
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            result(status, ByteArray(0))

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            result(status, ByteArray(0))

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            result(status, value)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            diag.log("notification ${value.hex()}")
            session?.onNotification(ch.uuid, value)
        }

        // Android 12 and older
        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) result(status, ch.value ?: ByteArray(0))
        }

        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = ch.value ?: return
                diag.log("notification ${value.hex()}")
                session?.onNotification(ch.uuid, value)
            }
        }

        private fun result(status: Int, value: ByteArray) {
            if (status == BluetoothGatt.GATT_SUCCESS) link?.complete(value) else link?.fail("GATT status $status")
        }
    }

    private companion object {
        // The brush can take a while to advertise again after a disconnect.
        const val SCAN_TIMEOUT_MS = 30_000L
        const val UNSUPPORTED_GRACE_MS = 4_000L
        const val PASSIVE_SILENCE_MS = 30_000L
        const val MAX_POLL_ATTEMPTS = 2
        const val POLL_RETRY_MS = 60_000L
        const val POLL_TIMEOUT_MS = 35_000L // battery, up to 30 history entries, brush head reminder
    }
}
