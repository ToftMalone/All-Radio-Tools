package com.allnetworktools.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Public "play sound" protocols that a tracker offers to people who do not own it. */
enum class SoundProtocol(val label: String, val service: UUID, val characteristic: UUID, val start: ByteArray, val stop: ByteArray?) {
    /** IETF DULT accessory protocol (Apple and Google specification, adopted by tag makers). */
    Dult(
        S(R.string.dult_standard_ietf),
        UUID.fromString("15190001-12F4-C226-88ED-2AC5579F2A85"), UUID.fromString("8E0C0001-1D68-FB92-BF61-48377421680E"),
        byteArrayOf(0x00, 0x03), byteArrayOf(0x01, 0x03),
    ),

    /** Apple Find My network accessory specification, accessory non-owner service. */
    FindMy(
        S(R.string.apple_find_my_protocol),
        UUID.fromString("0000FD44-0000-1000-8000-00805F9B34FB"), UUID.fromString("4F860003-943B-49EF-BED4-2F730304427A"),
        byteArrayOf(0x01, 0x00, 0x03), byteArrayOf(0x01, 0x01, 0x03),
    ),

    /** First-generation AirTag firmware. */
    AirTag(
        S(R.string.airtag_protocol),
        UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C"), UUID.fromString("7DFC9001-7D1C-4951-86AA-8D9728F8D66C"),
        byteArrayOf(0xAF.toByte()), null,
    ),
}

sealed interface SoundResult {
    data class Playing(val protocol: SoundProtocol) : SoundResult
    data class Refused(val protocol: SoundProtocol, val message: String) : SoundResult
    data class Failed(val message: String) : SoundResult
}

/** Reads a DULT Command_Response (opcode 0x0302): the command it answers and its status. */
internal fun dultResponse(v: ByteArray): Pair<Int, Int>? {
    if (v.size < 6 || v.u16le(0) != 0x0302) return null
    return v.u16le(2) to v.u16le(4)
}

/**
 * Makes a tracker ring through a public non-owner protocol. Trackers only accept it while they are
 * separated from their owner, so a tag next to its owner answers "invalid state".
 */
class TagSound(private val context: Context) {
    private var client: GattClient? = null
    private var protocol: SoundProtocol? = null

    suspend fun play(address: String): SoundResult {
        stop()
        val c = GattClient(context, address)
        client = c
        return try {
            c.connect(12_000)
            val services = c.discover()
            val (p, ch) = SoundProtocol.entries.firstNotNullOfOrNull { p ->
                services.firstOrNull { it.uuid == p.service }?.getCharacteristic(p.characteristic)?.let { p to it }
            } ?: run {
                close()
                return SoundResult.Failed(S(R.string.this_tag_offers_no_public_ringing))
            }
            protocol = p
            if (p == SoundProtocol.AirTag) {
                // The AirTag drops the link once it starts ringing.
                runCatching { c.write(ch, p.start) }
                return SoundResult.Playing(p)
            }
            runCatching { c.setNotify(ch, true) }
            coroutineScope {
                val answer = CompletableDeferred<ByteArray?>()
                val listen = launch {
                    val v = c.events.first { it is GattEvent.Changed && it.characteristic.uuid == p.characteristic } as GattEvent.Changed
                    answer.complete(v.value)
                }
                c.write(ch, p.start)
                val v = withTimeoutOrNull(4_000) { answer.await() }
                listen.cancel()
                val status = v?.let { if (p == SoundProtocol.FindMy && it.size > 1) dultResponse(it.copyOfRange(1, it.size)) ?: dultResponse(it) else dultResponse(it) }?.second
                when (status) {
                    null, 0 -> SoundResult.Playing(p)
                    1, 0xFFFF -> {
                        close()
                        SoundResult.Refused(p, S(R.string.the_tag_refuses_it_is_near))
                    }
                    else -> {
                        close()
                        SoundResult.Refused(p, S(R.string.the_tag_answered_with_error_0x).format(status))
                    }
                }
            }
        } catch (e: GattException) {
            close()
            SoundResult.Failed(e.message ?: S(R.string.connection_failed))
        }
    }

    /** Stops the sound (when the protocol has a stop command) and disconnects. */
    suspend fun stop() {
        val c = client ?: return
        val p = protocol
        val stopBytes = p?.stop
        if (p != null && stopBytes != null) {
            runCatching {
                c.discover().firstOrNull { it.uuid == p.service }?.getCharacteristic(p.characteristic)?.let { c.write(it, stopBytes) }
                delay(200)
            }
        }
        close()
    }

    private fun close() {
        client?.close()
        client = null
        protocol = null
    }
}
