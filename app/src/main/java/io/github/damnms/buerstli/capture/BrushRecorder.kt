package io.github.damnms.buerstli.capture

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
import io.github.damnms.buerstli.Diagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(val brand: String, val phase: Phase, val secondsLeft: Int, val progress: Float) : CaptureState
    data class Done(val report: CaptureReport) : CaptureState
    data class Failed(val brand: String, val reason: String) : CaptureState

    enum class Phase { ADVERTISEMENTS, CONNECTING, READING, NOTIFICATIONS }
}

/**
 * Records what an unsupported brush says, for a report its owner can send us.
 * Strictly read-only: it reads characteristics and subscribes to notifications, and never
 * writes anything except the standard "notify me" descriptor.
 */
@SuppressLint("MissingPermission")
class BrushRecorder(
    context: Context,
    private val scope: CoroutineScope,
    private val diag: Diagnostics,
) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var job: Job? = null
    private var gatt: BluetoothGatt? = null
    private var start = 0L
    private var brand = ""

    private val ads = mutableListOf<AdvertisementSample>()
    private val notifications = mutableListOf<NotificationSample>()
    private val problems: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile private var linkDown = false

    /** Wrapped so that "answered without a value" (e.g. a descriptor write) differs from a timeout. */
    private class Answer(val value: ByteArray?)

    @Volatile private var pending: CompletableDeferred<Answer>? = null
    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    @Volatile private var discovered: CompletableDeferred<Boolean>? = null

    fun start(device: BluetoothDevice, name: String?, brand: String) {
        stop()
        ads.clear(); notifications.clear(); problems.clear(); linkDown = false
        start = System.currentTimeMillis()
        this.brand = brand
        diag.mark("capture: $brand ($name)")
        job = scope.launch {
            listenToAdvertisements(device)
            val services = readGatt(device)
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            val report = CaptureReport(
                appVersion = appVersion(),
                phone = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                brand = brand,
                deviceName = name,
                address = device.address,
                advertisements = synchronized(ads) { ads.toList() },
                services = services,
                notifications = synchronized(notifications) { notifications.toList() },
                problems = synchronized(problems) { problems.toList() },
            )
            diag.log("capture done: ${report.advertisements.size} ads, ${services.size} services, ${report.notifications.size} notifications")
            _state.value = CaptureState.Done(report)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        gatt?.close()
        gatt = null
        _state.value = CaptureState.Idle
    }

    private fun now() = System.currentTimeMillis() - start

    private suspend fun countdown(phase: CaptureState.Phase, seconds: Int) {
        for (left in seconds downTo 1) {
            _state.value = CaptureState.Running(brand, phase, left, 1f - left.toFloat() / seconds)
            delay(1_000)
        }
    }

    // --- Phase 1: advertisements (Oral-B, for example, sends live data this way) ---

    private suspend fun listenToAdvertisements(device: BluetoothDevice) {
        val scanner = adapter?.bluetoothLeScanner ?: run { problems += "no scanner"; return }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val manufacturer = buildMap {
                    val data = record.manufacturerSpecificData ?: return@buildMap
                    for (i in 0 until data.size()) put(data.keyAt(i), data.valueAt(i))
                }
                val sample = AdvertisementSample(
                    timeMs = now(),
                    rssi = result.rssi,
                    serviceUuids = record.serviceUuids?.map { it.uuid } ?: emptyList(),
                    manufacturerData = manufacturer,
                )
                synchronized(ads) {
                    val last = ads.lastOrNull()
                    val changed = last == null || last.serviceUuids != sample.serviceUuids ||
                        last.manufacturerData.mapValues { it.value.toList() } != manufacturer.mapValues { it.value.toList() }
                    if (changed && ads.size < MAX_ADS) ads += sample
                }
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(device.address).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        try {
            countdown(CaptureState.Phase.ADVERTISEMENTS, ADVERTISEMENT_SECONDS)
        } finally {
            try { scanner.stopScan(cb) } catch (_: IllegalStateException) { }
        }
    }

    // --- Phase 2: connect, read everything readable, subscribe to everything ---

    private suspend fun readGatt(device: BluetoothDevice): List<ServiceDump> {
        _state.value = CaptureState.Running(brand, CaptureState.Phase.CONNECTING, 0, 0f)
        connected = CompletableDeferred()
        discovered = CompletableDeferred()
        val g = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected!!.await() } != true) {
            problems += "could not connect (is the brush awake and not connected to another app?)"
            return emptyList()
        }
        g.discoverServices()
        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { discovered!!.await() } != true) {
            problems += "service discovery failed"
            return emptyList()
        }

        _state.value = CaptureState.Running(brand, CaptureState.Phase.READING, 0, 0f)
        val dumps = g.services.map { service ->
            ServiceDump(
                uuid = service.uuid,
                characteristics = service.characteristics.map { ch ->
                    var value: ByteArray? = null
                    var error: String? = null
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        val result = op { g.readCharacteristic(ch) }
                        value = result.getOrNull()
                        error = result.exceptionOrNull()?.message
                    }
                    CharacteristicDump(ch.uuid, propertiesOf(ch), value, error)
                },
            )
        }

        for (ch in g.services.flatMap { it.characteristics }) {
            val notify = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!notify && !indicate) continue
            val cccd = ch.getDescriptor(CCCD) ?: continue
            g.setCharacteristicNotification(ch, true)
            val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            op {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }.exceptionOrNull()?.let { problems += "subscribe ${ch.uuid}: ${it.message}" }
        }

        if (!linkDown) countdown(CaptureState.Phase.NOTIFICATIONS, NOTIFICATION_SECONDS)
        return dumps
    }

    private suspend fun op(start: () -> Boolean): Result<ByteArray?> {
        val result = CompletableDeferred<Answer>()
        pending = result
        return try {
            if (!start()) return Result.failure(IllegalStateException("refused by Android"))
            val answer = withTimeoutOrNull(OPERATION_TIMEOUT_MS) { result.await() }
                ?: return Result.failure(IllegalStateException("no answer"))
            Result.success(answer.value)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } finally {
            pending = null
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            diag.log("capture link state $newState (GATT status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkDown = true
                connected?.complete(false)
                discovered?.complete(false)
                pending?.completeExceptionally(IllegalStateException("disconnected"))
                if (start > 0) problems += "disconnected after ${now() / 1000} s"
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            diag.log("capture services: ${g.services.size} (GATT status $status)")
            discovered?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(status, value)

        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) finish(status, ch.value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status, null)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            record(ch.uuid, value)

        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) record(ch.uuid, ch.value ?: return)
        }

        private fun finish(status: Int, value: ByteArray?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pending?.complete(Answer(value))
            } else {
                val hint = if (status == 5 || status == 15) " (pairing needed?)" else ""
                pending?.completeExceptionally(IllegalStateException("GATT status $status$hint"))
            }
        }

        private fun record(uuid: UUID, value: ByteArray) {
            synchronized(notifications) {
                if (notifications.size < MAX_NOTIFICATIONS) notifications += NotificationSample(now(), uuid, value.copyOf())
            }
        }
    }

    private fun propertiesOf(ch: BluetoothGattCharacteristic) = buildSet {
        val p = ch.properties
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val ADVERTISEMENT_SECONDS = 20
        const val NOTIFICATION_SECONDS = 30
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val OPERATION_TIMEOUT_MS = 4_000L
        const val MAX_ADS = 100
        const val MAX_NOTIFICATIONS = 500
    }
}
