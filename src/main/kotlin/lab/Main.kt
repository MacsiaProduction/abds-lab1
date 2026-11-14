package lab

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main(args: Array<String>) =
    runBlocking {
        val command = args.firstOrNull() ?: "serve"
        require(command in setOf("serve", "gen", "schema", "load", "setup", "teardown")) { "Unknown command" }
        val directory = Path.of("data")
        if (command == "gen") {
            generate(directory)
            return@runBlocking
        }
        val config = Config.fromEnvironment()
        val store = ClickHouse(config)
        when (command) {
            "serve" -> {
                embeddedServer(Netty, port = config.appPort, host = "0.0.0.0") { module(store) }.start(wait = true)
            }

            "schema" -> {
                store.schema()
            }

            "load" -> {
                store.load(directory)
            }

            "setup" -> {
                store.schema()
                generate(directory)
                store.load(directory)
            }

            "teardown" -> {
                store.teardown()
            }
        }
    }
