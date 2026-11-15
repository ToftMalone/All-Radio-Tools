package com.allnetworktools.util

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * CSV files opened in a spreadsheet: a cell that starts with `=`, `+`, `-` or `@` is read as a formula, and much of what
 * the tools save is written by strangers (a node name, a pager message, an SSID). [Guard] puts an apostrophe in front of
 * such a cell, which the spreadsheet then shows as plain text. Numbers (`-12.5`) are left alone.
 */
object Csv {
    private const val TRIGGERS = "=+-@\t\r"
    private val Number = Regex("[+-]?[0-9]+([.,][0-9]+)*([eE][+-]?[0-9]+)?")
    private const val MAX_FIELD = 1 shl 16

    /** One field as written (quotes included), with the apostrophe if it needs one. */
    internal fun guardField(f: ByteArray): ByteArray {
        val quoted = f.isNotEmpty() && f[0] == '"'.code.toByte()
        val at = if (quoted) 1 else 0
        if (at >= f.size || f[at].toInt().toChar() !in TRIGGERS) return f
        val end = if (quoted) f.indexOfLast { it == '"'.code.toByte() }.let { if (it > at) it else f.size } else f.size
        val content = String(f, at, end - at, Charsets.ISO_8859_1)
        if (Number.matches(content)) return f
        return f.copyOfRange(0, at) + byteArrayOf('\''.code.toByte()) + f.copyOfRange(at, f.size)
    }

    /** Wraps [out]: what is written through it comes out as CSV with the cells guarded. */
    class Guard(private val out: OutputStream) : OutputStream() {
        private val field = ByteArrayOutputStream()
        private var inQuotes = false

        override fun write(b: Int) {
            val c = b.toByte()
            when {
                c == '"'.code.toByte() -> { inQuotes = !inQuotes; field.write(b) }
                !inQuotes && (c == ','.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte()) -> { flushField(); out.write(b) }
                else -> { field.write(b); if (field.size() > MAX_FIELD) flushField() }
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt()) }

        private fun flushField() {
            if (field.size() > 0) out.write(guardField(field.toByteArray()))
            field.reset()
        }

        override fun flush() = out.flush()

        override fun close() {
            flushField()
            out.close()
        }
    }

    /** The same on a string, for the places that hold the whole text. */
    fun guard(text: String): String = ByteArrayOutputStream().also { Guard(it).use { g -> g.write(text.toByteArray(Charsets.UTF_8)) } }.toString(Charsets.UTF_8)
}
