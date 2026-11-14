package lab

data class Config(
    val appPort: Int,
    val host: String,
    val httpPort: Int,
    val user: String,
    val password: String,
    val database: String,
) {
    init {
        require(appPort in 1..65535 && httpPort in 1..65535)
        require(database.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) { "Invalid database identifier" }
        require(host.matches(Regex("[a-zA-Z0-9.:-]+"))) { "Invalid ClickHouse host" }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = requireNotNull(env[name]) { "Missing $name" }
            return Config(
                required("APP_PORT").toInt(),
                required("CLICKHOUSE_HOST"),
                required("CLICKHOUSE_HTTP_PORT").toInt(),
                required("CLICKHOUSE_USER"),
                required("CLICKHOUSE_PASSWORD"),
                required("CLICKHOUSE_DATABASE"),
            )
        }
    }
}
