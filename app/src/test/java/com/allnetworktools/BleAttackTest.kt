package com.allnetworktools

import com.allnetworktools.data.Ad
import com.allnetworktools.data.AdvertAbuse
import com.allnetworktools.data.AdvertDetect
import com.allnetworktools.data.AdvertLevel
import com.allnetworktools.data.AdvertSeen
import com.allnetworktools.data.AdvertWatch
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.FlipperKind
import com.allnetworktools.data.SpamFrame
import com.allnetworktools.data.SpamHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pop-up frames are recognised from the payloads the open-source detectors documented
 * (simondankelmann/Bluetooth-LE-Spam, K3YOMI/Wall-of-Flippers). Nothing here transmits.
 */
class BleAttackTest {
    private val now = 1_000_000L

    /** An advertisement carrying one manufacturer structure. */
    private fun mfg(company: Int, dataHex: String): String {
        val payload = "%02X%02X".format(company and 0xFF, (company shr 8) and 0xFF) + dataHex
        return "%02X".format(payload.length / 2 + 1) + "FF" + payload
    }

    private fun hit(hex: String) = AdvertDetect.classify(Ad.parseHex(hex))

    private fun addr(i: Int) = "AA:BB:CC:00:%02X:%02X".format(i / 256, i % 256)

    private fun seen(addr: String, hits: Set<SpamHit> = emptySet(), rssi: Int = -60, flipper: FlipperKind? = null) =
        AdvertSeen(addr, now - 5_000, now, rssi, hits, flipper)

    private fun dev(addr: String, hex: String, name: String? = null, rssi: Int = -60) = BleDevice(
        address = addr, name = name, rssi = rssi, txPower = null, kind = BleKind.Unknown, maker = null,
        connectable = false, lastSeen = 0L, raw = hex.ifEmpty { null }, ads = Ad.parseHex(hex),
    )

    // Payloads as the detectors list them.
    private val actionModal = mfg(0x004C, "0F05C027C72B7C")
    private val ios17Crash = mfg(0x004C, "0F05C00D39F791000010720DAB")
    private val proxAirtag = mfg(0x004C, "07190500305546493200006AF02720E126B2854197E6E2F07E75D0")
    private val proxSetup = mfg(0x004C, "0719070F205560144C00009775AF597963B2AC9A29CAFCC152F567")
    private val swiftPair = mfg(0x0006, "030080094B0065007900620064")
    private val easyWatch = mfg(0x0075, "010002000101FF0000431A")
    private val easyBuds = mfg(0x0075, "42098102141503210109" + "01" + "063C948E00000000C700")
    private val fastPair = "020106" + "06162CFE112233"
    private val lovespouse = mfg(0x00FF, "FFFF006DB643CE97FE427C0304")

    /** The very payload BleIdentifyTest calls an AirPods Pro: a real product, not an attack. */
    private val airPodsPro = "1EFF4C000719010E202B8F" + "00".repeat(20)

    @Test fun recognisesTheDocumentedPopupFrames() {
        assertEquals(SpamFrame.ActionModal, hit(actionModal)?.frame)
        assertEquals(SpamFrame.Ios17Crash, hit(ios17Crash)?.frame)
        assertEquals(SpamFrame.ProximityPairing, hit(proxAirtag)?.frame)
        assertEquals(SpamFrame.SwiftPair, hit(swiftPair)?.frame)
        assertEquals(SpamFrame.EasySetupWatch, hit(easyWatch)?.frame)
        assertEquals(SpamFrame.EasySetupBuds, hit(easyBuds)?.frame)
        assertEquals(SpamFrame.FastPair, hit(fastPair)?.frame)
        assertEquals(SpamFrame.Lovespouse, hit(lovespouse)?.frame)
    }

    @Test fun readsTheAppleActionNumberAndNamesIt() {
        assertEquals(0x27, hit(actionModal)?.action)
        assertEquals(0x0D, hit(ios17Crash)?.action)
        assertEquals("Whole Home Audio Setup", AdvertDetect.nearbyAction(0x0D))
        assertEquals("Watch Setup", AdvertDetect.nearbyAction(0x05))
        // Past the dissector's table the frame still reports, under its number.
        assertNull(AdvertDetect.nearbyAction(0x27))
        assertEquals("Continuity · Action Modal (0x27)", hit(actionModal)?.title)
        assertEquals("Continuity · crash iOS 17 (Whole Home Audio Setup)", hit(ios17Crash)?.title)
    }

    @Test fun realAirPodsAreProximityPairingAndNothingMore() {
        val h = hit(airPodsPro)
        assertEquals(SpamFrame.ProximityPairing, h?.frame)
        assertEquals(0x01, h?.action)
        // On their own they raise nothing: the card is the product's, not an attacker's.
        assertEquals(AdvertLevel.Calm, AdvertDetect.assess(listOf(seen(addr(1), setOf(h!!))), now).level)
    }

    @Test fun theCrashFrameIsToldFromAModalThatCarriesASecondMessage() {
        // Action Modal followed by a well-formed Nearby Info: not the crash frame.
        assertEquals(SpamFrame.ActionModal, hit(mfg(0x004C, "0F05C027C72B7C" + "1005031C9B4F2A"))?.frame)
        // The same modal with bytes hung past its declared end, starting 00 00 10.
        assertEquals(SpamFrame.Ios17Crash, hit(mfg(0x004C, "0F05C027C72B7C" + "000010720DAB"))?.frame)
    }

    @Test fun ordinaryAdvertisementsAreNotPopupFrames() {
        assertNull(hit("0201060303AAFE0C16AAFE00F0010203040506"))      // Eddystone beacon
        assertNull(hit(mfg(0x004C, "1005031C9B4F2A")))                  // Apple Nearby Info
        assertNull(hit(mfg(0x0075, "0100000000000000")))                // other Samsung frame
        assertNull(hit(""))
    }

    @Test fun pairedFastPairEarbudsDoNotCount() {
        // Discoverable Fast Pair is a bare 3-byte model id; once paired it is a longer key filter.
        assertEquals(SpamFrame.FastPair, hit("020106" + "06162CFE112233")?.frame)
        assertNull(hit("020106" + "0A162CFE00112233445566"))
    }

    @Test fun yourOwnEarbudsAreNotAnAttack() {
        val v = AdvertDetect.assess(listOf(seen(addr(1), setOf(SpamHit(SpamFrame.FastPair, 0x112233)))), now)
        assertEquals(AdvertLevel.Calm, v.level)
        assertTrue(v.findings.isEmpty())
        assertEquals(1, v.sources)
    }

    @Test fun aQuietRoomRaisesNothing() {
        val v = AdvertDetect.assess((1..20).map { seen(addr(it)) }, now)
        assertEquals(AdvertLevel.Calm, v.level)
        assertEquals(0, v.sources)
        assertEquals(20, v.advertisers)
    }

    @Test fun aCraftedFrameSettlesItOnItsOwn() {
        val v = AdvertDetect.assess(listOf(seen(addr(1), setOf(SpamHit(SpamFrame.Ios17Crash, 0x0D)))), now)
        assertEquals(AdvertLevel.Attack, v.level)
        assertTrue("iOS 17" in v.findings.single { it.abuse == AdvertAbuse.KnownAttack }.detail)
    }

    @Test fun cyclingThroughKindsOfCardIsAnAttack() {
        val hits = setOf(SpamHit(SpamFrame.ProximityPairing, 1), SpamHit(SpamFrame.SwiftPair), SpamHit(SpamFrame.EasySetupWatch))
        val v = AdvertDetect.assess(listOf(seen(addr(1), hits)), now)
        assertEquals(AdvertLevel.Attack, v.level)
        assertEquals(3, v.findings.single { it.abuse == AdvertAbuse.Cycling }.count)
    }

    @Test fun cyclingThroughModelBytesIsAnAttack() {
        // One address claiming to be three different Apple products in a minute.
        val hits = setOf(
            SpamHit(SpamFrame.ProximityPairing, 0x01),
            SpamHit(SpamFrame.ProximityPairing, 0x05),
            SpamHit(SpamFrame.ProximityPairing, 0x07),
        )
        val v = AdvertDetect.assess(listOf(seen(addr(1), hits)), now)
        assertEquals(AdvertLevel.Attack, v.level)
        assertTrue(v.findings.any { it.abuse == AdvertAbuse.Cycling })
    }

    @Test fun cyclingThroughAppleActionsIsAnAttack() {
        val hits = setOf(
            SpamHit(SpamFrame.ActionModal, 0x05),
            SpamHit(SpamFrame.ActionModal, 0x09),
            SpamHit(SpamFrame.ActionModal, 0x08),
        )
        val v = AdvertDetect.assess(listOf(seen(addr(1), hits)), now)
        assertEquals(AdvertLevel.Attack, v.level)
        assertTrue("Watch Setup" in v.findings.single { it.abuse == AdvertAbuse.Cycling }.detail)
    }

    @Test fun twoKindsFromTwoAddressesStayQuiet() {
        val hits = setOf(SpamHit(SpamFrame.ProximityPairing, 1), SpamHit(SpamFrame.SwiftPair))
        assertEquals(AdvertLevel.Calm, AdvertDetect.assess(listOf(seen(addr(1), hits), seen(addr(2), hits)), now).level)
    }

    @Test fun aCafeFullOfEarbudsIsOnlyWorthMentioning() {
        // Five people, five AirPods, five distances: said out loud, but not called an attack.
        val room = (1..5).map { seen(addr(it), setOf(SpamHit(SpamFrame.ProximityPairing, 0x01)), rssi = -40 - it * 9) }
        val v = AdvertDetect.assess(room, now)
        assertEquals(AdvertLevel.Suspect, v.level)
        assertEquals(5, v.findings.single { it.abuse == AdvertAbuse.PopupSpam }.count)
    }

    @Test fun oneRadioBehindThoseAddressesIsAnAttack() {
        val sources = (1..5).map { seen(addr(it), setOf(SpamHit(SpamFrame.ProximityPairing, 0x01)), rssi = -61 + it % 3) }
        val v = AdvertDetect.assess(sources, now)
        assertTrue(v.findings.any { it.abuse == AdvertAbuse.OneRadio })
        assertEquals(AdvertLevel.Attack, v.level)
    }

    @Test fun aFrankCrowdOfOffersIsAnAttackWhateverTheSignal() {
        val sources = (1..9).map { seen(addr(it), setOf(SpamHit(SpamFrame.ProximityPairing, 0x01)), rssi = -30 - it * 7) }
        assertEquals(AdvertLevel.Attack, AdvertDetect.assess(sources, now).level)
    }

    @Test fun theFlipperIsNamedByItsService() {
        assertEquals(FlipperKind.Black, AdvertDetect.flipper(Ad.parseHex("03038130"), addr(1), null))
        assertEquals(FlipperKind.White, AdvertDetect.flipper(Ad.parseHex("03038230"), addr(1), null))
        assertEquals(FlipperKind.Transparent, AdvertDetect.flipper(Ad.parseHex("03038330"), addr(1), null))
        assertNull(AdvertDetect.flipper(Ad.parseHex("03030F18"), addr(1), "Watch"))
    }

    @Test fun theFlipperIsNamedByItsNameOrItsAddress() {
        assertEquals(FlipperKind.Black, AdvertDetect.flipper(emptyList(), addr(1), "Flipper Dyno"))
        assertEquals(FlipperKind.Black, AdvertDetect.flipper(emptyList(), "80:E1:26:11:22:33", null))
        assertEquals(FlipperKind.Black, AdvertDetect.flipper(emptyList(), "0C:FA:22:11:22:33", null))
        assertNull(AdvertDetect.flipper(emptyList(), "11:22:33:44:55:66", "Pixel Buds"))
    }

    @Test fun aFlipperNearbyIsReported() {
        val v = AdvertDetect.assess(listOf(seen(addr(1), flipper = FlipperKind.White)), now)
        assertTrue("Flipper Zero" in v.findings.single { it.abuse == AdvertAbuse.Flipper }.detail)
        assertEquals(AdvertLevel.Suspect, v.level)
    }

    @Test fun theWatchRemembersEveryCardAnAddressSent() {
        // The cycling only shows if the earlier frames are kept: each packet carries one card.
        val watch = AdvertWatch()
        watch.observe(listOf(dev(addr(1), proxSetup)), now)
        watch.observe(listOf(dev(addr(1), swiftPair)), now + 1_000)
        watch.observe(listOf(dev(addr(1), easyWatch)), now + 2_000)
        val v = watch.assess(now + 2_000)
        assertEquals(1, v.advertisers)
        assertEquals(3, v.frames.size)
        assertEquals(AdvertLevel.Attack, v.level)
    }

    @Test fun theWatchSpotsAFlipperThroughTheScanner() {
        val watch = AdvertWatch()
        watch.observe(listOf(dev(addr(1), "03038230", name = "Flipper Zoe")), now)
        assertTrue(watch.assess(now).findings.any { it.abuse == AdvertAbuse.Flipper })
    }

    @Test fun aBurstIsStillOnScreenOnceTheWindowHasForgottenIt() {
        // Flooding comes and goes in seconds; the page must not go blank as if nothing had passed.
        val watch = AdvertWatch(windowMs = 10_000)
        watch.observe((1..9).map { dev(addr(it), proxSetup, rssi = -30 - it * 7) }, now)
        assertEquals(AdvertLevel.Attack, watch.assess(now).level)
        watch.observe(emptyList(), now + 30_000)
        assertEquals(AdvertLevel.Calm, watch.assess(now + 30_000).level)
        assertEquals(AdvertLevel.Attack, watch.peak?.level)
        assertEquals(9, watch.peak?.sources)
        watch.reset()
        assertNull(watch.peak)
    }

    @Test fun anAddressIsForgottenOnceTheWindowHasPassed() {
        val watch = AdvertWatch(windowMs = 10_000)
        watch.observe(listOf(dev(addr(1), proxSetup)), now)
        watch.observe(listOf(dev(addr(2), swiftPair)), now + 20_000)
        assertEquals(1, watch.size)
        watch.reset()
        assertEquals(0, watch.size)
    }
}
