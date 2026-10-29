package io.github.damnms.buerstli.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CaptureReportTest {

    private fun uuid16(short: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private val report = CaptureReport(
        appVersion = "0.4.0",
        phone = "OnePlus CPH2449, Android 14",
        brand = "Philips Sonicare",
        deviceName = "Philips Sonicare",
        address = "C0:00:00:01:1D:EE",
        advertisements = listOf(
            AdvertisementSample(1_200, -61, listOf(UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50001")), mapOf(477 to byteArrayOf(0x01, 0x02))),
        ),
        services = listOf(
            ServiceDump(
                uuid16(0x180A),
                listOf(
                    CharacteristicDump(uuid16(0x2A24), setOf("READ"), "HX992X".toByteArray(), null),
                    CharacteristicDump(uuid16(0x2A25), setOf("READ"), "SN123456789".toByteArray(), null),
                    CharacteristicDump(uuid16(0x2A23), setOf("READ"), byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null),
                ),
            ),
            ServiceDump(
                uuid16(0x180F),
                listOf(CharacteristicDump(uuid16(0x2A19), setOf("READ", "NOTIFY"), byteArrayOf(0x5E), null)),
            ),
            ServiceDump(
                UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50004"),
                listOf(
                    CharacteristicDump(
                        UUID.fromString("477ea600-a260-11e4-ae37-0002a5d54100"),
                        setOf("READ"), null, "GATT status 5 (pairing needed?)",
                    ),
                ),
            ),
        ),
        notifications = listOf(NotificationSample(32_500, uuid16(0x2A19), byteArrayOf(0x5D))),
    )

    private val text = report.render()

    @Test
    fun `masks the device address`() {
        assertFalse(text.contains("01:1D:EE"))
        assertTrue(text.contains("C0:00:00:XX:XX:XX"))
    }

    @Test
    fun `redacts serial number and system id, which identify the device`() {
        assertFalse(text.contains("SN123456789"))
        assertFalse(text.contains("01-02-03-04-05-06-07-08"))
        assertTrue(text.contains("[removed for privacy]"))
    }

    @Test
    fun `shows values as hex and readable text, with short ids for standard uuids`() {
        assertTrue(text.contains("0x2A24"))
        assertTrue(text.contains("\"HX992X\""))
        assertTrue(text.contains("0x2A19 [READ, NOTIFY] = 5E"))
    }

    @Test
    fun `keeps errors, advertisements and notifications`() {
        assertTrue(text.contains("GATT status 5 (pairing needed?)"))
        assertTrue(text.contains("manufacturer 0x01DD: 01-02"))
        assertTrue(text.contains("+32.50s 0x2A19 5D"))
    }
}
