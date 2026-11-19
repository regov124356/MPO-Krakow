package pl.necek.wywoz

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Collator
import java.util.Locale

data class ApiItem(val id: String, val name: String)

object MpoApi {
    private const val BASE = "https://kiedywywoz.pl/API/harmo_img/"
    private const val TOKEN = "OkkxhC6b9etJBAq7WTHJ0LhIglO18sip"
    private val leadingNumber = Regex("^\\d+")

    fun streets(): Map<String, List<String>> =
        items(post(mapOf("token" to TOKEN)))
            .groupBy({ titleCase(it.name) }, { it.id })
            .toSortedMap(Collator.getInstance(Locale.forLanguageTag("pl-PL")))

    fun numbers(streetIds: List<String>): List<ApiItem> =
        streetIds
            .flatMap { items(post(mapOf("ulica" to it, "token" to TOKEN))) }
            .map { ApiItem(it.id, it.name.uppercase()) }
            .distinctBy { it.name }
            .sortedWith(compareBy({ leadingNumber.find(it.name)?.value?.toIntOrNull() ?: Int.MAX_VALUE }, { it.name }))

    fun pdf(numberId: String): ByteArray {
        val bytes = request(URL("${BASE}pdf/?id_numeru=${enc(numberId)}&token=$TOKEN"), null)
        if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") {
            throw IOException(String(bytes, Charsets.UTF_8).trim().take(200).ifEmpty { "Pusta odpowiedź serwera" })
        }
        return bytes
    }

    fun titleCase(s: String): String {
        val sb = StringBuilder(s.length)
        var previousLetter = false
        for (ch in s.trim().lowercase()) {
            sb.append(if (!previousLetter && ch.isLetter()) ch.uppercaseChar() else ch)
            previousLetter = ch.isLetter()
        }
        return sb.toString()
    }

    private fun items(json: String): List<ApiItem> {
        val trimmed = json.trim { it.isWhitespace() || it == Char(0xFEFF) }
        if (!trimmed.startsWith("[")) throw IOException("Nieoczekiwana odpowiedź serwera: ${trimmed.take(100)}")
        val array = JSONArray(trimmed)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            if (id.isEmpty() || id == "0" || name.isEmpty() || name == "-Brak-") null else ApiItem(id, name)
        }
    }

    private fun post(form: Map<String, String>): String {
        val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return String(request(URL(BASE), body), Charsets.UTF_8)
    }

    private fun request(url: URL, form: String?): ByteArray {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Wywoz-Android/1.0")
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
