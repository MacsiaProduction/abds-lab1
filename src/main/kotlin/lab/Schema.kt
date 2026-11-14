package lab

val schemaColumns =
    mapOf(
        "dim_realty" to listOf("id", "address", "square", "type", "number", "rooms", "floor", "porch", "created_at"),
        "dim_gauges" to listOf("id", "name", "country", "created_at"),
        "dim_users" to listOf("id", "fio", "phone", "phone_prev", "created_at", "updated_at"),
        "dim_tariffs" to
            listOf(
                "id",
                "name",
                "price_day_kwph",
                "price_night_kwph",
                "valid_from",
                "valid_to",
                "created_at",
                "updated_at",
            ),
        "fact_gauge_bindings" to listOf("gauge_id", "realty_id", "created_at", "sign", "version"),
        "fact_meter_readings" to
            listOf(
                "realty_id",
                "user_id",
                "tariff_id",
                "gauge_id",
                "reading_date",
                "day_kw",
                "night_kw",
                "created_at",
            ),
        "fact_invoice" to
            listOf(
                "realty_id",
                "user_id",
                "tariff_id",
                "gauge_id",
                "day_kw",
                "night_kw",
                "price",
                "deadline",
                "created_at",
            ),
    )
