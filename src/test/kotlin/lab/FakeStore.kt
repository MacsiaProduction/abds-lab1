package lab

import kotlinx.serialization.json.*

class FakeStore : Store {
    val users = mutableMapOf<ULong, JsonObject>()
    val properties = mutableMapOf<ULong, JsonObject>()
    val gauges = mutableSetOf(1uL, 2uL)
    val readings = mutableListOf<JsonObject>()
    val bindings = mutableListOf<JsonObject>()
    val bills = mutableListOf<JsonObject>()
    val tariffs = mutableListOf<JsonObject>()
    var failWrites = false

    init {
        val fixtures: JsonObject =
            json.parseToJsonElement(requireNotNull(javaClass.getResource("/lab/entities.json")).readText()).jsonObject
        users[1uL] =
            fixtures.getValue("user").jsonObject
        properties[1uL] =
            fixtures.getValue("realty").jsonObject
        tariffs +=
            fixtures.getValue("tariff").jsonObject
        tariffs +=
            tariffs.first().changed(
                "price_day_kwph" to number(620L),
                "price_night_kwph" to number(310L),
                "valid_from" to text("2025-11-01 00:00:00"),
                "valid_to" to text("2099-01-01 00:00:00"),
            )
        bindings +=
            obj(
                "realty_id" to number(1L),
                "gauge_id" to number(1L),
                "sign" to number(1L),
                "version" to number(1L),
                "created_at" to text("2023-01-01 00:00:00"),
            )
        bills +=
            obj(
                "realty_id" to number(1L),
                "tariff_id" to number(1L),
                "gauge_id" to number(1L),
                "day_kw" to number(10L),
                "night_kw" to number(2L),
                "price" to number(5500L),
                "deadline" to text("2023-12-10"),
                "created_at" to text("2023-11-30 12:00:00"),
            )
    }

    override suspend fun user(id: ULong) = users[id]

    override suspend fun realty(id: ULong) = properties[id]

    override suspend fun exists(
        table: String,
        id: ULong,
    ) = when (table) {
        "dim_users" -> id in users
        "dim_gauges" -> id in gauges
        "dim_tariffs" -> tariffs.any { it.id("id") == id }
        else -> error("Unexpected table")
    }

    override suspend fun tariff(date: String) =
        tariffs.firstOrNull {
            it.value("valid_from") <= "$date 00:00:00" && "$date 00:00:00" < it.value("valid_to")
        }

    override suspend fun readings(
        id: ULong,
        from: String,
        to: String,
    ) = readings
        .filter {
            it.id("realty_id") == id && it.value("reading_date") in from..to
        }.groupBy { it.value("reading_date") }
        .values
        .map { rows -> rows.maxBy { it.value("created_at") } }
        .sortedBy { it.value("reading_date") }

    override suspend fun invoices(
        id: ULong,
        limit: ULong,
        offset: ULong,
    ): Pair<List<JsonObject>, ULong> {
        val rows = bills.filter { it.id("realty_id") == id }.sortedByDescending { it.value("created_at") }
        return rows
            .drop(offset.coerceAtMost(rows.size.toULong()).toInt())
            .take(limit.coerceAtMost(rows.size.toULong()).toInt()) to rows.size.toULong()
    }

    override suspend fun binding(id: ULong) =
        bindings
            .filter { it.id("realty_id") == id }
            .groupBy { it.id("gauge_id") to it.id("version") }
            .values
            .filter { rows -> rows.sumOf { it.value("sign").toInt() } == 1 }
            .map { it.first() }
            .singleOrNull()

    override suspend fun insert(
        table: String,
        rows: List<JsonObject>,
    ) {
        check(!failWrites) { "Simulated persistence failure" }
        when (table) {
            "dim_users" -> rows.forEach { users[it.id("id")] = it }
            "fact_meter_readings" -> readings += rows
            "fact_gauge_bindings" -> bindings += rows
            else -> error("Unexpected insert")
        }
    }
}

class TestClock : VersionClock {
    private var epoch = java.time.Instant.parse("2026-11-11T00:00:00Z")

    override suspend fun after(previous: String?): String {
        epoch = maxOf(epoch.plusSeconds(1), previous?.let(::parseTimestamp)?.plusSeconds(1) ?: epoch)
        return timestamp(epoch)
    }
}
