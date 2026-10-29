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

    // Found in a real report from an LFTB01-P: the brush puts its own address, byte-reversed,
    // into the advertisement, and the last two address bytes into its name.
    private val laifen = CaptureReport(
        appVersion = "0.6.1",
        phone = "OnePlus CPH2449, Android 17",
        brand = "Laifen",
        deviceName = "LFTB01-P-1DEE",
        address = "C0:00:00:01:1D:EE",
        advertisements = listOf(
            AdvertisementSample(4_060, -61, emptyList(), mapOf(0xAA00 to byteArrayOf(0xEE.toByte(), 0x1D, 0x01, 0x00, 0x00, 0xC0.toByte()))),
        ),
        services = listOf(
            ServiceDump(uuid16(0x1800), listOf(CharacteristicDump(uuid16(0x2A00), setOf("READ"), "LFTB01-P-1DEE".toByteArray(), null))),
        ),
        notifications = listOf(
            NotificationSample(1_000, uuid16(0xFF02), byteArrayOf(0x09, 0xC0.toByte(), 0x00, 0x00, 0x01, 0x1D, 0xEE.toByte())),
        ),
    ).render()

    @Test
    fun `the address is masked inside advertisement data, also when reversed`() {
        assertFalse(laifen.contains("EE-1D-01"))
        assertTrue(laifen.contains("manufacturer 0xAA00: XX-XX-XX-00-00-C0"))
    }

    @Test
    fun `the address is masked inside values and notifications`() {
        assertFalse(laifen.contains("00-01-1D-EE"))
        assertTrue(laifen.contains("09-C0-00-00-XX-XX-XX"))
    }

    @Test
    fun `an address suffix in the device name is masked, the model part stays`() {
        assertFalse(laifen.contains("1DEE"))
        assertTrue(laifen.contains("\"LFTB01-P-XXXX\""))
    }
}
