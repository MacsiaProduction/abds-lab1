package lab

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException

private class ApiError(
    val status: HttpStatusCode,
    override val message: String,
) : RuntimeException(message)

private fun invalid(
    field: String,
    location: String = "field",
): Nothing = throw ApiError(HttpStatusCode.BadRequest, "invalid \"$field\" $location")

private fun missing(): Nothing = throw ApiError(HttpStatusCode.NotFound, "Not found")

private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun timestamp(instant: Instant): String = timestampFormat.format(instant.atOffset(ZoneOffset.UTC))

fun parseTimestamp(value: String): Instant = LocalDateTime.parse(value, timestampFormat).toInstant(ZoneOffset.UTC)

fun interface VersionClock {
    suspend fun after(previous: String?): String
}

class WallClock(
    private val clock: Clock = Clock.systemUTC(),
) : VersionClock {
    override suspend fun after(previous: String?): String =
        withTimeoutOrNull(5000) {
            val old = previous?.let(::parseTimestamp)?.epochSecond ?: Long.MIN_VALUE
            var now = clock.instant()
            while (now.epochSecond <= old) {
                delay(20)
                now = clock.instant()
            }
            timestamp(now)
        } ?: error("Clock did not advance beyond stored version")
}

private fun date(
    raw: String?,
    field: String,
    location: String = "parameter",
): String {
    if (raw == null || !raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) invalid(field, location)
    try {
        LocalDate.parse(raw)
    } catch (_: DateTimeException) {
        invalid(field, location)
    }
    return raw
}

private fun integer(
    element: JsonElement?,
    field: String,
    maximum: ULong = ULong.MAX_VALUE,
): ULong {
    val primitive = element as? JsonPrimitive ?: invalid(field)
    if (primitive.isString || !primitive.content.matches(Regex("[0-9]+"))) invalid(field)
    return primitive.content.toULongOrNull()?.takeIf { it <= maximum } ?: invalid(field)
}

private fun string(
    element: JsonElement?,
    field: String,
): String {
    val primitive = element as? JsonPrimitive ?: invalid(field)
    if (!primitive.isString || primitive.content.isBlank()) invalid(field)
    return primitive.content
}

private fun ApplicationCall.id(): ULong =
    parameters["id"]?.toULongOrNull()
        ?: throw ApiError(HttpStatusCode.BadRequest, "invalid \"id\" parameter")

private fun ApplicationCall.pagination(
    name: String,
    default: ULong,
): ULong {
    val raw = request.queryParameters[name] ?: return default
    if (!raw.matches(Regex("[0-9]+"))) invalid(name, "parameter")
    return raw.toULongOrNull() ?: invalid(name, "parameter")
}

private suspend fun ApplicationCall.body(): JsonObject {
    val bytes = withContext(Dispatchers.IO) { receiveChannel().toInputStream().use { it.readNBytes(65537) } }
    if (bytes.size > 65536) throw ApiError(HttpStatusCode.PayloadTooLarge, "Request body too large")
    return try {
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (
        _: IllegalArgumentException,
    ) {
        throw ApiError(HttpStatusCode.BadRequest, "invalid JSON body")
    }
}

fun Application.module(
    store: Store,
    clock: VersionClock = WallClock(),
) {
    install(ContentNegotiation) { json(lab.json) }
    install(StatusPages) {
        exception<ApiError> { call, error -> call.respond(error.status, obj("message" to text(error.message))) }
        exception<Throwable> { call, error ->
            if (error is CancellationException) throw error
            call.application.environment.log
                .error("Request failed: ${error.javaClass.simpleName}")
            call.respond(HttpStatusCode.InternalServerError, obj("message" to text("Internal server error")))
        }
    }
    val writes = Mutex()

    suspend fun requireRealty(id: ULong) {
        if (store.realty(id) == null) missing()
    }
    routing {
        get("/ping") { call.respondText("") }
        get("/users/{id}") {
            val row = store.user(call.id()) ?: missing()
            call.respond(row.project("id", "fio", "phone", "phone_prev"))
        }
        patch("/users/{id}") {
            val id = call.id()
            val body = call.body()
            if (body.keys.any { it !in setOf("fio", "phone") }) invalid(body.keys.first { it !in setOf("fio", "phone") })
            if (body.isEmpty()) invalid("fio")
            val fio = if ("fio" in body) string(body["fio"], "fio") else null
            val phone = if ("phone" in body) string(body["phone"], "phone") else null
            writes.withLock {
                val old = store.user(id) ?: missing()
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
            call.respond(HttpStatusCode.NoContent)
        }
        get("/realty/{id}") {
            call.respond(
                (store.realty(call.id()) ?: missing()).project("id", "address", "square", "type", "number", "rooms", "floor", "porch"),
            )
        }
        get("/tariffs") {
            val date = date(call.request.queryParameters["date"], "date")
            val row = store.tariff(date) ?: missing()
            call.respond(row.project("id", "name", "price_day_kwph", "price_night_kwph", "valid_from", "valid_to"))
        }
        get("/realty/{id}/readings") {
            val id = call.id()
            val from = date(call.request.queryParameters["date_from"], "date_from")
            val to = date(call.request.queryParameters["date_to"], "date_to")
            if (from > to) invalid("date_from", "parameter")
            requireRealty(id)
            val firstDate = LocalDate.ofEpochDay(0).toString()
            val lastDate = LocalDate.ofEpochDay(65535).toString()
            val rows =
                if (to < firstDate || from > lastDate) {
                    emptyList()
                } else {
                    store
                        .readings(id, maxOf(from, firstDate), minOf(to, lastDate))
                        .map { it.project("reading_date", "user_id", "tariff_id", "gauge_id", "day_kw", "night_kw") }
                }
            call.respond(obj("readings" to JsonArray(rows), "count" to number(rows.size.toLong())))
        }
        post("/realty/{id}/readings") {
            val id = call.id()
            val body = call.body()
            val day = date((body["reading_date"] as? JsonPrimitive)?.takeIf { it.isString }?.content, "reading_date", "field")
            if (LocalDate.parse(day).toEpochDay() !in 0L..65535L) invalid("reading_date")
            val user = integer(body["user_id"], "user_id")
            val gauge = integer(body["gauge_id"], "gauge_id")
            val tariff = integer(body["tariff_id"], "tariff_id")
            val dayKw = integer(body["day_kw"], "day_kw", UInt.MAX_VALUE.toULong())
            val nightKw = integer(body["night_kw"], "night_kw", UInt.MAX_VALUE.toULong())
            writes.withLock {
                requireRealty(id)
                if (!store.exists("dim_users", user) || !store.exists("dim_gauges", gauge) ||
                    !store.exists("dim_tariffs", tariff)
                ) {
                    missing()
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
            call.respond(HttpStatusCode.Created)
        }
        get("/realty/{id}/invoices") {
            val id = call.id()
            val limit = call.pagination("limit", 10uL)
            val offset = call.pagination("offset", 0uL)
            requireRealty(id)
            val (rows, count) = store.invoices(id, limit, offset)
            call.respond(
                obj(
                    "invoices" to
                        JsonArray(
                            rows.map { it.project("tariff_id", "gauge_id", "day_kw", "night_kw", "price", "deadline", "created_at") },
                        ),
                    "count" to number(count),
                ),
            )
        }
        post("/realty/{id}/gauges") {
            val id = call.id()
            val gauge = integer(call.body()["gauge_id"], "gauge_id")
            writes.withLock {
                requireRealty(id)
                if (!store.exists("dim_gauges", gauge)) missing()
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
            call.respond(HttpStatusCode.Created)
        }
    }
}
