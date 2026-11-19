package pl.necek.wywoz

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveApiTest {

    @Test
    fun downloadsAndParsesRealSchedule() {
        assumeTrue(System.getenv("LIVE_API") == "1")
        val street = System.getenv("TEST_STREET") ?: "Romanowicza"
        val number = System.getenv("TEST_NUMBER") ?: "1 DM"

        val streets = MpoApi.streets()
        println("Liczba ulic: ${streets.size}")
        val ids = streets[street] ?: error("Nie znaleziono ulicy $street")

        val numbers = MpoApi.numbers(ids)
        println("Numery dla $street: ${numbers.take(30).joinToString { it.name }}")
        val item = numbers.firstOrNull { it.name == number } ?: error("Nie znaleziono numeru $number")

        val text = PDDocument.load(MpoApi.pdf(item.id)).use { PDFTextStripper().getText(it) }
        println("----- TEKST PDF -----\n$text\n---------------------")

        val pickups = ScheduleParser.parse(text)
        pickups.forEach { println("${it.date}  ${it.types.joinToString(", ")}") }
        assertTrue("Parser nie znalazł żadnych terminów", pickups.isNotEmpty())
    }
}
