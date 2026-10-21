package com.allnetworktools.data

import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class BleKind(val label: String, val filter: String, val icon: String) {
    Audio("Audio", "Audio", Sym.Headphones),
    Watch(S(R.string.watch), S(R.string.watches), Sym.Watch),
    Tag("Tag", "Tags", Sym.Sell),
    Phone(S(R.string.phone), S(R.string.phones), Sym.Smartphone),
    Computer(S(R.string.computer), S(R.string.computers), Sym.Laptop),
    Media(S(R.string.tv_media), S(R.string.tv_media_2), Sym.Tv),
    Accessory(S(R.string.accessory), S(R.string.accessories), Sym.Devices),
    Health(S(R.string.health), S(R.string.health), Sym.Favorite),
    Home(S(R.string.home), S(R.string.home), Sym.Lightbulb),
    Vehicle(S(R.string.vehicle), S(R.string.vehicles), Sym.DirectionsCar),
    Unknown(S(R.string.unknown), S(R.string.unknown), Sym.Bluetooth),
}

/** Result of [BleIdentify.identify]: what the device is, how sure we are, and why. */
data class BleIdentity(
    val kind: BleKind = BleKind.Unknown,
    val maker: String? = null,
    val model: String? = null,
    val icon: String? = null,
    /** 0–99. Below 60 the identification is a probable guess. */
    val confidence: Int = 0,
    val evidence: List<String> = emptyList(),
    /** Decoded values (iBeacon UUID, temperature…), shown in the device page. */
    val details: List<Pair<String, String>> = emptyList(),
) {
    val known get() = kind != BleKind.Unknown
    val guessed get() = known && confidence < 60
}

/**
 * Classifies a BLE device from everything its advertisements reveal. Each rule proposes a
 * candidate (kind, label, score, reason); the best-scored candidate wins and the others that
 * agree with it raise the confidence. Pure Kotlin: no Android types, so it is unit-testable.
 */
object BleIdentify {
    private class Cand(val kind: BleKind, val label: String?, val maker: String?, val icon: String?, val score: Int, val reason: String)

    private class Ctx {
        val cands = mutableListOf<Cand>()
        val details = mutableListOf<Pair<String, String>>()
        fun add(kind: BleKind, label: String?, maker: String?, score: Int, reason: String, icon: String? = null) {
            cands += Cand(kind, label, maker, icon, score, reason)
        }

        fun detail(k: String, v: String) {
            details += k to v
        }
    }

    fun identify(name: String?, ads: List<AdStructure>, address: String = "", gatt: GattIdentity? = null): BleIdentity {
        val ctx = Ctx()
        val localName = (name ?: Ad.name(ads) ?: gatt?.name)?.trim()?.takeIf { it.isNotEmpty() }
        val mfg = Ad.manufacturer(ads)
        val sdata = Ad.serviceData(ads)

        // 1. Company identifiers and their payloads.
        mfg.forEach { (cid, d) ->
            when (cid) {
                0x004C -> apple(ctx, d)
                0x0006 -> microsoft(ctx, d)
                0x0499 -> ruuvi(ctx, d)
                else -> Unit
            }
            MakerHints[cid]?.let { h -> ctx.add(h.kind, h.label, Companies.plain(cid), h.score, S(R.string.manufacturer_2, Companies.plain(cid)), h.icon) }
        }

        // 2. Service data with a known layout.
        sdata[0xFEAA]?.let { eddystone(ctx, it) }
        sdata[0xFE2C]?.let { fastPair(ctx, it) }
        sdata[0xFE95]?.let { xiaomi(ctx, it) }
        sdata[0xFD6F]?.let { ctx.detail(S(R.string.exposure_notification), S(R.string.random_proximity_identifier_bytes, it.size)) }

        // 3. Advertised (or discovered) services.
        val uuids16 = Ad.uuids16(ads) + (gatt?.services ?: emptySet())
        uuids16.forEach { u -> Svc16[u]?.let { h -> ctx.add(h.kind, h.label, h.maker, h.score + (if (gatt != null && u in gatt.services && u !in Ad.uuids16(ads)) 6 else 0), "Service ${"0x%04X".format(u)}${h.maker?.let { " ($it)" } ?: ""}", h.icon) } }
        Ad.uuids128(ads).forEach { u -> Svc128.firstOrNull { it.matches(u) }?.let { h -> ctx.add(h.kind, h.label, h.maker, h.score, "Service $u", h.icon) } }

        // 4. What the device says it is: appearance, class of device.
        (gatt?.appearance ?: Ad.appearance(ads))?.let { appearance(ctx, it, if (gatt?.appearance != null) "GATT" else "annonce") }
        Ad.classOfDevice(ads)?.let { classOfDevice(ctx, it) }

        // 5. Names: advertised name plus, after a connection, manufacturer and model strings.
        val texts = buildList {
            localName?.let { add(it to S(R.string.name, it)) }
            gatt?.model?.let { add(it to S(R.string.gatt_model, it)) }
            gatt?.manufacturer?.let { add(it to S(R.string.gatt_manufacturer, it)) }
        }
        texts.forEach { (t, src) -> nameRules(ctx, t, src) }
        localName?.let { n -> if (TeslaName.matches(n)) ctx.add(BleKind.Vehicle, "Tesla", "Tesla", 92, S(R.string.tesla_name, n), Sym.DirectionsCar) }
        gatt?.manufacturer?.let { m -> gattMaker(m)?.let { ctx.add(BleKind.Unknown, null, it, 0, S(R.string.gatt_manufacturer_2, it)) } }

        return conclude(ctx, mfg.keys, gatt, uuids16, ads, address)
    }

    private fun conclude(ctx: Ctx, companies: Set<Int>, gatt: GattIdentity?, uuids16: Set<Int>, ads: List<AdStructure>, address: String): BleIdentity {
        val known = ctx.cands.filter { it.kind != BleKind.Unknown && it.score >= 40 }
        val best = known.maxWithOrNull(compareBy<Cand> { it.score }.thenBy { it.label != null })
        val makerFromCompany = companies.mapNotNull { c -> Companies.plain(c)?.takeIf { !Companies.isChipset(c) } }.firstOrNull()
            ?: companies.firstNotNullOfOrNull { c -> Companies.name(c) }
            ?: companies.firstOrNull()?.let { S(R.string.manufacturer_0x).format(it) }
        val makerFromGatt = ctx.cands.firstOrNull { it.kind == BleKind.Unknown && it.maker != null }?.maker
        if (best == null) {
            val ev = buildList {
                if (ads.isEmpty()) add(S(R.string.no_advertising_packet_received)) else add(S(R.string.no_identity_clue_in_the_advertisement, ads.size, if (ads.size > 1) "s" else ""))
                if (makerFromCompany != null) add(S(R.string.manufacturer_2, makerFromCompany))
            }
            return BleIdentity(BleKind.Unknown, makerFromGatt ?: makerFromCompany, null, null, 0, ev, ctx.details)
        }
        val agreeing = known.filter { it !== best && it.kind == best.kind }.map { it.reason }.distinct()
        val confidence = minOf(99, best.score + 4 * minOf(agreeing.size, 3))
        val maker = best.maker ?: known.firstOrNull { it.kind == best.kind && it.maker != null }?.maker ?: makerFromGatt ?: makerFromCompany
        val model = best.label ?: known.firstOrNull { it.kind == best.kind && it.label != null }?.label
        val icon = best.icon ?: known.firstOrNull { it.kind == best.kind && it.icon != null && it.score >= best.score - 10 }?.icon
        return BleIdentity(best.kind, maker, model, icon, confidence, (listOf(best.reason) + agreeing).take(6), ctx.details)
    }

    // ---- Apple Continuity ------------------------------------------------------------------------

    private val AppleTypes = mapOf(
        0x02 to "iBeacon", 0x03 to "AirPrint", 0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity Pairing", 0x08 to "Hey Siri",
        0x09 to S(R.string.airplay_target), 0x0A to S(R.string.airplay_source), 0x0B to "Magic Switch", 0x0C to "Handoff", 0x0D to S(R.string.personal_hotspot_target),
        0x0E to S(R.string.personal_hotspot_source), 0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to S(R.string.find_my),
    )

    internal val AirPods = mapOf(
        0x0220 to "AirPods", 0x0F20 to S(R.string.airpods_2nd_generation), 0x1320 to S(R.string.airpods_3rd_generation), 0x0E20 to "AirPods Pro",
        0x1420 to S(R.string.airpods_pro_2nd_generation), 0x0A20 to "AirPods Max", 0x0B20 to "Powerbeats Pro", 0x0520 to "BeatsX",
        0x0620 to "Beats Solo3", 0x0920 to "Beats Studio3", 0x0320 to "Powerbeats3", 0x0C20 to "Beats Solo Pro",
        0x1020 to "Beats Flex", 0x1120 to "Beats Studio Buds",
    )

    internal val IBeaconUuids = mapOf(
        "B9407F30-F5F8-466E-AFF9-25556B57FE6D" to "Estimote", "F7826DA6-4FA2-4E98-8024-BC5B71E0893E" to "Kontakt.io",
        "2F234454-CF6D-4A0F-ADF2-F4911BA9FFA6" to "Radius Networks", "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0" to "AirLocate / Minew",
        "FDA50693-A4E2-4FB1-AFCF-C6EB07647825" to S(R.string.feasycom_generic_beacon), "61687109-905F-4436-91F8-E602F514C96D" to "BlueCats",
        "23A01AF0-232A-4518-9C0E-323FB773F5EF" to "Sensoro",
    )

    private fun tlv(d: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 2 <= d.size) {
            val t = d.u8(i)
            val l = d.u8(i + 1)
            if (t == 0) break
            out += t to d.copyOfRange(i + 2, minOf(i + 2 + l, d.size))
            i += 2 + l
        }
        return out
    }

    private fun apple(ctx: Ctx, d: ByteArray) {
        val items = tlv(d)
        if (items.isEmpty()) {
            ctx.add(BleKind.Phone, S(R.string.apple_device), "Apple", 45, S(R.string.apple_manufacturer_data), Sym.Smartphone)
            return
        }
        val types = items.map { it.first }.toSet()
        ctx.detail(S(R.string.continuity_frames), types.sorted().joinToString(", ") { AppleTypes[it] ?: "0x%02X".format(it) })
        for ((t, p) in items) when (t) {
            0x02 -> if (p.size >= 21) {
                val uuid = listOf(p.hex(0, 4), p.hex(4, 6), p.hex(6, 8), p.hex(8, 10), p.hex(10, 16)).joinToString("-") { it.replace(" ", "") }
                val brand = IBeaconUuids[uuid]
                ctx.add(BleKind.Tag, "iBeacon" + (brand?.let { " ($it)" } ?: ""), brand?.substringBefore(' ') ?: null, 92, S(R.string.ibeacon_frame_apple_0x02), Sym.Sell)
                ctx.detail("iBeacon UUID", uuid)
                ctx.detail("Major / minor", "${p.u16be(16)} / ${p.u16be(18)}")
                ctx.detail(S(R.string.power_at_1_m), "${p[20]} dBm")
            }
            0x07 -> if (p.size >= 3) {
                val model = p.u16be(1)
                val label = AirPods[model]
                ctx.add(BleKind.Audio, label ?: S(R.string.apple_beats_earbuds), "Apple", 96, S(R.string.proximity_pairing_frame_0x07_model_0x).format(model), Sym.Headphones)
                if (label == null) ctx.detail(S(R.string.apple_model), S(R.string.n_0x_not_listed).format(model))
            }
            0x12 -> ctx.add(BleKind.Tag, S(R.string.airtag_find_my_accessory), "Apple", 86, S(R.string.find_my_frame_0x12), Sym.Sell)
            0x03 -> ctx.add(BleKind.Accessory, S(R.string.airprint_printer), null, 88, S(R.string.airprint_frame_0x03), Sym.Print)
            0x06 -> ctx.add(BleKind.Home, S(R.string.homekit_accessory), "Apple", 86, S(R.string.homekit_frame_0x06), Sym.Lightbulb)
            0x0B -> ctx.add(BleKind.Watch, "Apple Watch", "Apple", 84, S(R.string.magic_switch_frame_0x0b_sent_by), Sym.Watch)
            0x09 -> ctx.add(BleKind.Media, S(R.string.apple_tv_homepod_or_mac_airplay), "Apple", 68, S(R.string.airplay_target_0x09), Sym.Tv)
            else -> Unit
        }
        // Any other Continuity frame: an iPhone, iPad, Mac or Watch nearby.
        ctx.add(BleKind.Phone, S(R.string.apple_device_iphone_ipad_mac_or), "Apple", 55, S(R.string.apple_continuity_frames), Sym.Smartphone)
    }

    // ---- Microsoft CDP ---------------------------------------------------------------------------

    private fun microsoft(ctx: Ctx, d: ByteArray) {
        if (d.size >= 2 && d.u8(0) == 0x01) {
            val t = d.u8(1) and 0x3F
            val r = when (t) {
                1 -> Triple(BleKind.Media, "Xbox One", Sym.Tv)
                6 -> Triple(BleKind.Phone, S(R.string.iphone_phone_link), Sym.Smartphone)
                7 -> Triple(BleKind.Phone, S(R.string.ipad_phone_link), Sym.Tablet)
                8 -> Triple(BleKind.Phone, S(R.string.android_phone_phone_link), Sym.Smartphone)
                9 -> Triple(BleKind.Computer, S(R.string.windows_desktop_pc), Sym.Laptop)
                11 -> Triple(BleKind.Phone, "Windows Phone", Sym.Smartphone)
                12 -> Triple(BleKind.Computer, S(R.string.linux_device), Sym.Laptop)
                13 -> Triple(BleKind.Home, "Windows IoT", Sym.Memory)
                14 -> Triple(BleKind.Media, "Surface Hub", Sym.Tv)
                15 -> Triple(BleKind.Computer, S(R.string.windows_laptop), Sym.Laptop)
                16 -> Triple(BleKind.Computer, S(R.string.windows_tablet), Sym.Tablet)
                else -> null
            }
            ctx.detail(S(R.string.microsoft_cdp_type), t.toString())
            if (r != null) {
                val ms = t in setOf(1, 9, 11, 13, 14, 15, 16)
                ctx.add(r.first, r.second, if (ms) "Microsoft" else null, 88, S(R.string.microsoft_cdp_beacon_type, t), r.third)
                return
            }
        }
        ctx.add(BleKind.Computer, S(R.string.microsoft_device), "Microsoft", 52, S(R.string.microsoft_manufacturer_data), Sym.Laptop)
    }

    // ---- Ruuvi -------------------------------------------------------------------------------------

    private fun ruuvi(ctx: Ctx, d: ByteArray) {
        var label = S(R.string.ruuvitag_sensor)
        if (d.size >= 12 && d.u8(0) == 5) {
            val temp = ((d[1].toInt() shl 8) or d.u8(2)) * 0.005
            val hum = d.u16be(3) * 0.0025
            val press = (d.u16be(5) + 50000) / 100.0
            ctx.detail(S(R.string.temperature), "%.1f °C".format(java.util.Locale.getDefault(), temp))
            ctx.detail(S(R.string.humidity), "%.0f %%".format(java.util.Locale.getDefault(), hum))
            ctx.detail(S(R.string.pressure), "%.0f hPa".format(java.util.Locale.getDefault(), press))
            label = "RuuviTag · %.1f °C".format(java.util.Locale.getDefault(), temp)
        }
        ctx.add(BleKind.Home, label, "Ruuvi", 97, S(R.string.ruuvi_manufacturer_data), Sym.Thermostat)
    }

    // ---- Service data layouts -----------------------------------------------------------------

    private fun eddystone(ctx: Ctx, d: ByteArray) {
        if (d.isEmpty()) return
        when (d.u8(0)) {
            0x00 -> {
                ctx.add(BleKind.Tag, S(R.string.eddystone_uid_beacon), null, 92, S(R.string.eddystone_uid_frame), Sym.Sell)
                if (d.size >= 18) {
                    ctx.detail("Eddystone namespace", d.hex(2, 12).replace(" ", ""))
                    ctx.detail("Eddystone instance", d.hex(12, 18).replace(" ", ""))
                }
            }
            0x10 -> {
                ctx.add(BleKind.Tag, S(R.string.eddystone_url_beacon), null, 92, S(R.string.eddystone_url_frame), Sym.Sell)
                eddystoneUrl(d)?.let { ctx.detail("URL", it) }
            }
            0x20 -> {
                ctx.add(BleKind.Tag, S(R.string.eddystone_tlm_beacon), null, 88, S(R.string.eddystone_tlm_frame_telemetry), Sym.Sell)
                if (d.size >= 6) {
                    ctx.detail(S(R.string.battery), "${d.u16be(2)} mV")
                    if (d.u16be(4) != 0x8000) ctx.detail(S(R.string.temperature), "%.1f °C".format(java.util.Locale.getDefault(), ((d[4].toInt() shl 8) or d.u8(5)) / 256.0))
                }
            }
            0x30 -> ctx.add(BleKind.Tag, S(R.string.eddystone_eid_beacon), null, 90, S(R.string.eddystone_eid_frame), Sym.Sell)
            0x40, 0x41 -> ctx.add(BleKind.Tag, S(R.string.google_find_hub_network_tag), "Google", 90, S(R.string.find_hub_frame_eddystone_0x).format(d.u8(0)), Sym.Sell)
        }
    }

    private val UrlSchemes = arrayOf("http://www.", "https://www.", "http://", "https://")
    private val UrlEnc = arrayOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    private fun eddystoneUrl(d: ByteArray): String? {
        if (d.size < 4 || d.u8(2) > 3) return null
        val sb = StringBuilder(UrlSchemes[d.u8(2)])
        for (i in 3 until d.size) {
            val c = d.u8(i)
            if (c < UrlEnc.size) sb.append(UrlEnc[c]) else if (c in 33..126) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun fastPair(ctx: Ctx, d: ByteArray) {
        if (d.size == 3) {
            ctx.detail("Fast Pair", S(R.string.model_discoverable).format((d.u8(0) shl 16) or (d.u8(1) shl 8) or d.u8(2)))
            ctx.add(BleKind.Audio, S(R.string.google_fast_pair_accessory), "Google", 66, S(R.string.fast_pair_service_pairing_mode), Sym.Headphones)
        } else {
            ctx.detail("Fast Pair", S(R.string.non_discoverable_mode_bytes, d.size))
            ctx.add(BleKind.Audio, S(R.string.google_fast_pair_accessory), "Google", 60, S(R.string.fast_pair_service), Sym.Headphones)
        }
    }

    private val XiaomiProducts = mapOf(
        0x01AA to S(R.string.xiaomi_thermometer_lywsdcgq), 0x045B to S(R.string.xiaomi_thermometer_lywsd02), 0x055B to S(R.string.xiaomi_thermometer_lywsd03mmc),
        0x0347 to S(R.string.qingping_thermometer_cgg1), 0x06D3 to S(R.string.xiaomi_thermometer_mho_c303), 0x0098 to S(R.string.xiaomi_plant_sensor_flower_care),
    )

    private fun xiaomi(ctx: Ctx, d: ByteArray) {
        if (d.size >= 4) {
            val product = d.u16le(2)
            val label = XiaomiProducts[product]
            ctx.detail(S(R.string.xiaomi_product_mibeacon), "0x%04X".format(product))
            if (label != null) {
                ctx.add(BleKind.Home, label, "Xiaomi", 92, S(R.string.mibeacon_frame_product_0x).format(product), Sym.Thermostat)
                return
            }
        }
        ctx.add(BleKind.Home, S(R.string.xiaomi_mi_home_device), "Xiaomi", 58, S(R.string.xiaomi_service_0xfe95), Sym.Sensors)
    }

    // ---- Appearance ------------------------------------------------------------------------------------

    private class App(val kind: BleKind, val label: String, val icon: String?)

    private fun appearance(ctx: Ctx, v: Int, source: String) {
        val cat = v shr 6
        val sub = v and 0x3F
        fun a(kind: BleKind, label: String, icon: String? = null) = App(kind, label, icon)
        val r: App? = when (cat) {
            1 -> a(BleKind.Phone, S(R.string.phone), Sym.Smartphone)
            2 -> when (sub) {
                7 -> a(BleKind.Phone, S(R.string.tablet), Sym.Tablet)
                6 -> a(BleKind.Watch, S(R.string.wrist_worn_computer), Sym.Watch)
                else -> a(BleKind.Computer, S(R.string.computer), Sym.Laptop)
            }
            3 -> a(BleKind.Watch, if (sub == 2) S(R.string.smartwatch) else if (sub == 1) S(R.string.sports_watch) else S(R.string.watch), Sym.Watch)
            4 -> a(BleKind.Home, S(R.string.clock), Sym.Schedule)
            5 -> a(BleKind.Media, S(R.string.display), Sym.Tv)
            6 -> a(BleKind.Accessory, S(R.string.remote_control), Sym.Devices)
            7 -> a(BleKind.Accessory, S(R.string.smart_glasses), Sym.Devices)
            8 -> a(BleKind.Tag, "Tag", Sym.Sell)
            9 -> a(BleKind.Tag, S(R.string.smart_key_fob), Sym.Sell)
            10 -> a(BleKind.Media, S(R.string.media_player), Sym.MusicNote)
            11 -> a(BleKind.Accessory, S(R.string.barcode_scanner), Sym.Devices)
            12 -> a(BleKind.Health, S(R.string.thermometer), Sym.Thermostat)
            13 -> a(BleKind.Health, if (sub == 1) S(R.string.heart_rate_belt) else S(R.string.heart_rate_sensor), Sym.Favorite)
            14 -> a(BleKind.Health, S(R.string.blood_pressure_monitor), Sym.Favorite)
            15 -> when (sub) {
                1 -> a(BleKind.Accessory, S(R.string.keyboard), Sym.Keyboard)
                2 -> a(BleKind.Accessory, S(R.string.mouse), Sym.Mouse)
                3 -> a(BleKind.Accessory, "Joystick", Sym.Gamepad)
                4 -> a(BleKind.Accessory, S(R.string.gamepad), Sym.Gamepad)
                5 -> a(BleKind.Accessory, S(R.string.graphics_tablet), Sym.Edit)
                7 -> a(BleKind.Accessory, S(R.string.digital_pen), Sym.Edit)
                8 -> a(BleKind.Accessory, S(R.string.barcode_scanner), Sym.Devices)
                else -> a(BleKind.Accessory, S(R.string.hid_device), Sym.Keyboard)
            }
            16 -> a(BleKind.Health, S(R.string.glucose_meter), Sym.Favorite)
            17 -> a(BleKind.Health, S(R.string.running_sensor), Sym.Favorite)
            18 -> a(BleKind.Health, S(R.string.cycling_sensor), Sym.PedalBike)
            19 -> a(BleKind.Home, S(R.string.control_device), Sym.Hub)
            20 -> a(BleKind.Home, S(R.string.access_point_gateway), Sym.Router)
            21 -> a(BleKind.Home, S(R.string.sensor), Sym.Sensors)
            22 -> a(BleKind.Home, S(R.string.light_fixture), Sym.Lightbulb)
            23 -> a(BleKind.Home, S(R.string.fan), Sym.Hub)
            24 -> a(BleKind.Home, if (sub == 1) "Thermostat" else S(R.string.heating_ventilation), Sym.Thermostat)
            25 -> a(BleKind.Home, S(R.string.air_conditioning), Sym.Thermostat)
            26 -> a(BleKind.Home, S(R.string.humidifier), Sym.Hub)
            27 -> a(BleKind.Home, S(R.string.heater), Sym.Thermostat)
            28 -> a(BleKind.Home, S(R.string.access_control), Sym.Lock)
            29 -> a(BleKind.Home, S(R.string.motorised_device_garage_gate), Sym.Hub)
            30 -> a(BleKind.Home, S(R.string.power_supply_plug), Sym.Bolt)
            31 -> a(BleKind.Home, S(R.string.light_source), Sym.Lightbulb)
            32 -> a(BleKind.Home, S(R.string.blind_shutter), Sym.Hub)
            33 -> a(BleKind.Audio, S(R.string.speaker_audio_receiver), Sym.Speaker)
            34 -> a(BleKind.Audio, S(R.string.audio_source), Sym.MusicNote)
            35 -> a(BleKind.Vehicle, when (sub) { 1 -> S(R.string.car); 2 -> S(R.string.motorbike); 3 -> S(R.string.e_bike); else -> S(R.string.motor_vehicle) }, Sym.DirectionsCar)
            36 -> a(BleKind.Home, S(R.string.home_appliance), Sym.Hub)
            37 -> a(BleKind.Audio, when (sub) { 1 -> S(R.string.earbuds); 2 -> S(R.string.headset); 3 -> S(R.string.headphones); 4 -> S(R.string.neckband_audio); else -> S(R.string.portable_audio) }, Sym.Headphones)
            38 -> a(BleKind.Vehicle, S(R.string.aircraft_drone), Sym.Flight)
            39 -> a(BleKind.Media, S(R.string.audio_video_equipment), Sym.Tv)
            40 -> a(BleKind.Media, when (sub) { 1 -> S(R.string.television); 2 -> S(R.string.monitor); 3 -> S(R.string.projector); else -> S(R.string.display_equipment) }, Sym.Tv)
            41 -> a(BleKind.Audio, S(R.string.hearing_aid), Sym.Headphones)
            42 -> a(BleKind.Media, when (sub) { 1 -> S(R.string.home_console); 2 -> S(R.string.handheld_console); else -> S(R.string.game_console) }, Sym.Gamepad)
            43 -> a(BleKind.Media, S(R.string.digital_signage), Sym.Tv)
            49 -> a(BleKind.Health, S(R.string.pulse_oximeter), Sym.Favorite)
            50 -> a(BleKind.Health, S(R.string.bathroom_scale), Sym.Scale)
            51 -> a(BleKind.Vehicle, S(R.string.personal_mobility), Sym.ElectricScooter)
            52 -> a(BleKind.Health, S(R.string.continuous_glucose_monitor), Sym.Favorite)
            53 -> a(BleKind.Health, S(R.string.insulin_pump), Sym.Favorite)
            54 -> a(BleKind.Health, S(R.string.medication_delivery_device), Sym.Favorite)
            55 -> a(BleKind.Health, S(R.string.spirometer), Sym.Favorite)
            81 -> a(BleKind.Watch, S(R.string.gps_sports_device), Sym.Watch)
            else -> null
        }
        if (r != null) ctx.add(r.kind, r.label, null, 84, S(R.string.declared_appearance_0x).format(source, v), r.icon)
    }

    private fun classOfDevice(ctx: Ctx, v: Int) {
        val major = (v shr 8) and 0x1F
        val minor = (v shr 2) and 0x3F
        val r: Triple<BleKind, String, String>? = when (major) {
            1 -> when (minor) {
                7 -> Triple(BleKind.Phone, S(R.string.tablet), Sym.Tablet)
                else -> Triple(BleKind.Computer, S(R.string.computer), Sym.Laptop)
            }
            2 -> Triple(BleKind.Phone, S(R.string.phone), Sym.Smartphone)
            3 -> Triple(BleKind.Home, S(R.string.network_access_point), Sym.Router)
            4 -> when (minor) {
                1, 2, 6 -> Triple(BleKind.Audio, S(R.string.headset_earpiece), Sym.Headphones)
                5, 7, 10 -> Triple(BleKind.Audio, S(R.string.speaker), Sym.Speaker)
                8 -> Triple(BleKind.Audio, S(R.string.car_stereo), Sym.Speaker)
                9, 14, 15 -> Triple(BleKind.Media, S(R.string.display_set_top_box), Sym.Tv)
                else -> Triple(BleKind.Media, S(R.string.audio_video), Sym.Tv)
            }
            5 -> when {
                (v and 0x40) != 0 && (v and 0x80) == 0 -> Triple(BleKind.Accessory, S(R.string.keyboard), Sym.Keyboard)
                (v and 0x80) != 0 && (v and 0x40) == 0 -> Triple(BleKind.Accessory, S(R.string.mouse_pointer), Sym.Mouse)
                (v and 0x0F) in 1..2 -> Triple(BleKind.Accessory, S(R.string.gamepad), Sym.Gamepad)
                else -> Triple(BleKind.Accessory, S(R.string.peripheral), Sym.Keyboard)
            }
            6 -> when {
                (v and 0x80) != 0 -> Triple(BleKind.Accessory, S(R.string.printer), Sym.Print)
                (v and 0x40) != 0 -> Triple(BleKind.Accessory, "Scanner", Sym.Print)
                (v and 0x20) != 0 -> Triple(BleKind.Accessory, S(R.string.camera), Sym.Videocam)
                else -> Triple(BleKind.Media, S(R.string.imaging_display), Sym.Tv)
            }
            7 -> when (minor) {
                1 -> Triple(BleKind.Watch, S(R.string.watch), Sym.Watch)
                5 -> Triple(BleKind.Accessory, S(R.string.smart_glasses), Sym.Devices)
                else -> Triple(BleKind.Watch, S(R.string.wearable), Sym.Watch)
            }
            8 -> Triple(BleKind.Accessory, S(R.string.smart_toy), Sym.SmartToy)
            9 -> Triple(BleKind.Health, S(R.string.health_device), Sym.Favorite)
            else -> null
        }
        if (r != null) ctx.add(r.first, r.second, null, 80, S(R.string.bluetooth_device_class_0x).format(v), r.third)
    }

    // ---- Services ------------------------------------------------------------------------------------------

    private class Hint(val kind: BleKind, val label: String?, val score: Int, val maker: String? = null, val icon: String? = null)

    private val MakerHints: Map<Int, Hint> = mapOf(
        0x009E to Hint(BleKind.Audio, S(R.string.bose_product), 75, icon = Sym.Headphones),
        0x0057 to Hint(BleKind.Audio, S(R.string.harman_product_jbl_akg_harman_kardon), 66, icon = Sym.Speaker),
        0x0067 to Hint(BleKind.Audio, S(R.string.jabra_product), 78, icon = Sym.Headphones),
        0x0055 to Hint(BleKind.Audio, S(R.string.poly_plantronics_product), 78, icon = Sym.Headphones),
        0x05A7 to Hint(BleKind.Audio, S(R.string.sonos_speaker), 86, icon = Sym.Speaker),
        0x0087 to Hint(BleKind.Watch, S(R.string.garmin_device_watch_sensor), 68, icon = Sym.Watch),
        0x006B to Hint(BleKind.Health, S(R.string.polar_sensor), 72, icon = Sym.Favorite),
        0x009F to Hint(BleKind.Watch, S(R.string.suunto_watch), 76, icon = Sym.Watch),
        0x0157 to Hint(BleKind.Watch, S(R.string.amazfit_or_mi_band_band_watch), 80, icon = Sym.Watch),
        0x0078 to Hint(BleKind.Watch, S(R.string.nike_device), 55, icon = Sym.Watch),
        0x01DA to Hint(BleKind.Accessory, S(R.string.logitech_peripheral), 74, icon = Sym.Mouse),
        0x0075 to Hint(BleKind.Phone, S(R.string.samsung_device_galaxy), 42, icon = Sym.Smartphone),
        0x02E5 to Hint(BleKind.Home, S(R.string.esp32_module), 58, icon = Sym.Memory),
        0x0822 to Hint(BleKind.Home, S(R.string.adafruit_board), 70, icon = Sym.Memory),
        0x0171 to Hint(BleKind.Home, S(R.string.amazon_device_echo_fire_tv), 52, icon = Sym.Speaker),
        0x00C4 to Hint(BleKind.Media, S(R.string.lg_device_tv_earbuds), 44, icon = Sym.Tv),
        0x012D to Hint(BleKind.Audio, S(R.string.sony_device), 44, icon = Sym.Headphones),
        0x027D to Hint(BleKind.Phone, S(R.string.huawei_device), 44, icon = Sym.Smartphone),
        0x0002 to Hint(BleKind.Computer, S(R.string.pc_intel_bluetooth), 46, icon = Sym.Laptop),
        0x058E to Hint(BleKind.Accessory, S(R.string.meta_device_quest_glasses), 56),
        0x01AB to Hint(BleKind.Accessory, S(R.string.meta_device_quest_glasses), 56),
    )

    private val Svc16: Map<Int, Hint> = mapOf(
        0x180D to Hint(BleKind.Health, S(R.string.heart_rate_sensor), 88, icon = Sym.Favorite),
        0x1814 to Hint(BleKind.Health, S(R.string.running_sensor), 82, icon = Sym.Favorite),
        0x1816 to Hint(BleKind.Health, S(R.string.cycling_sensor_speed_cadence), 85, icon = Sym.PedalBike),
        0x1818 to Hint(BleKind.Health, S(R.string.cycling_power_sensor), 88, icon = Sym.PedalBike),
        0x1826 to Hint(BleKind.Health, S(R.string.fitness_machine_ftms), 86, icon = Sym.PedalBike),
        0x181D to Hint(BleKind.Health, S(R.string.bathroom_scale), 88, icon = Sym.Scale),
        0x181B to Hint(BleKind.Health, S(R.string.body_composition_scale), 86, icon = Sym.Scale),
        0x1810 to Hint(BleKind.Health, S(R.string.blood_pressure_monitor), 90, icon = Sym.Favorite),
        0x1808 to Hint(BleKind.Health, S(R.string.glucose_meter), 90, icon = Sym.Favorite),
        0x181F to Hint(BleKind.Health, S(R.string.continuous_glucose_monitor), 90, icon = Sym.Favorite),
        0x1809 to Hint(BleKind.Health, S(R.string.medical_thermometer), 88, icon = Sym.Thermostat),
        0x1822 to Hint(BleKind.Health, S(R.string.pulse_oximeter), 90, icon = Sym.Favorite),
        0x1819 to Hint(BleKind.Health, S(R.string.gps_bike_computer_location_and_navigation), 50, icon = Sym.PedalBike),
        0x181A to Hint(BleKind.Home, S(R.string.environment_sensor_temperature_humidity), 86, icon = Sym.Thermostat),
        0x1812 to Hint(BleKind.Accessory, S(R.string.hid_device_keyboard_mouse_gamepad), 78, icon = Sym.Keyboard),
        0x1803 to Hint(BleKind.Tag, S(R.string.tracker_link_loss_alert), 56, icon = Sym.Sell),
        0x1802 to Hint(BleKind.Tag, S(R.string.tracker_immediate_alert), 46, icon = Sym.Sell),
        0x1811 to Hint(BleKind.Watch, S(R.string.watch_band_notifications), 52, icon = Sym.Watch),
        0x1827 to Hint(BleKind.Home, S(R.string.bluetooth_mesh_node), 90, icon = Sym.Hub),
        0x1828 to Hint(BleKind.Home, S(R.string.bluetooth_mesh_node_proxy), 90, icon = Sym.Hub),
        0x1852 to Hint(BleKind.Audio, S(R.string.auracast_broadcast), 93, icon = Sym.Speaker),
        0x1851 to Hint(BleKind.Audio, S(R.string.le_audio_broadcast), 86, icon = Sym.Speaker),
        0x1850 to Hint(BleKind.Audio, S(R.string.le_audio_device), 70, icon = Sym.Headphones),
        0x184E to Hint(BleKind.Audio, S(R.string.le_audio_device), 72, icon = Sym.Headphones),
        0x184F to Hint(BleKind.Audio, S(R.string.auracast_receiver), 72, icon = Sym.Headphones),
        0x1853 to Hint(BleKind.Audio, S(R.string.le_audio_device), 65, icon = Sym.Headphones),
        0x1854 to Hint(BleKind.Audio, S(R.string.hearing_aid), 88, icon = Sym.Headphones),
        0x1844 to Hint(BleKind.Audio, S(R.string.le_audio_device_volume), 60, icon = Sym.Headphones),
        0x1846 to Hint(BleKind.Audio, S(R.string.earbuds_coordinated_set), 62, icon = Sym.Headphones),
        0x1843 to Hint(BleKind.Audio, S(R.string.le_audio_device), 60, icon = Sym.Headphones),
        0x1820 to Hint(BleKind.Home, S(R.string.ipv6_over_ble_node_ipsp), 70, icon = Sym.Hub),
        0x1821 to Hint(BleKind.Tag, S(R.string.indoor_positioning_tag), 56, icon = Sym.Sell),
        0x1815 to Hint(BleKind.Home, S(R.string.automation_inputs_outputs), 66, icon = Sym.Hub),
        0x1823 to Hint(BleKind.Home, S(R.string.http_proxy), 60, icon = Sym.Hub),
        0xFEED to Hint(BleKind.Tag, "Tile", 96, "Tile", Sym.Sell),
        0xFEEC to Hint(BleKind.Tag, "Tile", 96, "Tile", Sym.Sell),
        0xFD84 to Hint(BleKind.Tag, "Tile", 96, "Tile", Sym.Sell),
        0xFD5A to Hint(BleKind.Tag, "Galaxy SmartTag", 92, "Samsung", Sym.Sell),
        0xFD69 to Hint(BleKind.Tag, S(R.string.samsung_smartthings_find_tag), 76, "Samsung", Sym.Sell),
        0xFE33 to Hint(BleKind.Tag, "Chipolo", 90, "Chipolo", Sym.Sell),
        0xFE9A to Hint(BleKind.Tag, S(R.string.estimote_beacon), 92, "Estimote", Sym.Sell),
        0xFEAA to Hint(BleKind.Tag, S(R.string.eddystone_beacon), 80, icon = Sym.Sell),
        0xFD6F to Hint(BleKind.Phone, S(R.string.phone_exposure_notification), 80, icon = Sym.Smartphone),
        0xFE2C to Hint(BleKind.Audio, S(R.string.google_fast_pair_accessory), 60, "Google", Sym.Headphones),
        0xFE07 to Hint(BleKind.Audio, S(R.string.sonos_speaker), 92, "Sonos", Sym.Speaker),
        0xFEBE to Hint(BleKind.Audio, S(R.string.bose_product), 86, "Bose", Sym.Headphones),
        0xFEE0 to Hint(BleKind.Watch, S(R.string.amazfit_or_mi_band_band_watch), 86, "Huami", Sym.Watch),
        0xFEE1 to Hint(BleKind.Watch, S(R.string.amazfit_or_mi_band_band_watch), 80, "Huami", Sym.Watch),
        0xFE95 to Hint(BleKind.Home, S(R.string.xiaomi_mi_home_device), 58, "Xiaomi", Sym.Sensors),
        0xFE0F to Hint(BleKind.Home, S(R.string.philips_hue_lighting), 86, "Philips", Sym.Lightbulb),
        0xFDCD to Hint(BleKind.Home, S(R.string.qingping_sensor), 86, "Qingping", Sym.Thermostat),
        0xFE59 to Hint(BleKind.Home, S(R.string.nordic_module_dfu_update), 56, "Nordic Semiconductor", Sym.Memory),
        0xFEB7 to Hint(BleKind.Accessory, S(R.string.meta_device_quest_glasses), 60, "Meta", Sym.Devices),
        0xFEB8 to Hint(BleKind.Accessory, S(R.string.meta_device_quest_glasses), 60, "Meta", Sym.Devices),
        0xFEE7 to Hint(BleKind.Home, S(R.string.wechat_connected_device_tencent), 50, "Tencent", Sym.Hub),
        0xFFE0 to Hint(BleKind.Home, S(R.string.generic_ble_serial_module_hm_10), 44, icon = Sym.Memory),
        0xFFF0 to Hint(BleKind.Home, S(R.string.generic_ble_serial_module), 40, icon = Sym.Memory),
    )

    private class Hint128(val prefix: String?, val suffix: String?, val hint: Hint) {
        val kind get() = hint.kind
        val label get() = hint.label
        val score get() = hint.score
        val maker get() = hint.maker
        val icon get() = hint.icon
        fun matches(u: String) = (prefix == null || u.startsWith(prefix)) && (suffix == null || u.endsWith(suffix))
    }

    private val Svc128: List<Hint128> = listOf(
        Hint128("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", null, Hint(BleKind.Home, S(R.string.serial_module_nordic_uart), 58, "Nordic Semiconductor", Sym.Memory)),
        Hint128("00000211-B2D1-43F0-9B88-960CEBF8B91E", null, Hint(BleKind.Vehicle, "Tesla", 94, "Tesla", Sym.DirectionsCar)),
        Hint128("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD", null, Hint(BleKind.Home, S(R.string.meshtastic_node_lora), 92, "Meshtastic", Sym.Router)),
        Hint128("7905F431-B5CE-4E99-A40F-4B1E122D00D0", null, Hint(BleKind.Watch, S(R.string.band_watch_ios_notifications), 66, icon = Sym.Watch)),
        Hint128("ADABFB00-6E7D-4601-BDA2-BFFAA68956BA", null, Hint(BleKind.Watch, "Fitbit", 94, "Fitbit", Sym.Watch)),
        Hint128(null, "-667B-11E3-949A-0800200C9A66", Hint(BleKind.Watch, S(R.string.garmin_device), 90, "Garmin", Sym.Watch)),
        Hint128("932C32BD-0000-47A2-835A-A8D455B859DD", null, Hint(BleKind.Home, S(R.string.philips_hue_lighting), 92, "Philips", Sym.Lightbulb)),
        Hint128("00001623-1212-EFDE-1623-785FEABCD123", null, Hint(BleKind.Accessory, S(R.string.lego_hub_powered_up), 94, "LEGO", Sym.SmartToy)),
        Hint128("98ED0001-A541-11E4-B6A0-0002A5D5C51B", null, Hint(BleKind.Health, S(R.string.oura_ring), 92, "Oura", Sym.Favorite)),
        Hint128("61080001-8D6D-82B8-614A-1C8CB0F8DCC6", null, Hint(BleKind.Health, S(R.string.whoop_band), 90, "Whoop", Sym.Favorite)),
        Hint128("00000001-19CA-4651-86E5-FA29DCDD09D1", null, Hint(BleKind.Accessory, S(R.string.zwift_controller), 88, "Zwift", Sym.Gamepad)),
    )

    private val TeslaName = Regex("""^S[0-9a-f]{16}C$""")

    // ---- Names ------------------------------------------------------------------------------------------------

    private class NameRule(val re: Regex, val kind: BleKind, val label: String, val maker: String?, val score: Int, val icon: String?)

    private fun n(p: String, kind: BleKind, label: String, maker: String? = null, score: Int = 80, icon: String? = null) =
        NameRule(Regex(p, RegexOption.IGNORE_CASE), kind, label, maker, score, icon)

    private val NameRules: List<NameRule> by lazy {
        val A = BleKind.Audio; val W = BleKind.Watch; val T = BleKind.Tag; val P = BleKind.Phone; val C = BleKind.Computer
        val M = BleKind.Media; val X = BleKind.Accessory; val H = BleKind.Health; val O = BleKind.Home; val V = BleKind.Vehicle
        listOf(
            // Audio
            n("""airpods?\s*max""", A, "AirPods Max", "Apple", 93, Sym.Headphones),
            n("""airpods?\s*pro""", A, "AirPods Pro", "Apple", 93, Sym.Headphones),
            n("""airpods?""", A, "AirPods", "Apple", 92, Sym.Headphones),
            n("""homepod""", A, "HomePod", "Apple", 92, Sym.Speaker),
            n("""beats\s*(x|solo|studio|flex|fit|pill)|powerbeats|\bbeats\b""", A, S(R.string.beats_headphones_earbuds), "Apple", 88, Sym.Headphones),
            n("""pixel\s*buds""", A, "Pixel Buds", "Google", 93, Sym.Headphones),
            n("""galaxy\s*buds|\bsm-r\d{3}""", A, "Galaxy Buds", "Samsung", 92, Sym.Headphones),
            n("""\b(le )?w[hf]-\w+|linkbuds|\bsony\s*(mdr|ult|srs)|\bsrs-\w+""", A, S(R.string.sony_audio), "Sony", 88, Sym.Headphones),
            n("""jbl\s*(flip|charge|go|clip|xtreme|boombox|pulse|partybox|link|authentics|horizon|encore)""", A, S(R.string.jbl_speaker), "Harman", 92, Sym.Speaker),
            n("""\bjbl\b|\bharman\b|\bakg\b""", A, S(R.string.jbl_harman_audio), "Harman", 86, Sym.Headphones),
            n("""soundlink|bose\s*(revolve|micro|portable|home)""", A, S(R.string.bose_speaker), "Bose", 90, Sym.Speaker),
            n("""\bbose\b|\bqc\s?\d{2}|quietcomfort|\bnc\s?700""", A, S(R.string.bose_headphones_earbuds), "Bose", 88, Sym.Headphones),
            n("""sennheiser|momentum|\bhd\s?\d{3}\b|cx\s?(plus|true)""", A, S(R.string.sennheiser_audio), "Sennheiser", 86, Sym.Headphones),
            n("""jabra|elite\s*\d""", A, S(R.string.jabra_audio), "Jabra", 88, Sym.Headphones),
            n("""soundcore|anker|liberty\s*\d|life\s*(q|p|a)\d""", A, S(R.string.anker_soundcore_audio), "Anker", 86, Sym.Headphones),
            n("""sonos|\broam\b\s*\d?""", A, S(R.string.sonos_speaker), "Sonos", 88, Sym.Speaker),
            n("""marshall|emberton|stanmore|acton|woburn|willen""", A, S(R.string.marshall_speaker), "Marshall", 88, Sym.Speaker),
            n("""\bue\s*(boom|megaboom|wonderboom|hyperboom)|ultimate\s*ears|\b(mega|wonder)boom""", A, S(R.string.ultimate_ears_speaker), "Ultimate Ears", 90, Sym.Speaker),
            n("""bang.{0,3}olufsen|beoplay|beosound|\bb&o\b""", A, S(R.string.bang_olufsen_audio), "Bang & Olufsen", 90, Sym.Speaker),
            n("""nest\s*(mini|audio)|google\s*home""", A, S(R.string.google_nest_speaker), "Google", 90, Sym.Speaker),
            n("""echo\s*buds""", A, "Amazon Echo Buds", "Amazon", 90, Sym.Headphones),
            n("""echo\s*(dot|show|studio|pop|spot|flex|input)?\b|\balexa\b""", A, S(R.string.amazon_echo_speaker), "Amazon", 84, Sym.Speaker),
            n("""redmi\s*buds|xiaomi\s*buds|mi\s*(true\s*wireless|air)|airdots|mi\s*neckband""", A, S(R.string.xiaomi_earbuds), "Xiaomi", 88, Sym.Headphones),
            n("""oneplus\s*buds|nord\s*buds""", A, "OnePlus Buds", "OnePlus", 90, Sym.Headphones),
            n("""oppo\s*enco|\benco\s*(x|w|air|buds|free)""", A, S(R.string.oppo_earbuds), "OPPO", 88, Sym.Headphones),
            n("""freebuds|freelace|huawei\s*(fb|earphone)""", A, S(R.string.huawei_earbuds), "Huawei", 90, Sym.Headphones),
            n("""realme\s*buds""", A, S(R.string.realme_earbuds), "realme", 88, Sym.Headphones),
            n("""nothing\s*ear|\bear\s*\(\d\)|cmf\s*buds""", A, S(R.string.nothing_earbuds), "Nothing", 90, Sym.Headphones),
            n("""skullcandy|crusher|indy\s*evo|sesh""", A, S(R.string.skullcandy_audio), "Skullcandy", 86, Sym.Headphones),
            n("""soundbar|sound\s*bar|barre\s*de\s*son""", A, S(R.string.soundbar), null, 86, Sym.Speaker),
            n("""tozo|soundpeats|edifier|haylou|\bqcy\b|lenovo\s*(lp|thinkplus)|\btws\b|earbuds?|earphones?|écouteurs?|headphones?|casque|headset|\bbuds\b|\bpods\b|\bneckband\b""", A, S(R.string.earbuds_headphones), null, 72, Sym.Headphones),
            n("""speaker|enceinte|boombox|bt\s*speaker|mini\s*speaker|\bboom\b|haut-?parleur""", A, S(R.string.bluetooth_speaker), null, 76, Sym.Speaker),
            n("""car\s*(kit|audio|stereo)|carkit|hands-?free|mains\s*libres|autoradio|pioneer|kenwood|alpine|\bjvc\b""", A, S(R.string.hands_free_kit_car_stereo), null, 70, Sym.Speaker),

            // Watches and bands
            n("""apple\s*watch""", W, "Apple Watch", "Apple", 93, Sym.Watch),
            n("""galaxy\s*(watch|fit|ring)|gear\s*(s\d|fit|sport)""", W, "Samsung Galaxy Watch", "Samsung", 93, Sym.Watch),
            n("""pixel\s*watch""", W, "Pixel Watch", "Google", 93, Sym.Watch),
            n("""fitbit|versa\s*\d|inspire\s*\d|\bionic\b""", W, "Fitbit", "Fitbit", 88, Sym.Watch),
            n("""amazfit|mi\s*(smart\s*)?band|mi\s*watch|xiaomi\s*(smart\s*)?(band|watch)|redmi\s*(watch|band)|\bgts\s?\d|\bgtr\s?\d|\bbip\b|zepp""", W, "Amazfit / Mi Band", "Xiaomi", 90, Sym.Watch),
            n("""garmin|fenix|forerunner|\bvenu\b|vivo(active|smart|fit|move)|instinct|\bepix\b|approach\s*[sg]\d|\blily\b|enduro|tactix|descent""", W, S(R.string.garmin_watch), "Garmin", 88, Sym.Watch),
            n("""\bedge\s?\d{3,4}\b""", H, S(R.string.garmin_edge_bike_computer), "Garmin", 84, Sym.PedalBike),
            n("""huawei\s*(watch|band)|honor\s*(watch|band)|\bband\s?\d\b""", W, S(R.string.huawei_band_watch), "Huawei", 84, Sym.Watch),
            n("""oura""", H, S(R.string.oura_ring), "Oura", 92, Sym.Favorite),
            n("""whoop""", H, S(R.string.whoop_band), "Whoop", 92, Sym.Favorite),
            n("""suunto|coros|ticwatch|mobvoi|fossil|casio|g-?shock|\bgbd|\bgbx|\bwsd|pebble|wear\s*os|polar\s*(vantage|grit|ignite|pacer|unite)""", W, S(R.string.smartwatch), null, 82, Sym.Watch),
            n("""smart\s*watch|smartwatch|\bwatch\b|montre|\bband\b|bracelet|\bsmart\s*band""", W, S(R.string.watch_band), null, 72, Sym.Watch),

            // Phones and tablets
            n("""iphone""", P, "iPhone", "Apple", 93, Sym.Smartphone),
            n("""ipad""", P, "iPad", "Apple", 93, Sym.Tablet),
            n("""galaxy\s*tab|mediapad|matepad|\btab\s?[a-z]?\d|tablet|tablette""", P, S(R.string.tablet), null, 82, Sym.Tablet),
            n("""galaxy\s*(s|a|z|note|m|f|xcover)\s?\d|\bsm-[gnsamfej]\d{3}""", P, "Samsung Galaxy", "Samsung", 90, Sym.Smartphone),
            n("""pixel\s*\d|\bpixel\b(?!\s*(buds|watch))""", P, "Google Pixel", "Google", 88, Sym.Smartphone),
            n("""oneplus(?!\s*buds)|\bop\d{4}\b|nord\s*(ce|n)?\s*\d""", P, "OnePlus", "OnePlus", 86, Sym.Smartphone),
            n("""redmi(?!\s*(buds|watch|band))|\bpoco\b|xiaomi\s*\d{1,2}|\bmi\s?\d{1,2}\b|mi\s*(note|max|mix|play)""", P, "Xiaomi / Redmi / POCO", "Xiaomi", 82, Sym.Smartphone),
            n("""huawei(?!\s*(watch|band|fb|earphone|freebuds))|honor\s*\d|\bnova\s?\d""", P, "Huawei / Honor", "Huawei", 82, Sym.Smartphone),
            n("""\boppo\b|reno\s?\d|find\s*x|realme(?!\s*buds)|vivo\s*[xyvs]\d|iqoo|motorola|moto\s*[gex]\s?\d|xperia|nokia\s*[a-z]?\d|zenfone|rog\s*phone|fairphone|nothing\s*phone""", P, S(R.string.android_phone), null, 80, Sym.Smartphone),
            n("""android|smartphone|\bphone\b|téléphone|telephone""", P, S(R.string.phone), null, 70, Sym.Smartphone),

            // Computers
            n("""macbook|imac|mac\s?mini|mac\s?studio|mac\s?pro""", C, "Mac", "Apple", 93, Sym.Laptop),
            n("""^(desktop|laptop)-[a-z0-9]{5,8}$""", C, S(R.string.windows_pc), "Microsoft", 90, Sym.Laptop),
            n("""thinkpad|thinkcentre|ideapad|latitude|elitebook|probook|zenbook|vivobook|inspiron|\bsurface\b|chromebook|\bportable\b|\blaptop\b|notebook|ordinateur|\bpc\b|desktop""", C, S(R.string.computer), null, 80, Sym.Laptop),
            n("""raspberry|raspberrypi|\brpi\b|jetson|arduino|esp-?32|esp8266|nrf5\d|nrf\d{4}|micro:?bit|\bpico\b|stm32|bluefruit|adafruit|dev\s*board|devkit""", O, S(R.string.development_board), null, 76, Sym.Memory),
            n("""flipper""", O, "Flipper Zero", "Flipper Devices", 93, Sym.Memory),
            n("""meshtastic|^mesh_[0-9a-f]{4}$""", O, S(R.string.meshtastic_node_lora), "Meshtastic", 92, Sym.Router),

            // TV and media
            n("""^\[(tv|av)\]|samsung.*\b(tv|q\d{2}|frame|serif)|the\s*frame|qled|neo\s*qled|crystal\s*uhd""", M, S(R.string.samsung_tv), "Samsung", 90, Sym.Tv),
            n("""\[lg\]|lg\s*webos|webos|lg.*\boled|oled\d{2}[a-z]\d""", M, S(R.string.lg_tv), "LG", 90, Sym.Tv),
            n("""bravia""", M, S(R.string.sony_bravia_tv), "Sony", 92, Sym.Tv),
            n("""chromecast""", M, "Chromecast", "Google", 92, Sym.Cast),
            n("""fire\s*tv|firestick|\baft[a-z]{1,3}\b|fire\s*stick""", M, "Amazon Fire TV", "Amazon", 90, Sym.Tv),
            n("""android\s*tv|google\s*tv|mibox|mi\s*box|nvidia\s*shield|shield\s*(tv|remote)""", M, S(R.string.android_tv_box), null, 82, Sym.Tv),
            n("""nest\s*hub|home\s*hub""", M, S(R.string.google_nest_hub_display), "Google", 84, Sym.Tv),
            n("""apple\s*tv""", M, "Apple TV", "Apple", 93, Sym.Tv),
            n("""\broku\b""", M, "Roku", "Roku", 90, Sym.Tv),
            n("""xbox\s*wireless|xbox\s*elite|xbox\s*adaptive""", X, S(R.string.xbox_controller), "Microsoft", 94, Sym.Gamepad),
            n("""\bxbox\b""", M, S(R.string.xbox_console), "Microsoft", 88, Sym.Gamepad),
            n("""dualsense|dualshock|wireless\s*controller|playstation|\bps[345]\b""", X, S(R.string.playstation_controller), "Sony", 88, Sym.Gamepad),
            n("""pro\s*controller|joy-?con|nintendo|\bswitch\b\s*(pro|lite|oled)?""", X, S(R.string.nintendo_controller_console), "Nintendo", 86, Sym.Gamepad),
            n("""hisense|\btcl\b|vizio|panasonic|sharp\s*aquos|philips\s*(tv|the\s*one)|toshiba.*tv|xiaomi\s*tv|mi\s*tv|vestel|thomson|telefunken|grundig|(^|[\s\-\[])tv([\s\-\]\d]|$)|téléviseur|television""", M, S(R.string.television), null, 74, Sym.Tv),
            n("""projector|projecteur|beamer|xgimi|nebula\s*(capsule|cosmos|mars)""", M, S(R.string.projector), null, 82, Sym.Tv),
            n("""kindle|kobo|remarkable|e-?reader|liseuse""", M, S(R.string.e_reader), null, 82, Sym.Tablet),
            n("""gopro|insta360|action\s*cam|\bosmo\b|\bcamera\b|caméra|webcam|dashcam""", X, S(R.string.camera_2), null, 76, Sym.Videocam),

            // Accessories
            n("""keyboard|clavier|keychron|k380|k780|k480|mx\s*keys|mx\s*mechanical|magic\s*keyboard|\bkeys\b|\bcraft\b""", X, S(R.string.keyboard), null, 88, Sym.Keyboard),
            n("""mouse|souris|mx\s*(master|anywhere|ergo|vertical)|magic\s*mouse|\bm(3[05]|5[57])\d\b|trackball""", X, S(R.string.mouse), null, 88, Sym.Mouse),
            n("""trackpad|touchpad""", X, "Trackpad", null, 88, Sym.Mouse),
            n("""logitech|\blogi\b""", X, S(R.string.logitech_peripheral), "Logitech", 76, Sym.Mouse),
            n("""controller|gamepad|joystick|8bitdo|gamesir|steam\s*controller|steam\s*deck|backbone|stadia""", X, S(R.string.gamepad), null, 88, Sym.Gamepad),
            n("""razer|steelseries|corsair|hyperx|roccat|redragon""", X, S(R.string.gaming_peripheral), null, 72, Sym.Mouse),
            n("""\bpen\b|s[\s-]?pen|pencil|stylus|stylet|wacom""", X, S(R.string.stylus), null, 80, Sym.Edit),
            n("""remote|télécommande|telecommande|magic\s*remote|presenter|clicker|spotlight""", X, S(R.string.remote_control), null, 76, Sym.Devices),
            n("""selfie|shutter""", X, S(R.string.camera_remote), null, 78, Sym.Videocam),
            n("""printer|imprimante|laserjet|deskjet|officejet|hp\s*(envy|smart\s*tank|neverstop)|epson\s*(xp|et|wf|l\d)|\bxp-\d{3}|pixma|canon\s*(ts|mg|g\d|tr)\d|brother\s*(hl|dcp|mfc)|hl-l\d|dcp-|mfc-""", X, S(R.string.printer), null, 88, Sym.Print),
            n("""dymo|phomemo|niimbot|\bd11\b|\bd110\b|\bb21\b|peripage|munbyn|label\s*printer|étiqueteuse|zebra\s*(zd|zq|zt|ql)|pos-?\d|thermal|cat\s*printer""", X, S(R.string.label_thermal_printer), null, 84, Sym.Print),
            n("""barcode|code-?barres?|scan\s*gun|honeywell|socket\s*mobile|netum|eyoyo|inateck|scanner""", X, S(R.string.barcode_scanner), null, 76, Sym.Devices),
            n("""ray-?ban|meta\s*(ray|quest|glasses)|\bquest\b|oculus|\bvr\b|xreal|rokid|vuzix|even\s*g1|bose\s*frames|glasses|lunettes""", X, S(R.string.vr_glasses_headset), null, 80, Sym.Devices),
            n("""sphero|bb-8|ozobot|lego|powered\s*up|mindstorms|cozmo|meccano|ubtech|wowwee|\brobot\b|\btoy\b|jouet""", X, S(R.string.toy_robot), null, 80, Sym.SmartToy),

            // Trackers and beacons
            n("""airtag|find\s*my|findmy""", T, "AirTag", "Apple", 95, Sym.Sell),
            n("""^tile\b|\btile\s*(mate|pro|slim|sticker|tracker)""", T, "Tile", "Tile", 94, Sym.Sell),
            n("""chipolo""", T, "Chipolo", "Chipolo", 94, Sym.Sell),
            n("""smart\s*tag|smarttag""", T, "Galaxy SmartTag", "Samsung", 94, Sym.Sell),
            n("""pebblebee""", T, "Pebblebee", "Pebblebee", 94, Sym.Sell),
            n("""eufy.*track|smarttrack|moto\s*tag|jio\s*tag|find\s*hub|findhub|cube\s*(tracker|pro|shadow)|trackr|nutale|\bnut\s*(mini|find)|orbit\s*(tag|tracker)?|\bitag\b|anti-?lost|antilost|key\s*finder|keyfinder|traceur|tracker""", T, S(R.string.item_tracker), null, 84, Sym.Sell),
            n("""beacon|ibeacon|eddystone|estimote|kontakt|minew|feasycom|holyiot|accent\s*systems|bluecats|sensoro|gimbal|radbeacon|jaalee|blukii|bluvision|wiliot|ble-?tag|asset\s*tag""", T, S(R.string.ble_beacon), null, 80, Sym.Sell),
            n("""\btag\b""", T, "Tag", null, 66, Sym.Sell),

            // Health and fitness
            n("""polar\s*(h\d|verity|oh1)|\bh10\b|\bh9\b|tickr|hrm-?(dual|pro|run|fit|tri)|hr\s*monitor|heart\s*rate|coospo|magene|myzone|ceinture\s*cardio""", H, S(R.string.heart_rate_belt), null, 88, Sym.Favorite),
            n("""wahoo|kickr|elemnt|igpsport|stages|quarq|assioma|favero|4iiii|power\s*meter|cadence|\bspeed\s*sensor|zwift\s*(hub|cog|ride)|tacx|elite\s*(direto|suito)|wattbike|home\s*trainer""", H, S(R.string.bike_sensor_smart_trainer), null, 84, Sym.PedalBike),
            n("""\bpm5\b|concept2|treadmill|tapis|rower|elliptical|ftms|peloton|technogym|kettler|domyos|nordictrack|schwinn|echelon|bowflex|proform|yesoul|merach|fitshow""", H, S(R.string.fitness_machine), null, 80, Sym.PedalBike),
            n("""omron|beurer\s*bm|blood\s*pressure|tensiom|\bbp\d{2,3}\b|\bbpm\b|qardio|ihealth\s*(bp|track)""", H, S(R.string.blood_pressure_monitor), null, 88, Sym.Favorite),
            n("""glucose|glucometer|glucomètre|accu-?chek|\bcontour\b|onetouch|one\s*touch|freestyle|\blibre\b|dexcom|guardian|medtronic""", H, S(R.string.glucose_sensor), null, 88, Sym.Favorite),
            n("""thermometer|thermomètre|\bthermo\b\s*(baby|ear|front)?|forehead""", H, S(R.string.thermometer), null, 76, Sym.Thermostat),
            n("""oximeter|oxymètre|spo2|pulse\s*ox|wellue|checkme|viatom|lepu|o2ring""", H, S(R.string.pulse_oximeter), null, 90, Sym.Favorite),
            n("""mi\s*(body|smart\s*scale)|mibfs|mi\s*scale|xiaomi\s*(body\s*)?scale|renpho|etekcity|yolanda|qn-?scale|tanita|inbody|body\s*(comp|fat|scale)|withings\s*(body|scale)|pèse-?personne|\bscale\b|balance""", H, S(R.string.bathroom_scale), null, 86, Sym.Scale),
            n("""oral-?b|sonicare|kolibree|toothbrush|brosse\s*à\s*dents""", H, S(R.string.smart_toothbrush), null, 90, Sym.Favorite),
            n("""massager|massage|foreo""", H, S(R.string.massage_care_device), null, 76, Sym.Favorite),

            // Home
            n("""philips\s*hue|hue\s*(lamp|bulb|go|play|bridge|white|color|lightstrip)|^hue\b""", O, S(R.string.philips_hue_lighting), "Philips", 92, Sym.Lightbulb),
            n("""lifx""", O, S(R.string.lifx_bulb), "LIFX", 92, Sym.Lightbulb),
            n("""nanoleaf""", O, S(R.string.nanoleaf_lighting), "Nanoleaf", 92, Sym.Lightbulb),
            n("""gvh5\d{3}|govee_?\s?h5\d{3}|govee.*(thermo|hygro)""", O, S(R.string.govee_thermometer), "Govee", 94, Sym.Thermostat),
            n("""govee|ihoment|\bh6\d{3}\b|\bh7\d{3}\b""", O, S(R.string.govee_lighting), "Govee", 88, Sym.Lightbulb),
            n("""yeelight|yeelink""", O, S(R.string.yeelight_lighting), "Yeelight", 90, Sym.Lightbulb),
            n("""sengled|\bwiz\b|tapo\s*l\d|kasa|\bkl\d{3}\b|ikea|tradfri|ledvance|osram|innr|sylvania""", O, S(R.string.smart_bulb), null, 80, Sym.Lightbulb),
            n("""elk-?bled|triones|ledble|melk|bledom|led\s*(strip|controller|lamp|bulb|net)|^led|^lednet|zengge|magic\s*hue|smart\s*(bulb|led|light)|\blamp\b|\blight\b|ampoule|lampe|luminaire|ruban""", O, S(R.string.lighting_led_strip), null, 76, Sym.Lightbulb),
            n("""nuki|august|yale|kwikset|schlage|lockly|tedee|igloohome|ultraloq|smart\s*lock|serrure|verrou|door\s*lock|padlock|cadenas|masterlock|tapplock|sesame|danalock|ttlock|sciener|\block\b""", O, S(R.string.smart_lock), null, 82, Sym.Lock),
            n("""switchbot\s*(meter|hygrometer|thermo)|thermopro|tp3[59]\d|inkbird|ibs-?th|lywsd|mj[_ -]?ht|cgg1|cgdk|atc_|pvvx|ruuvi|hygro|humidity|humidité|thermo-?hygro|airthings|qingping|temp(erature)?\s*(sensor|&)|capteur|\bsensor\b""", O, S(R.string.temperature_humidity_sensor), null, 84, Sym.Thermostat),
            n("""flower\s*care|miflora|mi\s*flora|hhcc|plant\s*sensor|\bflora\b""", O, S(R.string.plant_sensor), "Xiaomi", 88, Sym.Sensors),
            n("""switchbot""", O, "SwitchBot", "SwitchBot", 92, Sym.Hub),
            n("""shelly|sonoff|\bplug\b|prise|relay|relais|smart\s*switch|interrupteur|meross|tapo\s*p\d|aqara|\blumi\b|mijia""", O, S(R.string.smart_plug_switch), null, 82, Sym.Bolt),
            n("""thermostat|tado|netatmo|ecobee|\bhive\b|eve\s*(room|thermo|energy|door|motion|weather|button|aqua|degree)""", O, S(R.string.thermostat_home_sensor), null, 86, Sym.Thermostat),
            n("""nest\s*(thermostat|protect|cam|doorbell)""", O, "Google Nest", "Google", 90, Sym.Thermostat),
            n("""dyson|roomba|roborock|irobot|vacuum|aspirateur|dreame|ecovacs|braava|purifier|humidifier|diffuser|kettle|bouilloire|nespresso|cafetière|coffee|fridge|frigo|oven|\bfour\b|washer|dishwasher|blender|thermomix|airfryer|instant\s*pot|anova|sous-?vide""", O, S(R.string.smart_home_appliance), null, 80, Sym.Hub),
            n("""meater|igrill|weber|traeger|\bbbq\b|barbecue|grill""", O, S(R.string.cooking_thermometer), null, 80, Sym.Thermostat),
            n("""ring\s*(doorbell|alarm|cam|chime|stick)|arlo|eufy|wyze|blink|reolink|tapo\s*c\d|doorbell|sonnette|alarm|alarme|siren|smoke|détecteur|simplisafe|canary|somfy|hikvision|dahua|imou|ezviz""", O, S(R.string.camera_doorbell_alarm), null, 80, Sym.Videocam),
            n("""garage|portail|\bgate\b|blind|store|volet|rideau|curtain|velux|chamberlain|myq|liftmaster|hörmann|hormann|marantec""", O, S(R.string.opener_garage_shutter_gate), null, 78, Sym.Hub),
            n("""hm-?10|hmsoft|bt-?05|jdy-?\d+|at-?09|cc41|ble-?uart|hc-?0[56]|hc-?42|bluno|dsd\s?tech|ble\s?(module|serial)|nordic_?uart|\buart\b""", O, S(R.string.ble_serial_module_hm_10), null, 80, Sym.Memory),
            n("""tuya|smart\s*life|smartlife""", O, S(R.string.tuya_smart_life_device), "Tuya", 78, Sym.Hub),

            // Vehicles
            n("""obd-?ii|obd2|obd\s*link|elm327|veepeak|carista|bluedriver|vlink|v-link|vgate|viecar|konnwei|bafx|icar|carly|thinkdiag|foxwell|fixd|\bobd\b""", V, S(R.string.obd_ii_adapter), null, 92, Sym.DirectionsCar),
            n("""tpms|tpm[ _-]?\d|tire\s*pressure|pression\s*des\s*pneus""", V, S(R.string.tyre_pressure_sensor), null, 86, Sym.DirectionsCar),
            n("""miscooter|mi\s*scooter|m365|ninebot|segway|scooter|trottinette|kickscooter|dott|\bvoi\b|\btier\b|lime""", V, S(R.string.e_scooter), null, 84, Sym.ElectricScooter),
            n("""e-?bike|ebike|vélo|\bbike\b|bafang|kiox|nyon|purion|intuvia|shimano\s*(e[678]|steps)|vanmoof|cowboy|specialized\s*(turbo|mission)|stromer|riese""", V, S(R.string.e_bike), null, 84, Sym.PedalBike),
            n("""drone|tello|mavic|fpv|dji\s*(mini|air|mavic|neo)""", V, "Drone", null, 86, Sym.Flight),
            n("""tesla|\bbmw\b|audi|mercedes|volkswagen|\bvw\b|skoda|peugeot|citro[eë]n|renault|dacia|toyota|honda|nissan|mazda|\bford\b|opel|fiat|volvo|\bkia\b|hyundai|\bjeep\b|land\s*rover|porsche|carplay|android\s*auto""", V, S(R.string.vehicle), null, 74, Sym.DirectionsCar),
        )
    }

    private fun nameRules(ctx: Ctx, raw: String, source: String) {
        val text = raw.replace('_', ' ')
        for (r in NameRules) if (r.re.containsMatchIn(text)) ctx.add(r.kind, r.label, r.maker, r.score, S(R.string.matches_the, source), r.icon)
    }

    /** Vendor names spelled out in the Device Information manufacturer string. */
    private fun gattMaker(s: String): String? = s.trim().removeSuffix("Inc.").removeSuffix("Inc").removeSuffix("Ltd.").removeSuffix("Corporation").trim(' ', ',').takeIf { it.length >= 2 }
}
