@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package lab

import kotlinx.serialization.json.*

val json = Json { ignoreUnknownKeys = false }

fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))

fun text(value: String) = JsonPrimitive(value)

fun number(value: ULong): JsonPrimitive = JsonUnquotedLiteral(value.toString())

fun number(value: Long): JsonPrimitive = JsonPrimitive(value)

fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content

fun JsonObject.id(key: String) = value(key).toULong()

fun JsonObject.project(vararg keys: String) = JsonObject(keys.associateWith { getValue(it) })

fun JsonObject.changed(vararg fields: Pair<String, JsonElement>) = JsonObject(this + fields)
