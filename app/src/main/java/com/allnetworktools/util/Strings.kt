package com.allnetworktools.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.allnetworktools.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's texts, in the phone's language (values/ English by default, values-fr/ French…). Reachable from anywhere,
 * composables or not, so labels built in the data layer translate too. The application sets [context] at start-up.
 */
object Res {
    @Volatile var context: Context? = null

    /**
     * Without Android (plain JVM unit tests), texts come from the strings file named by the "ant.strings" system
     * property, so logic tests still read real sentences.
     */
    private val fallback: Map<Int, Any> by lazy { loadFallback() }

    fun string(@StringRes id: Int, args: Array<out Any?>): String {
        val c = context
        if (c != null) return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
        val raw = fallback[id] as? String ?: error("string $id missing from the test strings file")
        return if (args.isEmpty()) raw else String.format(Locale.getDefault(), raw, *args)
    }

    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String {
        val c = context
        if (c != null) return c.resources.getQuantityString(id, count, *args)
        @Suppress("UNCHECKED_CAST")
        val forms = fallback[id] as? Map<String, String> ?: error("plural $id missing from the test strings file")
        // French and English rules alike for the forms the app uses: "one" for 1 (and 0 in French), "other" otherwise.
        val one = if (Locale.getDefault().language == "fr") count <= 1 else count == 1
        val raw = forms[if (one) "one" else "other"] ?: forms.getValue("other")
        return if (args.isEmpty()) raw else String.format(Locale.getDefault(), raw, *args)
    }

    private fun loadFallback(): Map<Int, Any> {
        val dir = System.getProperty("ant.strings") ?: return emptyMap()
        val ids = HashMap<String, Int>()
        R.string::class.java.fields.forEach { ids["s:" + it.name] = it.getInt(null) }
        R.plurals::class.java.fields.forEach { ids["p:" + it.name] = it.getInt(null) }
        val out = HashMap<Int, Any>()
        File(dir).listFiles { f -> f.name.endsWith(".xml") }?.forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val e = strings.item(i) as org.w3c.dom.Element
                ids["s:" + e.getAttribute("name")]?.let { out[it] = unescape(e.textContent) }
            }
            val plurals = doc.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val e = plurals.item(i) as org.w3c.dom.Element
                val items = e.getElementsByTagName("item")
                val forms = (0 until items.length).associate { k ->
                    val it = items.item(k) as org.w3c.dom.Element
                    it.getAttribute("quantity") to unescape(it.textContent)
                }
                ids["p:" + e.getAttribute("name")]?.let { out[it] = forms }
            }
        }
        return out
    }

    /** Android's string escapes: surrounding quotes, \' \" \\ \n \t \uXXXX. */
    internal fun unescape(s: String): String {
        val t = s.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
        val b = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val n = t[i + 1]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'u' -> { b.append(t.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> b.append(n)
                }
                i += 2
            } else {
                b.append(c); i++
            }
        }
        return b.toString()
    }
}

/** A text of the app in the current language. */
fun S(@StringRes id: Int, vararg args: Any?): String = Res.string(id, args)

/** A text that agrees with [count] ("1 satellite", "2 satellites"). */
fun P(@PluralsRes id: Int, count: Int, vararg args: Any?): String = Res.plural(id, count, args)
