package lab

import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth

fun csv(value: Any): String {
    val s = value.toString()
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

private fun Writer.row(vararg values: Any) {
    write(values.joinToString(",", postfix = "\n", transform = ::csv))
}

fun generate(
    directory: Path,
    properties: Int = 1000,
) {
    require(properties in 1..1000)
    Files.createDirectories(directory)
    val writers = tables.associateWith { Files.newBufferedWriter(directory.resolve("$it.csv")) }
    val start = LocalDate.of(2023, 11, 1)
    val end = LocalDate.of(2026, 10, 31)
    val change = LocalDate.of(2025, 11, 1)
    val initial = "2023-10-31 00:00:00"
    try {
        val tariffs = writers.getValue("dim_tariffs")
        tariffs.row(1, "День–Ночь", 500, 250, "2023-11-01 00:00:00", "2025-11-01 00:00:00", initial, initial)
        tariffs.row(1, "День–Ночь", 620, 310, "2025-11-01 00:00:00", "2099-01-01 00:00:00", initial, initial)
        for (id in 1..properties) {
            val nonResidential = id % 10 == 0
            writers.getValue("dim_realty").row(
                id,
                "г. Москва, Тестовая улица, дом ${(id - 1) / 100 + 1}",
                40 + id % 60,
                if (nonResidential) "nrcp" else "flat",
                if (nonResidential) "" else "$id",
                if (nonResidential) 0 else 1 + id % 4,
                id % 10,
                1 + id % 3,
                initial,
            )
            writers.getValue("dim_users").row(id, "Тестовый Собственник $id", "+7${9000000000L + id}", "", initial, initial)
            writers.getValue("dim_gauges").row(id, "Тестовый счётчик", "RU", initial)
            writers.getValue("fact_gauge_bindings").row(id, id, initial, 1, 1)
            if (id <= properties / 10) {
                writers.getValue("dim_gauges").row(1000 + id, "Новый счётчик", "RU", "$change 00:00:00")
                writers.getValue("fact_gauge_bindings").row(id, id, initial, -1, 1)
                writers.getValue("fact_gauge_bindings").row(1000 + id, id, "$change 00:00:00", 1, 2)
            }
            val dayUsage = 4 + id % 4
            val nightUsage = 1 + id % 3
            var dayCounter = 0
            var nightCounter = 0
            var day = start
            var index = 0
            while (!day.isAfter(end)) {
                if (day == change && id <= properties / 10) {
                    dayCounter = 0
                    nightCounter = 0
                }
                dayCounter += dayUsage
                nightCounter += nightUsage
                val gauge = if (day >= change && id <= properties / 10) 1000 + id else id
                val correction = (index * 1000 + id - 1) % 100 < 3
                writers.getValue("fact_meter_readings").row(
                    id,
                    id,
                    1,
                    gauge,
                    day,
                    if (correction) dayCounter - 1 else dayCounter,
                    if (correction) nightCounter - 1 else nightCounter,
                    "$day 12:00:00",
                )
                if (correction) {
                    writers.getValue("fact_meter_readings").row(
                        id,
                        id,
                        1,
                        gauge,
                        day,
                        dayCounter,
                        nightCounter,
                        "$day 12:00:01",
                    )
                }
                day = day.plusDays(1)
                index++
            }
            var month = YearMonth.from(start)
            repeat(36) {
                val gauge = if (month.atDay(1) >= change && id <= properties / 10) 1000 + id else id
                val dayKw = month.lengthOfMonth().toLong() * dayUsage
                val nightKw = month.lengthOfMonth().toLong() * nightUsage
                val priceDay = if (month.atDay(1) < change) 500L else 620L
                val priceNight = if (month.atDay(1) < change) 250L else 310L
                val price = Math.addExact(Math.multiplyExact(dayKw, priceDay), Math.multiplyExact(nightKw, priceNight))
                writers.getValue("fact_invoice").row(
                    id,
                    id,
                    1,
                    gauge,
                    dayKw,
                    nightKw,
                    price,
                    month.plusMonths(1).atDay(10),
                    "${month.atEndOfMonth()} 12:00:00",
                )
                month = month.plusMonths(1)
            }
        }
    } finally {
        writers.values.forEach { it.close() }
    }
}
