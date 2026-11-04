package io.github.damnms.buerstli.drivers.xiaomi

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.KeyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

internal class XiaomiPassiveSession(
    private val productIds: Set<Int>,
    encrypted: Boolean,
) : BrushSession {
    private val _state = MutableStateFlow(BrushState(keyStatus = if (encrypted) KeyStatus.MISSING else null))
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private var key: ByteArray? = null

    /** The last encrypted frame, to try a newly entered key at once instead of waiting for the brush. */
    private var lastEncrypted: Pair<ByteArray, String?>? = null
    private var failures = 0

    override suspend fun start() {}

    override fun onNotification(characteristic: UUID, value: ByteArray) {}

    override fun onAdvertisement(advertisement: Advertisement) {
        val data = advertisement.serviceData[MiBeaconCodec.SERVICE] ?: return
        val beacon = MiBeaconCodec.parse(data) ?: return
        if (beacon.productId !in productIds) return
        if (beacon.encrypted) {
            lastEncrypted = data to advertisement.address
            read(data, beacon, advertisement.address)
        } else {
            // Objects only overwrite what they report, so a repeated frame changes nothing.
            _state.value = beacon.objects.fold(_state.value, MiBeaconCodec::apply)
        }
    }

    override fun setKey(key: ByteArray?) {
        this.key = key
        failures = 0
        val last = lastEncrypted
        when {
            last != null -> MiBeaconCodec.parse(last.first)?.let { read(last.first, it, last.second) }
            key == null -> _state.update { if (it.keyStatus != null) it.copy(keyStatus = KeyStatus.MISSING) else it }
            else -> _state.update { if (it.keyStatus == KeyStatus.MISSING) it.copy(keyStatus = null) else it }
        }
    }

    private fun read(data: ByteArray, beacon: MiBeacon, address: String?) {
        // Older frames (V2/V3) use another scheme Bürstli doesn't implement; no key would help.
        if (beacon.version < 4) return
        val k = key
        if (k == null) {
            _state.update { it.copy(keyStatus = KeyStatus.MISSING) }
            return
        }
        val objects = MiBeaconCodec.decrypt(data, beacon, k, address)
        if (objects == null) {
            // One damaged frame doesn't make the key wrong; two in a row do (as xiaomi-ble does).
            if (++failures >= 2 || _state.value.keyStatus != KeyStatus.OK) {
                _state.update { it.copy(keyStatus = KeyStatus.WRONG) }
            }
            return
        }
        failures = 0
        _state.value = objects.fold(_state.value.copy(keyStatus = KeyStatus.OK), MiBeaconCodec::apply)
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
