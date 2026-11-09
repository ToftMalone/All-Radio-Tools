package com.allnetworktools.data.remoteid

/** One aircraft heard over Remote ID, built up from the separate messages it broadcasts. */
data class Drone(
    val key: String,
    val id: String? = null,
    val idType: IdType = IdType.None,
    val uaType: UaType = UaType.None,
    val status: Status = Status.Undeclared,
    val lat: Double? = null,
    val lon: Double? = null,
    val altGeoM: Double? = null,
    val altBaroM: Double? = null,
    val heightM: Double? = null,
    val heightAgl: Boolean = false,
    val speedMs: Double? = null,
    val verticalMs: Double? = null,
    val directionDeg: Int? = null,
    val operatorLat: Double? = null,
    val operatorLon: Double? = null,
    val operatorId: String? = null,
    val selfId: String? = null,
    val euCategory: Int? = null,
    val euClass: Int? = null,
    val rssi: Int? = null,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
    val messages: Int = 0,
    val track: List<Pair<Double, Double>> = emptyList(),
    /** How it was heard: Bluetooth, Wi-Fi beacon or Wi-Fi Aware. */
    val via: Via = Via.Bluetooth,
) {
    val title: String get() = id ?: key
    val located: Boolean get() = lat != null && lon != null
    fun active(now: Long) = now - lastSeen < ACTIVE_MS

    companion object {
        const val ACTIVE_MS = 30_000L
        const val FORGET_MS = 30 * 60_000L
    }
}

enum class Via(val label: String) { Bluetooth("Bluetooth"), WifiBeacon("Wi-Fi"), WifiNan("Wi-Fi Aware") }

/** Folds Remote ID messages into drones. Bluetooth addresses identify a transmitter for the flight. */
class DroneTracker {
    private val drones = LinkedHashMap<String, Drone>()

    /** Returns true when this transmitter was not known yet (first time it is heard). */
    @Synchronized
    fun add(address: String, msgs: List<Message>, rssi: Int?, now: Long, via: Via = Via.Bluetooth): Boolean {
        if (msgs.isEmpty()) return false
        val fresh = address !in drones
        var d = drones[address] ?: Drone(address, firstSeen = now, via = via)
        for (m in msgs) d = when (m) {
            is Message.BasicId -> d.copy(
                id = if (d.id == null || m.idType == IdType.Serial || d.idType != IdType.Serial) m.id else d.id,
                idType = if (d.id == null || m.idType == IdType.Serial || d.idType != IdType.Serial) m.idType else d.idType,
                uaType = if (m.uaType != UaType.None) m.uaType else d.uaType,
            )
            is Message.Location -> {
                val moved = m.lat != null && m.lon != null
                val track = if (moved && d.track.lastOrNull() != (m.lat!! to m.lon!!)) (d.track + (m.lat to m.lon)).takeLast(300) else d.track
                d.copy(
                    status = m.status, heightAgl = m.heightAgl,
                    lat = m.lat ?: d.lat, lon = m.lon ?: d.lon,
                    altGeoM = m.altGeoM ?: d.altGeoM, altBaroM = m.altBaroM ?: d.altBaroM, heightM = m.heightM ?: d.heightM,
                    speedMs = m.speedMs, verticalMs = m.verticalMs, directionDeg = m.directionDeg, track = track,
                )
            }
            is Message.System -> d.copy(
                operatorLat = m.operatorLat ?: d.operatorLat, operatorLon = m.operatorLon ?: d.operatorLon,
                euCategory = m.euCategory ?: d.euCategory, euClass = m.euClass ?: d.euClass,
            )
            is Message.OperatorId -> d.copy(operatorId = m.id)
            is Message.SelfId -> d.copy(selfId = m.text)
        }
        drones[address] = d.copy(lastSeen = now, rssi = rssi ?: d.rssi, messages = d.messages + msgs.size)
        return fresh
    }

    @Synchronized
    fun prune(now: Long) {
        drones.entries.removeAll { now - it.value.lastSeen > Drone.FORGET_MS }
    }

    @Synchronized
    fun clear() = drones.clear()

    /** Active drones first, then the most recently heard. */
    @Synchronized
    fun list(now: Long): List<Drone> = drones.values.sortedWith(compareByDescending<Drone> { it.active(now) }.thenByDescending { it.lastSeen })
}
