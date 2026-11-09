package com.allnetworktools.data.remoteid

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drone Remote ID as broadcast over Bluetooth LE (ASTM F3411, the format the EU and US rules use): service data for
 * UUID 0xFFFA, application code 0x0D, a message counter, then one 25-byte message, or a message pack on Bluetooth 5
 * long range.
 */
object RemoteId {
    const val SERVICE_UUID16 = 0xFFFA
    const val SERVICE_UUID = "0000fffa-0000-1000-8000-00805f9b34fb"
    const val APP_CODE = 0x0D
    private const val MSG = 25

    /** Messages in one service-data payload (what follows the UUID), or empty if it is not Remote ID. */
    fun parseServiceData(d: ByteArray): List<Message> {
        if (d.size < 2 + MSG || (d[0].toInt() and 0xFF) != APP_CODE) return emptyList()
        return parseMessage(d.copyOfRange(2, d.size))
    }

    /**
     * A Wi-Fi beacon's vendor-specific element (id 221), body only: OUI FA:0B:BC, type 0x0D, a counter, then a message
     * pack. The same counter + pack is what Wi-Fi Aware (NAN) carries as service information.
     */
    fun parseWifiVendorIe(body: ByteArray): List<Message> {
        if (body.size < 5 + 3 + MSG) return emptyList()
        if ((body[0].toInt() and 0xFF) != 0xFA || (body[1].toInt() and 0xFF) != 0x0B || (body[2].toInt() and 0xFF) != 0xBC) return emptyList()
        if ((body[3].toInt() and 0xFF) != APP_CODE) return emptyList()
        return parseMessage(body.copyOfRange(5, body.size))
    }

    /** Wi-Fi Aware service information: message counter, then a message pack. */
    fun parseNan(info: ByteArray): List<Message> =
        if (info.size < 1 + 3 + MSG) emptyList() else parseMessage(info.copyOfRange(1, info.size))

    const val NAN_SERVICE = "org.opendroneid.remoteid"

    fun parseMessage(m: ByteArray): List<Message> {
        if (m.size < MSG) return emptyList()
        return when ((m[0].toInt() and 0xFF) shr 4) {
            0x0 -> listOfNotNull(basicId(m))
            0x1 -> listOfNotNull(location(m))
            0x3 -> listOfNotNull(selfId(m))
            0x4 -> listOfNotNull(system(m))
            0x5 -> listOfNotNull(operatorId(m))
            0xF -> pack(m)
            else -> emptyList()
        }
    }

    private fun pack(m: ByteArray): List<Message> {
        if (m.size < 3) return emptyList()
        val size = m[1].toInt() and 0xFF
        val n = m[2].toInt() and 0xFF
        if (size != MSG || n > 9 || m.size < 3 + n * MSG) return emptyList()
        return (0 until n).flatMap { i ->
            val one = m.copyOfRange(3 + i * MSG, 3 + (i + 1) * MSG)
            if ((one[0].toInt() and 0xFF) shr 4 == 0xF) emptyList() else parseMessage(one)
        }
    }

    private fun le(m: ByteArray) = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
    private fun text(m: ByteArray, from: Int, len: Int) =
        String(m, from, len, Charsets.US_ASCII).trimEnd('\u0000', ' ').filter { it.code in 0x20..0x7E }

    /** Altitudes are 0.5 m steps from -1000 m; 0 means unknown. */
    private fun alt(raw: Int): Double? = if (raw == 0) null else raw * 0.5 - 1000.0

    private fun basicId(m: ByteArray): Message.BasicId? {
        val b = m[1].toInt() and 0xFF
        val id = text(m, 2, 20)
        if (id.isEmpty()) return null
        return Message.BasicId(IdType.of(b shr 4), UaType.of(b and 0x0F), id)
    }

    private fun location(m: ByteArray): Message.Location? {
        val bb = le(m)
        val flags = m[1].toInt() and 0xFF
        val dirRaw = m[2].toInt() and 0xFF
        val dir = if (flags and 0x02 != 0) dirRaw + 180 else dirRaw
        val spRaw = m[3].toInt() and 0xFF
        val speed = when {
            spRaw == 255 -> null
            flags and 0x01 == 0 -> spRaw * 0.25
            else -> spRaw * 0.75 + 255 * 0.25
        }
        val vRaw = m[4].toInt()
        val lat = bb.getInt(5) / 1e7
        val lon = bb.getInt(9) / 1e7
        val valid = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
        val ts = bb.getShort(21).toInt() and 0xFFFF
        return Message.Location(
            status = Status.of(flags shr 4),
            heightAgl = flags and 0x04 != 0,
            directionDeg = dir.takeIf { it <= 359 },
            speedMs = speed,
            verticalMs = if (vRaw == 63 * 2) null else vRaw * 0.5,
            lat = lat.takeIf { valid }, lon = lon.takeIf { valid },
            altBaroM = alt(bb.getShort(13).toInt() and 0xFFFF),
            altGeoM = alt(bb.getShort(15).toInt() and 0xFFFF),
            heightM = alt(bb.getShort(17).toInt() and 0xFFFF),
            horizAccuracy = m[19].toInt() and 0x0F,
            tenthsAfterHour = ts.takeIf { it != 0xFFFF && it <= 36000 },
        )
    }

    private fun selfId(m: ByteArray): Message.SelfId? = text(m, 2, 23).takeIf { it.isNotEmpty() }?.let { Message.SelfId(it) }

    private fun system(m: ByteArray): Message.System {
        val bb = le(m)
        val lat = bb.getInt(2) / 1e7
        val lon = bb.getInt(6) / 1e7
        val valid = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
        val flags = m[1].toInt() and 0xFF
        val eu = (flags shr 2) and 0x07 == 1
        val cc = m[17].toInt() and 0xFF
        return Message.System(
            operatorLat = lat.takeIf { valid }, operatorLon = lon.takeIf { valid },
            operatorLocationType = flags and 0x03,
            euCategory = if (eu) (cc shr 4).takeIf { it in 1..3 } else null,
            euClass = if (eu) (cc and 0x0F).takeIf { it in 1..7 } else null,
            operatorAltGeoM = alt(bb.getShort(18).toInt() and 0xFFFF),
        )
    }

    private fun operatorId(m: ByteArray): Message.OperatorId? = text(m, 2, 20).takeIf { it.isNotEmpty() }?.let { Message.OperatorId(it) }

    /** Distance in metres between two points. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }
}

enum class IdType { None, Serial, Registration, UtmUuid, SessionId, Other;
    companion object { fun of(v: Int) = when (v) { 0 -> None; 1 -> Serial; 2 -> Registration; 3 -> UtmUuid; 4 -> SessionId; else -> Other } }
}

enum class UaType { None, Aeroplane, Multirotor, Gyroplane, HybridVtol, Ornithopter, Glider, Kite, FreeBalloon, CaptiveBalloon, Airship, Parachute, Rocket, Tethered, GroundObstacle, Other;
    companion object { fun of(v: Int) = entries.getOrElse(v) { Other } }
}

enum class Status { Undeclared, Ground, Airborne, Emergency, Failure;
    companion object { fun of(v: Int) = entries.getOrElse(v) { Undeclared } }
}

sealed interface Message {
    data class BasicId(val idType: IdType, val uaType: UaType, val id: String) : Message
    data class Location(
        val status: Status, val heightAgl: Boolean, val directionDeg: Int?, val speedMs: Double?, val verticalMs: Double?,
        val lat: Double?, val lon: Double?, val altBaroM: Double?, val altGeoM: Double?, val heightM: Double?,
        val horizAccuracy: Int, val tenthsAfterHour: Int?,
    ) : Message
    data class SelfId(val text: String) : Message
    data class System(
        val operatorLat: Double?, val operatorLon: Double?, val operatorLocationType: Int,
        val euCategory: Int?, val euClass: Int?, val operatorAltGeoM: Double?,
    ) : Message
    data class OperatorId(val id: String) : Message
}
