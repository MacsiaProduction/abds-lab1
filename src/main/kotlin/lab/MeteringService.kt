package lab

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class EntityNotFound : RuntimeException()

internal class MeteringService(
    private val store: Store,
    private val clock: VersionClock,
) {
    private val writes = Mutex()

    suspend fun updateUser(
        id: ULong,
        fio: String?,
        phone: String?,
    ): Unit =
        writes.withLock {
            val old = store.user(id) ?: throw EntityNotFound()
            val next =
                old.changed(
                    "fio" to text(fio ?: old.value("fio")),
                    "phone" to text(phone ?: old.value("phone")),
                    "phone_prev" to
                        text(if (phone != null && phone != old.value("phone")) old.value("phone") else old.value("phone_prev")),
                    "updated_at" to text(clock.after(old.value("updated_at"))),
                )
            store.insert("dim_users", listOf(next))
        }

    suspend fun recordReading(
        id: ULong,
        user: ULong,
        gauge: ULong,
        tariff: ULong,
        day: String,
        dayKw: ULong,
        nightKw: ULong,
    ): Unit =
        writes.withLock {
            requireRealty(id)
            if (!store.exists("dim_users", user) || !store.exists("dim_gauges", gauge) ||
                !store.exists("dim_tariffs", tariff)
            ) {
                throw EntityNotFound()
            }
            val old = store.readings(id, day, day).singleOrNull()
            store.insert(
                "fact_meter_readings",
                listOf(
                    obj(
                        "realty_id" to number(id),
                        "user_id" to number(user),
                        "tariff_id" to number(tariff),
                        "gauge_id" to number(gauge),
                        "reading_date" to text(day),
                        "day_kw" to number(dayKw),
                        "night_kw" to number(nightKw),
                        "created_at" to text(clock.after(old?.value("created_at"))),
                    ),
                ),
            )
        }

    suspend fun installGauge(
        id: ULong,
        gauge: ULong,
    ): Unit =
        writes.withLock {
            requireRealty(id)
            if (!store.exists("dim_gauges", gauge)) throw EntityNotFound()
            val old = store.binding(id)
            val version = old?.id("version") ?: 0uL
            check(version < ULong.MAX_VALUE) { "Gauge version exhausted" }
            val next =
                obj(
                    "gauge_id" to number(gauge),
                    "realty_id" to number(id),
                    "created_at" to text(clock.after(null)),
                    "sign" to number(1L),
                    "version" to number(version + 1uL),
                )
            store.insert("fact_gauge_bindings", listOfNotNull(old?.changed("sign" to number(-1L)), next))
        }

    private suspend fun requireRealty(id: ULong) {
        if (store.realty(id) == null) throw EntityNotFound()
    }
}
