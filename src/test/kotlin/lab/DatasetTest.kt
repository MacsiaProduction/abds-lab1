package lab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth

class DatasetTest {
    @TempDir lateinit var directory: Path

    @Test fun `full synthetic period counts and invoices agree with corrected cumulative readings`() {
        generate(directory)
        val expected =
            mapOf(
                "dim_realty" to 1000L,
                "dim_users" to 1000L,
                "dim_gauges" to 1100L,
                "dim_tariffs" to 2L,
                "fact_gauge_bindings" to 1200L,
                "fact_invoice" to 36000L,
                "fact_meter_readings" to 1128880L,
            )
        for ((table, count) in expected) {
            assertThat(Files.lines(directory.resolve("$table.csv")).use { it.count() }).describedAs(table).isEqualTo(count)
        }
        // Compute usage from emitted readings, independently of generator invoice logic.
        val usage = mutableMapOf<Pair<Int, YearMonth>, Pair<Long, Long>>()
        val baselines = mutableMapOf<Int, Triple<Int, Long, Long>>()
        var pending: List<String>? = null
        var logical = 0
        var lastId = 0
        var expectedDay = LocalDate.of(2023, 11, 1)

        fun accept(row: List<String>) {
            val id = row[0].toInt()
            val gauge = row[3].toInt()
            val date = LocalDate.parse(row[4])
            if (id != lastId) {
                if (lastId != 0) assertThat(expectedDay).isEqualTo(LocalDate.of(2026, 11, 1))
                assertThat(id).isEqualTo(lastId + 1)
                expectedDay = LocalDate.of(2023, 11, 1)
                lastId = id
            }
            assertThat(date).isEqualTo(expectedDay)
            expectedDay = expectedDay.plusDays(1)
            val day = row[5].toLong()
            val night = row[6].toLong()
            val old = baselines[id]?.takeIf { it.first == gauge }
            val deltaDay = day - (old?.second ?: 0)
            val deltaNight = night - (old?.third ?: 0)
            assertThat(deltaDay >= 0 && deltaNight >= 0).isTrue()
            val key = id to YearMonth.from(date)
            val month = usage[key] ?: (0L to 0L)
            usage[key] = month.first + deltaDay to month.second + deltaNight
            baselines[id] = Triple(gauge, day, night)
            logical++
        }
        Files.lines(directory.resolve("fact_meter_readings.csv")).use { rows ->
            rows.forEach { line ->
                val row = line.split(',')
                val previous = pending
                if (previous != null && (previous[0] != row[0] || previous[4] != row[4])) accept(previous)
                if (previous != null && previous[0] == row[0] && previous[4] == row[4]) {
                    assertThat(previous[7] < row[7]).isTrue()
                }
                pending = row
            }
        }
        accept(pending!!)
        assertThat(expectedDay).isEqualTo(LocalDate.of(2026, 11, 1))
        assertThat(logical).isEqualTo(1096000)
        val invoiceKeys = mutableSetOf<Pair<Int, YearMonth>>()
        Files.lines(directory.resolve("fact_invoice.csv")).use { rows ->
            rows.forEach { line ->
                val row = line.split(',')
                val id = row[0].toInt()
                val date = LocalDate.parse(row[8].take(10))
                val month = YearMonth.from(date)
                assertThat(invoiceKeys.add(id to month)).isTrue()
                assertThat(row[4].toLong() to row[5].toLong()).isEqualTo(usage.getValue(id to month))
                val prices = if (date < LocalDate.of(2025, 11, 1)) 500L to 250L else 620L to 310L
                assertThat(row[6].toLong()).isEqualTo(row[4].toLong() * prices.first + row[5].toLong() * prices.second)
                assertThat(row[7]).isEqualTo(month.plusMonths(1).atDay(10).toString())
            }
        }
        assertThat(invoiceKeys.size).isEqualTo(36000)
    }

    @Test fun `generation is reproducible and CSV escapes text`() {
        val one = directory.resolve("one")
        val two = directory.resolve("two")
        generate(one, 1)
        generate(two, 1)
        for (table in tables) {
            assertThat(Files.readAllBytes(two.resolve("$table.csv"))).isEqualTo(Files.readAllBytes(one.resolve("$table.csv")))
        }
        assertThat(csv("a,b")).isEqualTo("\"a,b\"")
        assertThat(csv("a\"b")).isEqualTo("\"a\"\"b\"")
        assertThat(csv("a\nb")).isEqualTo("\"a\nb\"")
    }
}
