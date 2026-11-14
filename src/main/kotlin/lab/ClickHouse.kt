package lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64

class ClickHouse(
    private val config: Config,
) : Store {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private suspend fun execute(
        sql: String,
        params: Map<String, String> = emptyMap(),
        data: String = "",
    ): String =
        withContext(Dispatchers.IO) {
            val query =
                mapOf(
                    "query" to sql,
                    "wait_end_of_query" to "1",
                    "output_format_json_quote_64bit_integers" to "0",
                ) + params.mapKeys { "param_${it.key}" }
            val encoded =
                query.entries.joinToString("&") { (k, v) ->
                    URLEncoder.encode(k, UTF_8) + "=" + URLEncoder.encode(v, UTF_8)
                }
            val uri = URI("http", null, config.host, config.httpPort, "/", null, null).toString() + "?" + encoded
            val credentials = Base64.getEncoder().encodeToString("${config.user}:${config.password}".toByteArray(UTF_8))
            val request =
                HttpRequest
                    .newBuilder(URI(uri))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Basic $credentials")
                    .POST(HttpRequest.BodyPublishers.ofString(data, UTF_8))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(8 * 1024 * 1024 + 1) }
            check(bytes.size <= 8 * 1024 * 1024) { "ClickHouse response too large" }
            check(response.statusCode() == 200) { "ClickHouse request failed (${response.statusCode()})" }
            bytes.toString(UTF_8)
        }

    private fun table(name: String): String {
        require(name in tables)
        return "`${config.database}`.$name"
    }

    private suspend fun select(
        sql: String,
        params: Map<String, String> = emptyMap(),
    ): List<JsonObject> =
        execute("$sql FORMAT JSONEachRow", params)
            .lineSequence()
            .filter(String::isNotBlank)
            .map { json.parseToJsonElement(it).jsonObject }
            .toList()

    override suspend fun user(id: ULong) =
        select(
            "SELECT * FROM ${table("dim_users")} FINAL WHERE id={id:UInt64}",
            mapOf("id" to id.toString()),
        ).singleOrNull()

    override suspend fun realty(id: ULong) =
        select(
            "SELECT * FROM ${table("dim_realty")} WHERE id={id:UInt64} LIMIT 1",
            mapOf("id" to id.toString()),
        ).singleOrNull()

    override suspend fun exists(
        table: String,
        id: ULong,
    ): Boolean {
        val final = if (table in listOf("dim_users", "dim_tariffs")) " FINAL" else ""
        return select(
            "SELECT id FROM ${table(table)}$final WHERE id={id:UInt64} LIMIT 1",
            mapOf("id" to id.toString()),
        ).isNotEmpty()
    }

    override suspend fun tariff(date: String) =
        select(
            "SELECT * FROM ${table("dim_tariffs")} FINAL WHERE valid_from<=toDateTime({date:String}, 'UTC') " +
                "AND toDateTime({date:String}, 'UTC')<valid_to ORDER BY id LIMIT 1",
            mapOf("date" to "$date 00:00:00"),
        ).singleOrNull()

    override suspend fun readings(
        id: ULong,
        from: String,
        to: String,
    ) = select(
        "SELECT * FROM ${table("fact_meter_readings")} FINAL WHERE realty_id={id:UInt64} " +
            "AND reading_date>={from:Date} AND reading_date<={to:Date} ORDER BY reading_date",
        mapOf("id" to id.toString(), "from" to from, "to" to to),
    )

    override suspend fun invoices(
        id: ULong,
        limit: ULong,
        offset: ULong,
    ): Pair<List<JsonObject>, ULong> {
        val params = mapOf("id" to id.toString(), "limit" to limit.toString(), "offset" to offset.toString())
        val count =
            select("SELECT count() AS count FROM ${table("fact_invoice")} WHERE realty_id={id:UInt64}", params)
                .single()
                .id("count")
        val rows =
            if (limit == 0uL) {
                emptyList()
            } else {
                select(
                    "SELECT * FROM ${table("fact_invoice")} WHERE realty_id={id:UInt64} " +
                        "ORDER BY created_at DESC LIMIT {limit:UInt64} OFFSET {offset:UInt64}",
                    params,
                )
            }
        return rows to count
    }

    override suspend fun binding(id: ULong): JsonObject? {
        val rows =
            select(
                "SELECT * FROM ${table("fact_gauge_bindings")} FINAL WHERE realty_id={id:UInt64} AND sign=1",
                mapOf("id" to id.toString()),
            )
        check(rows.size <= 1) { "Multiple active gauge bindings" }
        return rows.firstOrNull()
    }

    override suspend fun insert(
        table: String,
        rows: List<JsonObject>,
    ) {
        if (rows.isEmpty()) return
        val columns = rows.first().keys
        require(rows.all { it.keys == columns })
        require(columns.all { it.matches(Regex("[a-z_]+")) })
        execute(
            "INSERT INTO ${table(table)} (${columns.joinToString(",")}) FORMAT JSONEachRow",
            data = rows.joinToString("\n", postfix = "\n"),
        )
    }

    suspend fun schema() {
        val source = requireNotNull(javaClass.getResource("/schema.sql")).readText()
        source
            .replace("__DATABASE__", "`${config.database}`")
            .split(';')
            .filter(String::isNotBlank)
            .forEach { execute(it) }
    }

    suspend fun teardown() {
        execute("DROP DATABASE IF EXISTS `${config.database}` SYNC")
    }

    suspend fun load(directory: java.nio.file.Path) {
        // Refuse even a partial previous load: reset explicitly before replaying CSV.
        for (name in tables) {
            check(select("SELECT count() AS count FROM ${table(name)}").single().id("count") == 0uL) {
                "Nonempty database: run make teardown before setup/load"
            }
        }
        for (name in tables) {
            val columns = schemaColumns.getValue(name).joinToString(",")
            withContext(Dispatchers.IO) {
                java.nio.file.Files.newBufferedReader(directory.resolve("$name.csv")).use { reader ->
                    val batch = StringBuilder()
                    var count = 0
                    // Generated rows contain no embedded newlines; CSV escaping is still used for text.
                    while (true) {
                        val line = reader.readLine() ?: break
                        batch.append(line).append('\n')
                        count++
                        if (count == 100000) {
                            execute("INSERT INTO ${table(name)} ($columns) FORMAT CSV", data = batch.toString())
                            batch.setLength(0)
                            count = 0
                        }
                    }
                    if (count > 0) execute("INSERT INTO ${table(name)} ($columns) FORMAT CSV", data = batch.toString())
                }
            }
        }
    }
}
