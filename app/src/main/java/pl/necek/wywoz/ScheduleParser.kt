package pl.necek.wywoz

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Pickup(val date: LocalDate, val types: List<String>)

object ScheduleParser {
    val MONTHS = listOf(
        "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
        "lipca", "sierpnia", "września", "października", "listopada", "grudnia"
    )
    val DAYS = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")

    private val FAMILIES = listOf(
        listOf("zmiesz", "resztkow"),
        listOf("papier", "makulatur"),
        listOf("szkło", "szklo", "szkła"),
        listOf("tworzyw", "metal", "opakowa", "plastik"),
        listOf("bio", "kuchen", "beczk"),
        listOf("zielon", "ogrod"),
        listOf("choink"),
        listOf("gabaryt", "wielkogabaryt"),
        listOf("elektr"),
        listOf("popiół", "popiol"),
        listOf("tekstyl", "odzież", "odziez"),
    ).map { keys -> keys.map { Regex("(?<!\\p{L})" + Regex.escape(it)) } }

    private val GENERIC = Regex("(?<!\\p{L})odpady(?!\\p{L})")
    private val STOP = Regex("(?<!\\p{L})(harmonogram|wywoz|wywóz|odbior|odbiór|strona|mpo|www|data|ulica|adres)")
    private val dateRegex = Regex(
        "(?<!\\d)(\\d{1,2})\\s+(${MONTHS.joinToString("|")})(?!\\p{L})(?:\\s+(\\d{4})(?!\\d))?"
    )
    private val generatedRegex = Regex("data\\s+generowania:?\\s*(\\d{4})-(\\d{2})-(\\d{2})")
    private val dayRegex = Regex("(?<!\\p{L})(${DAYS.joinToString("|")})(?!\\p{L})")
    private val typeRegex = Regex("^\\p{L}[\\p{L} ()\\-–]*$")
    private val spaces = Regex("\\s+")

    private class Entry(val day: Int, val month: Int, val year: Int?, val weekday: Int?, val types: List<String>)

    fun parse(text: String, today: LocalDate = LocalDate.now()): List<Pickup> {
        val clean = cutFooter(text.replace(Char(0xA0), ' ').replace("\r", "")).lowercase()
        val reference = generatedRegex.find(clean)?.let {
            runCatching {
                LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }.getOrNull()
        } ?: today
        val body = generatedRegex.replace(clean, "\n")
        val matches = dateRegex.findAll(body).toList()
        val entries = matches.mapIndexed { i, m ->
            val windowStart = maxOf(if (i > 0) matches[i - 1].range.last + 1 else 0, m.range.first - 30)
            val before = body.substring(windowStart, m.range.first)
            val weekday = dayRegex.findAll(before).lastOrNull()?.let { DAYS.indexOf(it.value) + 1 }
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            Entry(
                day = m.groupValues[1].toInt(),
                month = MONTHS.indexOf(m.groupValues[2]) + 1,
                year = m.groupValues[3].toIntOrNull(),
                weekday = weekday,
                types = extractTypes(body.substring(m.range.last + 1, end))
            )
        }
        if (entries.isEmpty()) return emptyList()

        val best = (reference.year - 1..reference.year + 1)
            .map { startYear -> startYear to datesFor(entries, startYear) }
            .maxByOrNull { (startYear, dates) -> score(entries, dates, startYear, reference) }!!
            .second

        val result = sortedMapOf<LocalDate, LinkedHashSet<String>>()
        entries.forEachIndexed { i, e ->
            val date = best[i] ?: return@forEachIndexed
            if (e.types.isNotEmpty()) result.getOrPut(date) { LinkedHashSet() }.addAll(e.types)
        }
        return result.map { Pickup(it.key, it.value.toList()) }
    }

    private fun cutFooter(text: String): String {
        val lines = text.lines()
        val firstDate = lines.indexOfFirst { dateRegex.containsMatchIn(it.lowercase()) }
        if (firstDate < 0) return text
        for (i in firstDate + 1 until lines.size) {
            val line = lines[i].trim()
            val letters = line.filter { it.isLetter() }
            if (letters.length < 4 || !letters.all { it.isUpperCase() }) continue
            val next = lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            if (line.endsWith(":") || next.startsWith("-") || next.startsWith("–") || next.length > 80) {
                return lines.take(i).joinToString("\n")
            }
        }
        return text
    }

    private fun datesFor(entries: List<Entry>, startYear: Int): List<LocalDate?> {
        var year = startYear
        var previousMonth: Int? = null
        return entries.map { e ->
            if (e.year != null) year = e.year
            else if (previousMonth != null && e.month < previousMonth!!) year++
            previousMonth = e.month
            runCatching { LocalDate.of(year, e.month, e.day) }.getOrNull()
        }
    }

    private fun score(entries: List<Entry>, dates: List<LocalDate?>, startYear: Int, reference: LocalDate): Int {
        val weekdayHits = entries.indices.count { i ->
            val w = entries[i].weekday
            val d = dates[i]
            w != null && d != null && d.dayOfWeek.value == w
        }
        val valid = dates.filterNotNull()
        val inSpan = valid.isNotEmpty() &&
            ChronoUnit.DAYS.between(valid.min(), reference) >= -31 &&
            ChronoUnit.DAYS.between(reference, valid.max()) >= -31
        return weekdayHits * 10 + (if (inSpan) 5 else 0) + (if (startYear == reference.year) 1 else 0) + valid.size * 100
    }

    private fun extractTypes(segment: String): List<String> =
        segment.split('\n', ',', ';')
            .map { dayRegex.replace(it, " ").replace(spaces, " ").trim(' ', '-', '–', '•', '*', '\t') }
            .filter { it.isNotEmpty() && it.length <= 60 && typeRegex.matches(it) && !STOP.containsMatchIn(it) }
            .flatMap { splitTypes(it) }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .distinct()

    private fun splitTypes(line: String): List<String> {
        val hits = FAMILIES.flatMapIndexed { family, patterns ->
            patterns.flatMap { p -> p.findAll(line).map { it.range.first to family }.toList() }
        }.sortedBy { it.first }
        if (hits.isEmpty()) return if (GENERIC.containsMatchIn(line)) listOf(line) else emptyList()
        val cuts = mutableListOf(0)
        var family = hits.first().second
        for ((position, f) in hits) {
            if (f != family) {
                cuts += position
                family = f
            }
        }
        cuts += line.length
        return cuts.zipWithNext { a, b -> line.substring(a, b).trim(' ', '-', '–') }.filter { it.isNotEmpty() }
    }
}
