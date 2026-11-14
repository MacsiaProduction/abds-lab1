package lab

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class TransportTest {
    @TempDir lateinit var directory: Path

    data class Request(
        val query: Map<String, String>,
        val body: String,
        val authorization: String?,
    )

    private fun server(block: (ClickHouse, MutableList<Request>, (String, Int) -> Unit) -> Unit) {
        val requests = mutableListOf<Request>()
        var body = ""
        var status = 200
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query =
                exchange.requestURI.rawQuery.split('&').associate { part ->
                    val pair = part.split('=', limit = 2)
                    URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair[1], UTF_8)
                }
            requests +=
                Request(
                    query,
                    exchange.requestBody.use { it.readBytes().toString(UTF_8) },
                    exchange.requestHeaders.getFirst("Authorization"),
                )
            val bytes = body.toByteArray(UTF_8)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block(ClickHouse(Config(8080, "127.0.0.1", server.address.port, "test", "fake", "energosbyt")), requests) { b, s ->
                body = b
                status =
                    s
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun `values stay typed parameters and credentials stay out of URL`() =
        server { db, requests, reply ->
            runBlocking {
                reply("""{"id":18446744073709551615,"fio":"X"}""", 200)
                assertThat(db.user(ULong.MAX_VALUE)!!.id("id")).isEqualTo(ULong.MAX_VALUE)
                val request = requests.single()
                assertThat(request.query["param_id"]).isEqualTo(ULong.MAX_VALUE.toString())
                assertThat(request.query.getValue("query").contains("FINAL WHERE id={id:UInt64}")).isTrue()
                assertThat(request.query.values.any { it.contains("fake") }).isFalse()
                assertThat(request.authorization!!.startsWith("Basic ")).isTrue()
                reply("", 200)
                val unsafe = "O'Reilly; DROP DATABASE energosbyt; --"
                db.insert("dim_users", listOf(obj("fio" to text(unsafe))))
                assertThat(
                    requests
                        .last()
                        .query
                        .getValue("query")
                        .contains(unsafe),
                ).isFalse()
                assertThat(requests.last().body.contains(unsafe)).isTrue()
                assertThatThrownBy {
                    runBlocking {
                        db.insert(
                            "dim_users; DROP DATABASE x",
                            listOf(
                                obj("id" to number(1L)),
                            ),
                        )
                    }
                }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }

    @Test fun `database errors and malformed response are never accepted`() =
        server { db, _, reply ->
            reply("credential or SQL details", 500)
            assertThatThrownBy { runBlocking { db.user(1uL) } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageNotContaining("credential")
            reply("not JSON", 200)
            assertThatThrownBy { runBlocking { db.user(1uL) } }.isInstanceOf(IllegalArgumentException::class.java)
        }

    @Test fun `schema and loader refuse nonempty data and use explicit column order`() =
        server { db, requests, reply ->
            runBlocking {
                reply("", 200)
                db.schema()
                assertThat(requests.size).isEqualTo(8)
                assertThat(requests.none { "__DATABASE__" in it.query.getValue("query") }).isTrue()
                requests.clear()
                reply("""{"count":1}""", 200)
                assertThatThrownBy { runBlocking { db.load(directory) } }.isInstanceOf(IllegalStateException::class.java)
                assertThat(requests.size).isEqualTo(1)
                assertThat(requests.any { it.query.getValue("query").startsWith("INSERT") }).isFalse()
                requests.clear()
                for (table in tables) Files.writeString(directory.resolve("$table.csv"), "1,fixture\n")
                Files.writeString(directory.resolve("fact_meter_readings.csv"), "1,fixture\n".repeat(100001))
                // Only SELECT needs a JSON result; INSERT ignores this benign fake response.
                reply("""{"count":0}""", 200)
                db.load(directory)
                assertThat(requests.size).isEqualTo(15)
                assertThat(requests.take(7).all { it.query.getValue("query").startsWith("SELECT count()") }).isTrue()
                assertThat(requests.drop(7).all { it.query.getValue("query").contains("FORMAT CSV") }).isTrue()
                val batches = requests.drop(7).filter { it.query.getValue("query").contains("fact_meter_readings") }
                assertThat(batches.map { batch -> batch.body.count { it == '\n' } }).isEqualTo(listOf(100000, 1))
                assertThat(requests.drop(7).sumOf { request -> request.body.count { it == '\n' } }).isEqualTo(100007)
                db.teardown()
                assertThat(requests.last().query["query"]).isEqualTo("DROP DATABASE IF EXISTS `energosbyt` SYNC")
            }
        }

    @Test fun `identifier configuration rejects SQL injection`() {
        assertThatThrownBy {
            Config(
                8080,
                "localhost",
                8123,
                "",
                "",
                "x`; DROP DATABASE y",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
