package pl.necek.wywoz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleParserTest {

    private fun dayName(d: LocalDate) = ScheduleParser.DAYS[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }
    private fun dateText(d: LocalDate) = "${d.dayOfMonth} ${ScheduleParser.MONTHS[d.monthValue - 1]}"

    private fun listing(generated: String?, items: List<Pair<LocalDate, List<String>>>, sameLine: Boolean = false): String {
        val sb = StringBuilder("Harmonogram wywozu odpadów komunalnych\nRomanowicza 1 DM\n")
        if (generated != null) sb.append("Data generowania: $generated\n")
        for ((d, types) in items) {
            if (sameLine) sb.append("${dayName(d)} ${dateText(d)} ${types.joinToString(" ")}\n")
            else sb.append("${dayName(d)}\n${dateText(d)}\n${types.joinToString("\n")}\n")
        }
        return sb.toString()
    }

    @Test
    fun parsesVerticalListWithYearRollover() {
        val items = listOf(
            LocalDate.of(2026, 11, 20) to listOf("Zmieszane", "Papier"),
            LocalDate.of(2026, 11, 24) to listOf("Tworzywa sztuczne"),
            LocalDate.of(2026, 12, 30) to listOf("Szkło"),
            LocalDate.of(2027, 1, 4) to listOf("Bio", "Choinki"),
            LocalDate.of(2027, 2, 9) to listOf("Beczka - odpady kuchenne")
        )
        val result = ScheduleParser.parse(listing("2026-11-19", items), LocalDate.of(2000, 1, 1))
        assertEquals(items.map { it.first }, result.map { it.date })
        assertEquals(items.map { it.second }, result.map { it.types })
    }

    @Test
    fun splitsTypesOnOneLine() {
        val items = listOf(
            LocalDate.of(2026, 11, 20) to listOf("Zmieszane", "Papier"),
            LocalDate.of(2026, 11, 27) to listOf("Metale i tworzywa sztuczne", "Szkło"),
            LocalDate.of(2026, 12, 1) to listOf("Odpady wielkogabarytowe")
        )
        val result = ScheduleParser.parse(listing("2026-11-19", items, sameLine = true))
        assertEquals(items.map { it.first }, result.map { it.date })
        assertEquals(items.map { it.second }, result.map { it.types })
    }

    @Test
    fun fullCalendarYearKeepsJanuaryInSameYear() {
        val items = listOf(
            LocalDate.of(2026, 1, 7) to listOf("Papier"),
            LocalDate.of(2026, 6, 10) to listOf("Szkło"),
            LocalDate.of(2026, 12, 16) to listOf("Zmieszane")
        )
        val result = ScheduleParser.parse(listing("2026-11-19", items))
        assertEquals(items.map { it.first }, result.map { it.date })
    }

    @Test
    fun generatedInJanuaryWithDecemberStart() {
        val items = listOf(
            LocalDate.of(2026, 12, 29) to listOf("Papier"),
            LocalDate.of(2027, 1, 5) to listOf("Zmieszane")
        )
        val result = ScheduleParser.parse(listing("2027-01-02", items))
        assertEquals(items.map { it.first }, result.map { it.date })
    }

    @Test
    fun weekdaysPickYearWithoutGenerationDate() {
        val items = listOf(
            LocalDate.of(2027, 3, 2) to listOf("Papier"),
            LocalDate.of(2027, 3, 9) to listOf("Zmieszane"),
            LocalDate.of(2027, 3, 16) to listOf("Szkło")
        )
        val result = ScheduleParser.parse(listing(null, items), LocalDate.of(2026, 12, 20))
        assertEquals(items.map { it.first }, result.map { it.date })
    }

    @Test
    fun ignoresHeaderFooterNoise() {
        val text = """
            Harmonogram wywozu odpadów komunalnych
            ul. Romanowicza 1 DM
            Data generowania: 2026-11-19
            Piątek
            20 listopada
            Zmieszane
            Strona 1 z 2
            Harmonogram wywozu odpadów komunalnych
            Wtorek
            24 listopada
            Papier
            www.mpo.krakow.pl
        """.trimIndent()
        val result = ScheduleParser.parse(text)
        assertEquals(2, result.size)
        assertEquals(listOf("Zmieszane"), result[0].types)
        assertEquals(listOf("Papier"), result[1].types)
    }

    @Test
    fun emptyTextGivesEmptyList() {
        assertTrue(ScheduleParser.parse("Niepoprawny numer #2").isEmpty())
    }
}
