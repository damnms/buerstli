package io.github.damnms.buerstli.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import io.github.damnms.buerstli.Diagnostics
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Adapter: implements the domain's [BrushLink] on top of Android's BluetoothGatt.
 *
 * Android allows only one GATT operation at a time and reports results through
 * callbacks. This class serializes operations with a mutex and turns each callback
 * into the result of a suspending call. [BleConnector] forwards the callbacks.
 */
@SuppressLint("MissingPermission")
internal class AndroidBrushLink(
    private val gatt: BluetoothGatt,
    private val diag: Diagnostics,
) : BrushLink {

    private val mutex = Mutex()
    @Volatile private var pending: CompletableDeferred<ByteArray>? = null

    override suspend fun read(characteristic: UUID): ByteArray =
        operation("read ${characteristic.short()}") { gatt.readCharacteristic(find(characteristic)) }

    override suspend fun write(characteristic: UUID, data: ByteArray) {
        operation("write ${characteristic.short()} ${data.hex()}") {
            val ch = find(characteristic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                ch.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        }
    }

    override suspend fun enableNotifications(characteristic: UUID) {
        operation("subscribe ${characteristic.short()}") {
            val ch = find(characteristic)
            val cccd = ch.getDescriptor(CCCD) ?: throw IOException("characteristic has no notification descriptor")
            if (!gatt.setCharacteristicNotification(ch, true)) throw IOException("could not enable notifications")
            val canNotify = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val enable = if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }
    }

    /** Called from GATT callbacks. */
    fun complete(value: ByteArray) {
        pending?.complete(value)
    }

    /** Called from GATT callbacks, and on disconnect. */
    fun fail(reason: String) {
        pending?.completeExceptionally(IOException(reason))
    }

    private fun find(uuid: UUID): BluetoothGattCharacteristic =
        gatt.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
            ?: throw IOException("characteristic $uuid not found")

    private suspend fun operation(name: String, start: () -> Boolean): ByteArray = mutex.withLock {
        val result = CompletableDeferred<ByteArray>()
        pending = result
        val t0 = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0
        try {
            if (!start()) throw IOException("Bluetooth refused the operation")
            // withTimeoutOrNull, not withTimeout: a cancellation from the caller (e.g. its own
            // timeout) must propagate as cancellation, not turn into "brush did not answer".
            val value = withTimeoutOrNull(OPERATION_TIMEOUT_MS) { result.await() } ?: run {
                diag.log("$name: NO ANSWER after ${ms()} ms")
                throw IOException("brush did not answer")
            }
            diag.log("$name: ok after ${ms()} ms" + if (value.isNotEmpty()) " -> ${value.hex()}" else "")
            value
        } catch (e: kotlinx.coroutines.CancellationException) {
            diag.log("$name: cancelled after ${ms()} ms")
            throw e
        } catch (e: IOException) {
            diag.log("$name: FAILED after ${ms()} ms: ${e.message}")
            throw e
        } finally {
            pending = null
        }
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val OPERATION_TIMEOUT_MS = 5_000L
    }
}

private const val SIG_BASE = "-0000-1000-8000-00805f9b34fb"

/** For the log: "2A24" for standard characteristics, otherwise the last 8 digits ("…a5d54022"). */
internal fun UUID.short(): String {
    val s = toString()
    return if (s.startsWith("0000") && s.endsWith(SIG_BASE)) s.substring(4, 8).uppercase() else "…" + s.takeLast(8)
}
