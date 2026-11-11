package lab

import kotlinx.serialization.json.JsonObject

interface Store {
    suspend fun user(id: ULong): JsonObject?

    suspend fun realty(id: ULong): JsonObject?

    suspend fun exists(
        table: String,
        id: ULong,
    ): Boolean

    suspend fun tariff(date: String): JsonObject?

    suspend fun readings(
        id: ULong,
        from: String,
        to: String,
    ): List<JsonObject>

    suspend fun invoices(
        id: ULong,
        limit: ULong,
        offset: ULong,
    ): Pair<List<JsonObject>, ULong>

    suspend fun binding(id: ULong): JsonObject?

    suspend fun insert(
        table: String,
        rows: List<JsonObject>,
    )
}

val tables =
    listOf(
        "dim_realty",
        "dim_gauges",
        "dim_tariffs",
        "dim_users",
        "fact_gauge_bindings",
        "fact_meter_readings",
        "fact_invoice",
    )
