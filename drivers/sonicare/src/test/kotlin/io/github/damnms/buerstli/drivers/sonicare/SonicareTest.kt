package io.github.damnms.buerstli.drivers.sonicare

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.Intensity
import io.github.damnms.buerstli.domain.ModeName
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.ACTIVE_SESSION
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BATTERY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_MODE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_TIME
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.FIRMWARE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HANDLE_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_LIMIT
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_USAGE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.INTENSITY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.LATEST_SESSION
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.MODEL_NUMBER
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.ROUTINE_LENGTH
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SESSION_CONTROL
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SESSION_COUNT
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SESSION_DATA
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SESSION_TYPE
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
        val writes = mutableListOf<Pair<UUID, ByteArray>>()
        var failFirstReads = 0

        /** What the brush does when written to, e.g. send its stored sessions. */
        var onWrite: (UUID, ByteArray) -> Unit = { _, _ -> }

        override suspend fun read(characteristic: UUID): ByteArray {
            reads += characteristic
            if (failFirstReads-- > 0) throw IOException("GATT status 5")
            return values[characteristic] ?: throw IOException("characteristic $characteristic not found")
        }

        override suspend fun write(characteristic: UUID, data: ByteArray) {
            writes += characteristic to data
            onWrite(characteristic, data)
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

    /**
     * Exactly what the HX960X answered in Bürstli's log, in standby. Its head has no chip: 0/0.
     * 0.9.0 didn't read the model number, so it isn't here either; mode 1 was shown as White+, which
     * the app's table for an HX960X reads as Gum Health – still open, see docs/SONICARE.md.
     */
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
    fun `without a storage service nothing is written to the brush`() = runTest {
        val brush = diamondClean()
        val session = SonicareClassicDriver.open(brush).apply { start() }

        assertEquals(CommandResult.Unsupported, session.execute(BrushCommand.ActivateMode(0)))
        assertTrue(brush.writes.isEmpty()) // no storage service: nothing to ask for
    }

    @Test
    fun `unknown values and broken packets are ignored`() = runTest {
        val session = SonicareClassicDriver.open(diamondClean()).apply { start() }

        session.onNotification(BRUSHING_MODE, byteArrayOf(42))
        session.onNotification(BRUSHING_TIME, byteArrayOf(1)) // too short for uint16

        assertNull(session.state.value.modeName)
        assertEquals(0, session.state.value.brushingSeconds)
    }

    // --- model number and modes (app) ---

    private fun withModel(model: String, mode: Int) = diamondClean().apply {
        values[MODEL_NUMBER] = model.toByteArray()
        values[BRUSHING_MODE] = byteArrayOf(mode.toByte())
    }

    @Test
    fun `reads the model number before the mode, because the mode depends on it`() = runTest {
        val brush = withModel("HX991X", 1).apply { failFirstReads = 1 } // pairing: the first read fails
        val s = SonicareClassicDriver.open(brush).apply { start() }.state.value

        assertTrue(brush.reads.indexOf(MODEL_NUMBER) in 1 until brush.reads.indexOf(BRUSHING_MODE))
        assertEquals("HX991X", s.modelName)
        assertEquals(ModeName.WHITE_PLUS, s.modeName)
    }

    @Test
    fun `the same mode number means something else on every model`() = runTest {
        suspend fun mode(model: String, n: Int) =
            SonicareClassicDriver.open(withModel(model, n)).apply { start() }.state.value.modeName

        assertEquals(ModeName.GUM_HEALTH, mode("HX960X", 1)) // ExpertClean: Clean, Gum Care, Deep Clean
        assertEquals(ModeName.DEEP_CLEAN_PLUS, mode("HX960X", 2))
        assertEquals(ModeName.WHITE_PLUS, mode("HX961X", 1))
        assertEquals(ModeName.SENSITIVE, mode("HX741X", 1))
        assertEquals(ModeName.POLISH, mode("HX98XX", 7))
        assertEquals(ModeName.COMFORT, mode("HX92XX", 6))
        assertNull(mode("HX960X", 3)) // the ExpertClean has three modes
    }

    @Test
    fun `unknown models get the app's default list, which swaps 4 and 5 against the old table`() {
        assertEquals(ModeName.DEEP_CLEAN_PLUS, SonicareCodec.mode(3, null))
        assertEquals(ModeName.TONGUE_CARE, SonicareCodec.mode(4, "HX992X"))
        assertEquals(ModeName.SENSITIVE, SonicareCodec.mode(5, "HX992X"))
        assertEquals(ModeName.COMFORT, SonicareCodec.mode(7, null))
        assertNull(SonicareCodec.mode(8, null))
    }

    @Test
    fun `xian models count by routine id, without skipping an entry`() {
        assertEquals(ModeName.TONGUE_CARE, SonicareCodec.mode(3, "HX999X"))
        assertEquals(ModeName.DEEP_CLEAN_PLUS, SonicareCodec.mode(4, "HX9996"))
        assertEquals(ModeName.POLISH, SonicareCodec.mode(8, "HX999X"))
    }

    @Test
    fun `reads the firmware and trims padding from Device Information`() = runTest {
        val brush = withModel("HX960X\u0000\u0000", 0).apply { values[FIRMWARE] = "1.5.3 ".toByteArray() }
        val s = SonicareClassicDriver.open(brush).apply { start() }.state.value

        assertEquals("HX960X", s.modelName)
        assertEquals("1.5.3", s.firmwareVersion)
    }

    // --- brushing history (app) ---

    /** A stored session as the app parses it: 13 bytes (v1), or 16 with the routine id. */
    private fun record(id: Int, start: Long, brushed: Int, length: Int, routine: Int, intensity: Int, routineId: Int? = null): ByteArray {
        val b = ByteArray(if (routineId == null) 13 else 16)
        for (i in 0..3) b[1 + i] = (start shr (8 * i)).toByte()
        u16(id).copyInto(b, 5); u16(brushed).copyInto(b, 7); u16(length).copyInto(b, 9)
        b[11] = routine.toByte(); b[12] = intensity.toByte()
        routineId?.let { b[13] = it.toByte() }
        return b
    }

    /** A brush with stored sessions that sends them like the app expects. */
    private fun withHistory(sessions: List<ByteArray>, latest: Int, session: () -> BrushSessionRef) = withModel("HX991X", 0).apply {
        values[LATEST_SESSION] = u16(latest)
        values[SESSION_COUNT] = u16(sessions.size)
        listOf(ACTIVE_SESSION, SESSION_DATA, SESSION_CONTROL, SESSION_TYPE).forEach { values[it] = byteArrayOf() }
        onWrite = { uuid, data ->
            if (uuid == SESSION_CONTROL && data.contentEquals(byteArrayOf(1))) {
                val s = session().value!!
                sessions.forEach { r ->
                    s.onNotification(ACTIVE_SESSION, r.copyOfRange(5, 7))
                    s.onNotification(SESSION_DATA, r)
                    s.onNotification(SESSION_DATA, byteArrayOf(9, 9, 9)) // a later packet of the session
                    s.onNotification(SESSION_CONTROL, byteArrayOf(0))
                }
            }
        }
    }

    private class BrushSessionRef { var value: io.github.damnms.buerstli.domain.BrushSession? = null }

    private val jan2026 = 1_767_225_600L // 2026-01-01T00:00:00Z

    @Test
    fun `fetches the stored sessions like the app, newest first`() = runTest {
        val ref = BrushSessionRef()
        val brush = withHistory(
            listOf(
                record(10, jan2026, 118, 120, 1, 2),
                record(11, jan2026 + 86_400, 125, 120, 3, 0),
                record(12, 1_000, 60, 120, 0, 1), // clock never set
            ),
            latest = 12,
        ) { ref }
        ref.value = SonicareClassicDriver.open(brush)
        ref.value!!.start()

        val history = ref.value!!.state.value.history
        assertEquals(listOf(12, 11, 10), history.map { it.number })
        assertEquals(125, history[1].brushedSeconds)
        assertEquals(120, history[1].targetSeconds)
        assertEquals(ModeName.DEEP_CLEAN_PLUS, history[1].modeName) // HX991X: 3 = Deep Clean
        assertEquals(java.time.Instant.ofEpochSecond(jan2026 + 86_400), history[1].startedAt)
        assertNull(history[0].startedAt)
        assertEquals(ModeName.WHITE_PLUS, history[2].modeName)
    }

    @Test
    fun `asks only for what it reads, in the app's order`() = runTest {
        val ref = BrushSessionRef()
        val brush = withHistory(listOf(record(41, jan2026, 120, 120, 0, 1), record(42, jan2026, 120, 120, 0, 1)), latest = 42) { ref }
        ref.value = SonicareClassicDriver.open(brush)
        ref.value!!.start()

        assertEquals(
            listOf(SESSION_TYPE to listOf<Byte>(0), ACTIVE_SESSION to listOf<Byte>(41, 0), SESSION_CONTROL to listOf<Byte>(1)),
            brush.writes.map { (u, d) -> u to d.toList() },
        )
    }

    @Test
    fun `fetches at most the newest 30`() = runTest {
        val ref = BrushSessionRef()
        val brush = withHistory(emptyList(), latest = 500) { ref }.apply { values[SESSION_COUNT] = u16(200) }
        ref.value = SonicareClassicDriver.open(brush)
        ref.value!!.start()

        assertEquals(ACTIVE_SESSION to listOf<Byte>(0xD7.toByte(), 0x01), brush.writes[1].let { (u, d) -> u to d.toList() }) // 471
    }

    @Test
    fun `a brush that stops answering gets a cancel and the sessions that arrived`() = runTest {
        val ref = BrushSessionRef()
        val brush = withHistory(listOf(record(7, jan2026, 120, 120, 0, 1)), latest = 9) { ref }.apply {
            values[SESSION_COUNT] = u16(3)
        }
        ref.value = SonicareClassicDriver.open(brush)
        ref.value!!.start()

        assertEquals(listOf(7), ref.value!!.state.value.history.map { it.number })
        assertEquals(SESSION_CONTROL to listOf<Byte>(2), brush.writes.last().let { (u, d) -> u to d.toList() })
    }

    @Test
    fun `no sessions stored, nothing asked for`() = runTest {
        val ref = BrushSessionRef()
        val brush = withHistory(emptyList(), latest = 0) { ref }
        ref.value = SonicareClassicDriver.open(brush)
        ref.value!!.start()

        assertTrue(ref.value!!.state.value.history.isEmpty())
        assertEquals(listOf(SESSION_TYPE), brush.writes.map { it.first })
    }

    @Test
    fun `xian sessions take the mode from the routine id`() {
        val r = SonicareCodec.session(record(3, jan2026, 120, 120, routine = 0, intensity = 1, routineId = 3), "HX999X")!!
        assertEquals(ModeName.TONGUE_CARE, r.modeName)
        assertNull(SonicareCodec.session(ByteArray(12), null)) // too short
    }

    @Test
    fun `every message the codec can build only selects what to read`() {
        val allowed = setOf(
            SESSION_TYPE to listOf<Byte>(0),
            SESSION_CONTROL to listOf<Byte>(1),
            SESSION_CONTROL to listOf<Byte>(2),
        )
        val built = listOf(SonicareCodec.Request.ReadSessions, SonicareCodec.Request.SendAll, SonicareCodec.Request.Cancel)
        built.forEach { assertTrue(allowed.contains(it.characteristic to it.bytes.toList())) }
        val start = SonicareCodec.Request.StartAt(0x1234)
        assertEquals(ACTIVE_SESSION to listOf<Byte>(0x34, 0x12), start.characteristic to start.bytes.toList())
    }

    @Test
    fun `full product numbers find the most specific short form`() {
        assertEquals("HX961X", SonicareCodec.modelCode("HX9611"))
        assertEquals("HX961X", SonicareCodec.modelCode("hx9611/62 "))
        assertEquals("HX962X", SonicareCodec.modelCode("HX9621")) // not the broader HX9XXX
        assertEquals("HX9XXX", SonicareCodec.modelCode("HX9050"))
        assertEquals("HX9996", SonicareCodec.modelCode("HX9996"))
        assertEquals("HX999X", SonicareCodec.modelCode("HX9990"))
        assertEquals("HX742X", SonicareCodec.modelCode("HX7423"))
        assertNull(SonicareCodec.modelCode("HX6310")) // not in the app
        assertNull(SonicareCodec.modelCode("Sonicare"))
        assertNull(SonicareCodec.modelCode(null))
    }

    @Test
    fun `a full product number still gets its model's modes`() {
        assertEquals(ModeName.WHITE_PLUS, SonicareCodec.mode(1, "HX9611")) // HX961X: Clean, White, Deep Clean
        assertEquals(ModeName.GUM_HEALTH, SonicareCodec.mode(1, "HX9601")) // HX960X: Clean, Gum Care, Deep Clean
        assertEquals(ModeName.TONGUE_CARE, SonicareCodec.mode(3, "HX9990")) // xian
    }
}
