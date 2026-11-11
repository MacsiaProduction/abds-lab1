package lab

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CompatibilityTest {
    @Test fun `all successful endpoints and state transitions`() =
        testApplication {
            val store = FakeStore()
            application { module(store, TestClock()) }

            suspend fun getObject(path: String): JsonObject {
                val response = client.get(path)
                assertThat(response.status).describedAs(response.bodyAsText()).isEqualTo(HttpStatusCode.OK)
                assertThat(response.contentType()!!.match(ContentType.Application.Json)).isTrue()
                return json.parseToJsonElement(response.bodyAsText()).jsonObject
            }

            suspend fun patch(body: String) {
                val response =
                    client.patch("/users/1") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
                assertThat(response.bodyAsText()).isEqualTo("")
            }
            assertThat(client.get("/ping").bodyAsText()).isEqualTo("")
            assertThat(getObject("/users/1").value("fio")).isEqualTo("Old name")
            patch("""{"fio":"New name"}""")
            patch("""{"phone":"two"}""")
            patch("""{"phone":"three"}""")
            patch("""{"phone":"three"}""")
            val user = getObject("/users/1")
            assertThat(user.value("fio")).isEqualTo("New name")
            assertThat(user.value("phone")).isEqualTo("three")
            assertThat(user.value("phone_prev")).isEqualTo("two")
            assertThat(getObject("/realty/1").value("address")).isEqualTo("Test street")
            assertThat(getObject("/tariffs?date=2025-10-31").value("price_day_kwph")).isEqualTo("500")
            assertThat(getObject("/tariffs?date=2025-11-01").value("price_day_kwph")).isEqualTo("620")
            val empty = getObject("/realty/1/readings?date_from=2026-10-01&date_to=2026-10-01")
            assertThat(empty["readings"]).isEqualTo(JsonArray(emptyList()))
            assertThat(empty.value("count")).isEqualTo("0")
            for (gauge in listOf(1, 2)) {
                val response =
                    client.post("/realty/1/readings") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"user_id":1,"gauge_id":$gauge,"tariff_id":1,"reading_date":"2026-10-01","day_kw":0,"night_kw":4294967295}""",
                        )
                    }
                assertThat(response.status).describedAs(response.bodyAsText()).isEqualTo(HttpStatusCode.Created)
                assertThat(response.bodyAsText()).isEqualTo("")
            }
            assertThat(store.readings.size).isEqualTo(2)
            assertThat(store.readings[0].value("created_at") < store.readings[1].value("created_at")).isTrue()
            val current = getObject("/realty/1/readings?date_from=2026-10-01&date_to=2026-10-01")
            assertThat(current.value("count")).isEqualTo("1")
            assertThat(
                current
                    .getValue("readings")
                    .jsonArray
                    .single()
                    .jsonObject
                    .value("gauge_id"),
            ).isEqualTo("2")
            assertThat(getObject("/realty/1/invoices?limit=0").value("count")).isEqualTo("1")
            assertThat(getObject("/realty/1/invoices?offset=100")["invoices"]).isEqualTo(JsonArray(emptyList()))
            assertThat(getObject("/realty/1/invoices").getValue("invoices").jsonArray.size).isEqualTo(1)
            for (gauge in listOf(2, 2, 1)) {
                val response =
                    client.post("/realty/1/gauges") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"gauge_id":$gauge}""")
                    }
                assertThat(response.status).isEqualTo(HttpStatusCode.Created)
                assertThat(response.bodyAsText()).isEqualTo("")
            }
            assertThat(store.binding(1uL)!!.value("gauge_id")).isEqualTo("1")
            assertThat(store.binding(1uL)!!.value("version")).isEqualTo("4")
            assertThat(store.bindings.size).isEqualTo(7)
            // Cancellation copies every original field except sign.
            assertThat(store.bindings[1]).isEqualTo(store.bindings[0].changed("sign" to number(-1L)))
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/users/999", "/realty/999", "/realty/999/invoices",
            "/realty/999/readings?date_from=2026-10-01&date_to=2026-10-31", "/tariffs?date=1900-01-01",
        ],
    )
    fun `unknown entities are JSON 404`(path: String) =
        testApplication {
            application { module(FakeStore(), TestClock()) }
            val response = client.get(path)
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(json.parseToJsonElement(response.bodyAsText()).jsonObject.value("message")).isEqualTo("Not found")
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tariffs", "/tariffs?date=2026-02-30", "/tariffs?date=bad",
            "/realty/1/invoices?limit=-1", "/realty/1/invoices?offset=1.5",
            "/realty/1/invoices?limit=18446744073709551616", "/users/bad",
            "/realty/1/readings?date_from=2026-10-02&date_to=2026-10-01",
            "/realty/1/readings?date_from=2026-10-01",
        ],
    )
    fun `invalid query parameters are JSON 400`(path: String) =
        testApplication {
            application { module(FakeStore(), TestClock()) }
            val response = client.get(path)
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .value("message")
                    .startsWith("invalid"),
            ).isTrue()
        }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "null", "[]", "{", "{\"fio\":null}", "{\"fio\":1}", "{\"phone\":\"\"}"])
    fun `invalid patches do not write`(body: String) =
        testApplication {
            val store = FakeStore()
            application { module(store, TestClock()) }
            val response =
                client.patch("/users/1") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(store.users[1uL]!!.value("fio")).isEqualTo("Old name")
        }

    @Test fun `unsigned IDs are encoded as JSON numbers and quoted strings stay data`() =
        testApplication {
            val store = FakeStore()
            store.users[ULong.MAX_VALUE] = store.users.getValue(1uL).changed("id" to number(ULong.MAX_VALUE))
            application { module(store, TestClock()) }
            val response = client.get("/users/${ULong.MAX_VALUE}")
            val id =
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
            assertThat(id.isString).isFalse()
            assertThat(id.content).isEqualTo(ULong.MAX_VALUE.toString())
            val name = "O'Reilly; DROP DATABASE energosbyt; --"
            val patch =
                client.patch("/users/1") {
                    contentType(ContentType.Application.Json)
                    setBody(obj("fio" to text(name)).toString())
                }
            assertThat(patch.status).isEqualTo(HttpStatusCode.NoContent)
            assertThat(store.users.getValue(1uL).value("fio")).isEqualTo(name)
        }

    @Test fun `write failure returns 500 and preserves state`() =
        testApplication {
            val store = FakeStore().apply { failWrites = true }
            application { module(store, TestClock()) }
            val response =
                client.patch("/users/1") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"phone":"two"}""")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
            assertThat(response.bodyAsText().contains("Simulated")).isFalse()
            assertThat(store.users.getValue(1uL).value("phone")).isEqualTo("one")
        }

    @Test fun `invalid counters missing references and unknown writes`() =
        testApplication {
            val store = FakeStore()
            application { module(store, TestClock()) }
            for (value in listOf("-1", "4294967296", "1.1", "null", "\"1\"")) {
                val response =
                    client.post("/realty/1/readings") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"user_id":1,"gauge_id":1,"tariff_id":1,"reading_date":"2026-10-01","day_kw":$value,"night_kw":0}""")
                    }
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                assertThat(json.parseToJsonElement(response.bodyAsText()).jsonObject.value("message")).isEqualTo("invalid \"day_kw\" field")
            }
            for (field in listOf("user_id", "gauge_id", "tariff_id")) {
                val payload =
                    json
                        .parseToJsonElement(
                            """{"user_id":1,"gauge_id":1,"tariff_id":1,"reading_date":"2026-10-01","day_kw":0,"night_kw":0}""",
                        ).jsonObject
                val response =
                    client.post("/realty/1/readings") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            payload.changed(field to number(999L)).toString(),
                        )
                    }
                assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            }
            assertThat(
                client
                    .patch("/users/999") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"fio":"X"}""")
                    }.status,
            ).isEqualTo(HttpStatusCode.NotFound)
            assertThat(
                client
                    .post("/realty/999/gauges") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"gauge_id":1}""")
                    }.status,
            ).isEqualTo(HttpStatusCode.NotFound)
            assertThat(
                client
                    .post("/realty/1/gauges") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"gauge_id":999}""")
                    }.status,
            ).isEqualTo(HttpStatusCode.NotFound)
            assertThat(
                client
                    .post("/realty/1/gauges") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status,
            ).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(store.readings.isEmpty()).isTrue()
        }

    @Test fun `request size is bounded and writes to missing references stay absent`() =
        testApplication {
            val store = FakeStore()
            application { module(store, TestClock()) }
            assertThat(
                client
                    .patch("/users/1") {
                        contentType(ContentType.Application.Json)
                        setBody("x".repeat(70000))
                    }.status,
            ).isEqualTo(HttpStatusCode.PayloadTooLarge)
            val response =
                client.post("/realty/999/readings") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{
                "user_id":1,"gauge_id":1,"tariff_id":1,
                "reading_date":"2026-10-01","day_kw":0,"night_kw":0
            }""",
                    )
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(store.readings.isEmpty()).isTrue()
        }

    @Test fun `date storage bounds are validated without truncation`() =
        testApplication {
            application { module(FakeStore(), TestClock()) }
            val empty = client.get("/realty/1/readings?date_from=1900-01-01&date_to=1900-12-31")
            assertThat(empty.status).isEqualTo(HttpStatusCode.OK)
            assertThat(json.parseToJsonElement(empty.bodyAsText()).jsonObject.value("count")).isEqualTo("0")
            for (day in listOf("1969-12-31", "2149-06-07")) {
                val response =
                    client.post("/realty/1/readings") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{
                    "user_id":1,"gauge_id":1,"tariff_id":1,
                    "reading_date":"$day","day_kw":0,"night_kw":0
                }""",
                        )
                    }
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            }
        }
}
