package io.github.damnms.buerstli.drivers.sonicare

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.Intensity
import io.github.damnms.buerstli.domain.ModeName
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BATTERY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_MODE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_TIME
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HANDLE_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_LIMIT
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_USAGE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.INTENSITY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.ROUTINE_LENGTH
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * Values follow the formats in philips_sonicare_ble/docs/BLE_PROTOCOL.md. [diamondClean9000] holds
 * real values from a DiamondClean 9000 (HX960X, firmware 1.5.3), taken from Bürstli 0.9.0's log and
 * report on 2026-10-31; what the phone showed was checked on screenshots. [diamondClean] is made up
 * and covers what that brush doesn't send, like brush head wear.
 */
class SonicareTest {

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte()) // little-endian

    /** A pretend DiamondClean: answers reads from [values], fails for missing characteristics. */
    private class FakeSonicare(val values: MutableMap<UUID, ByteArray>) : BrushLink {
        val subscribed = mutableListOf<UUID>()
        val reads = mutableListOf<UUID>()
        val writes = mutableListOf<UUID>()
        var failFirstReads = 0

        override suspend fun read(characteristic: UUID): ByteArray {
            reads += characteristic
            if (failFirstReads-- > 0) throw IOException("GATT status 5")
            return values[characteristic] ?: throw IOException("characteristic $characteristic not found")
        }

        override suspend fun write(characteristic: UUID, data: ByteArray) {
            writes += characteristic
        }

        override suspend fun enableNotifications(characteristic: UUID) {
            if (characteristic !in values) throw IOException("characteristic $characteristic not found")
            subscribed += characteristic
        }
    }

    private fun diamondClean() = FakeSonicare(
        mutableMapOf(
            BATTERY to byteArrayOf(87),
            HANDLE_STATE to byteArrayOf(1),        // standby
            BRUSHING_STATE to byteArrayOf(0),      // off
            BRUSHING_MODE to byteArrayOf(1),       // White+
            INTENSITY to byteArrayOf(2),           // high
            BRUSHING_TIME to u16(0),
            ROUTINE_LENGTH to u16(120),
            HEAD_LIMIT to u16(21600),
            HEAD_USAGE to u16(5400),
        ),
    )

    /** Exactly what the HX960X answered in Bürstli's log, in standby. Its head has no chip: 0/0. */
    private fun diamondClean9000() = FakeSonicare(
        mutableMapOf(
            HANDLE_STATE to byteArrayOf(0x01),
            BATTERY to byteArrayOf(0x62),
            BRUSHING_STATE to byteArrayOf(0x00),
            BRUSHING_MODE to byteArrayOf(0x01),
            INTENSITY to byteArrayOf(0x01),
            BRUSHING_TIME to byteArrayOf(0x00, 0x00),
            ROUTINE_LENGTH to byteArrayOf(0xC8.toByte(), 0x00),
            HEAD_LIMIT to byteArrayOf(0x00, 0x00),
            HEAD_USAGE to byteArrayOf(0x00, 0x00),
        ),
    )

    // --- recognition ---

    private val classic = UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50001")
    private val condor = UUID.fromString("e50ba3c0-af04-4564-92ad-fef019489de6")

    @Test
    fun `recognizes Sonicare by name or classic service`() {
        assertTrue(SonicareClassicDriver.recognizes(Advertisement("Philips Sonicare")))
        assertTrue(SonicareClassicDriver.recognizes(Advertisement("Philips OHC")))
        assertTrue(SonicareClassicDriver.recognizes(Advertisement(null, listOf(classic))))
    }

    @Test
    fun `leaves the 7100 series alone, it only speaks Condor`() {
        assertFalse(SonicareClassicDriver.recognizes(Advertisement("Philips Sonicare", listOf(condor))))
    }

    @Test
    fun `is experimental, may need pairing and changes nothing`() {
        assertEquals(SupportLevel.EXPERIMENTAL, SonicareClassicDriver.supportLevel)
        assertTrue(SonicareClassicDriver.mayNeedPairing)
        assertTrue(SonicareClassicDriver.capabilities.modes.isEmpty())
        assertTrue(SonicareClassicDriver.capabilities.switches.isEmpty())
        assertNull(SonicareClassicDriver.capabilities.brushingDuration)
    }

    // --- session ---

    @Test
    fun `subscribes before reading, so the brush keeps the connection`() = runTest {
        val brush = diamondClean()
        SonicareClassicDriver.open(brush).start()

        assertTrue(brush.subscribed.containsAll(listOf(HANDLE_STATE, BRUSHING_MODE, BRUSHING_TIME, BATTERY)))
        assertEquals(HANDLE_STATE, brush.subscribed.first())
    }

    @Test
    fun `reads battery, state, mode, intensity, times and brush head wear`() = runTest {
        val session = SonicareClassicDriver.open(diamondClean()).apply { start() }
        val s = session.state.value

        assertEquals(87, s.batteryPercent)
        assertEquals(Activity.STANDBY, s.activity)
        assertEquals(ModeName.WHITE_PLUS, s.modeName)
        assertEquals(Intensity.HIGH, s.intensity)
        assertEquals(0, s.brushingSeconds)
        assertEquals(120, s.brushingDurationSeconds)
        assertEquals(25, s.brushHeadWearPercent)
    }

    @Test
    fun `a real DiamondClean 9000 in standby reads as the phone showed it`() = runTest {
        val s = SonicareClassicDriver.open(diamondClean9000()).apply { start() }.state.value

        assertEquals(98, s.batteryPercent)
        assertEquals(Activity.STANDBY, s.activity) // "bereit"
        assertEquals(ModeName.WHITE_PLUS, s.modeName)
        assertEquals(Intensity.MEDIUM, s.intensity)
        assertEquals(0, s.brushingSeconds)
        assertEquals(200, s.brushingDurationSeconds) // "0:00 / 3:20"
        assertNull(s.brushHeadWearPercent) // no chip in the head: show nothing rather than 0 %
    }

    @Test
    fun `a real DiamondClean 9000 counts up while brushing`() = runTest {
        val session = SonicareClassicDriver.open(diamondClean9000()).apply { start() }

        // State and brushing times as the brush sent them in the report while brushing,
        // the battery notification from the log.
        session.onNotification(HANDLE_STATE, byteArrayOf(0x02))
        session.onNotification(BRUSHING_STATE, byteArrayOf(0x01))
        session.onNotification(BRUSHING_TIME, byteArrayOf(0x25, 0x00))
        session.onNotification(BRUSHING_TIME, byteArrayOf(0x26, 0x00))
        session.onNotification(BATTERY, byteArrayOf(0x61))

        val s = session.state.value
        assertEquals(Activity.BRUSHING, s.activity) // "putzt"
        assertEquals(38, s.brushingSeconds) // "0:38 / 3:20"
        assertEquals(97, s.batteryPercent)
    }

    @Test
    fun `live notifications update the state`() = runTest {
        val session = SonicareClassicDriver.open(diamondClean()).apply { start() }

        session.onNotification(HANDLE_STATE, byteArrayOf(2))
        session.onNotification(BRUSHING_STATE, byteArrayOf(1))
        session.onNotification(BRUSHING_TIME, u16(45))
        assertEquals(Activity.BRUSHING, session.state.value.activity)
        assertEquals(45, session.state.value.brushingSeconds)

        session.onNotification(BRUSHING_STATE, byteArrayOf(2))
        assertEquals(Activity.PAUSED, session.state.value.activity)

        session.onNotification(HANDLE_STATE, byteArrayOf(3))
        assertEquals(Activity.CHARGING, session.state.value.activity)
    }

    @Test
    fun `the kids model lacks brushing state and brush head data, that's fine`() = runTest {
        val kids = diamondClean().apply {
            values.remove(BRUSHING_STATE); values.remove(HEAD_LIMIT); values.remove(HEAD_USAGE)
        }
        val session = SonicareClassicDriver.open(kids).apply { start() }

        assertEquals(87, session.state.value.batteryPercent)
        assertNull(session.state.value.brushHeadWearPercent)
    }

    @Test(expected = IOException::class)
    fun `without the classic handle state it is not a classic Sonicare`() = runTest {
        val other = diamondClean().apply { values.remove(HANDLE_STATE) }
        SonicareClassicDriver.open(other).start()
    }

    @Test
    fun `a first read that fails while the phone pairs is retried`() = runTest {
        val brush = diamondClean().apply { failFirstReads = 2 }
        val session = SonicareClassicDriver.open(brush).apply { start() }

        assertEquals(Activity.STANDBY, session.state.value.activity)
    }

    @Test
    fun `nothing is ever written to the brush`() = runTest {
        val brush = diamondClean()
        val session = SonicareClassicDriver.open(brush).apply { start() }

        assertEquals(CommandResult.Unsupported, session.execute(BrushCommand.ActivateMode(0)))
        assertTrue(brush.writes.isEmpty())
    }

    @Test
    fun `unknown values and broken packets are ignored`() = runTest {
        val session = SonicareClassicDriver.open(diamondClean()).apply { start() }

        session.onNotification(BRUSHING_MODE, byteArrayOf(42))
        session.onNotification(BRUSHING_TIME, byteArrayOf(1)) // too short for uint16

        assertNull(session.state.value.modeName)
        assertEquals(0, session.state.value.brushingSeconds)
    }
}
