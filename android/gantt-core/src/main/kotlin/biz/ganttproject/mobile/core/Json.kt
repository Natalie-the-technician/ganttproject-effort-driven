/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

/**
 * A very small JSON reader.
 *
 * Why not `org.json`: that ships with Android only, and this module must also
 * run on a plain JVM so the matching logic stays testable without an Android
 * SDK. Pulling in a full serialisation library for the handful of fields a
 * time-tracking API returns would be out of proportion.
 *
 * Covers the whole of JSON: objects, arrays, strings with escapes (including
 * `\uXXXX`), numbers with exponents, `true`/`false`/`null`.
 */
sealed interface JsonValue {
  data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue
  data class JsonArray(val items: List<JsonValue>) : JsonValue
  data class JsonString(val value: String) : JsonValue
  data class JsonNumber(val value: Double) : JsonValue
  data class JsonBoolean(val value: Boolean) : JsonValue
  data object JsonNull : JsonValue
}

class JsonParseException(message: String) : Exception(message)

fun JsonValue?.asObject(): Map<String, JsonValue>? = (this as? JsonValue.JsonObject)?.entries
fun JsonValue?.asArray(): List<JsonValue>? = (this as? JsonValue.JsonArray)?.items
fun JsonValue?.asString(): String? = (this as? JsonValue.JsonString)?.value
fun JsonValue?.asDouble(): Double? = (this as? JsonValue.JsonNumber)?.value
fun JsonValue?.asLong(): Long? = (this as? JsonValue.JsonNumber)?.value?.toLong()
fun JsonValue?.asBoolean(): Boolean? = (this as? JsonValue.JsonBoolean)?.value

object Json {
  fun parse(text: String): JsonValue {
    val parser = Parser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    if (!parser.atEnd()) throw JsonParseException("trailing characters at position ${parser.position}")
    return value
  }

  private class Parser(private val text: String) {
    var position = 0
      private set

    fun atEnd(): Boolean = position >= text.length

    fun skipWhitespace() {
      while (position < text.length && text[position].isWhitespace()) position++
    }

    fun parseValue(): JsonValue {
      skipWhitespace()
      if (atEnd()) throw JsonParseException("unexpected end of input")
      return when (val c = text[position]) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> JsonValue.JsonString(parseString())
        't' -> parseLiteral("true", JsonValue.JsonBoolean(true))
        'f' -> parseLiteral("false", JsonValue.JsonBoolean(false))
        'n' -> parseLiteral("null", JsonValue.JsonNull)
        else ->
          if (c == '-' || c.isDigit()) parseNumber()
          else throw JsonParseException("unexpected character '$c' at position $position")
      }
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
      if (!text.startsWith(literal, position)) {
        throw JsonParseException("expected '$literal' at position $position")
      }
      position += literal.length
      return value
    }

    private fun parseObject(): JsonValue {
      expect('{')
      val entries = LinkedHashMap<String, JsonValue>()
      skipWhitespace()
      if (peek() == '}') { position++; return JsonValue.JsonObject(entries) }
      while (true) {
        skipWhitespace()
        val key = parseString()
        skipWhitespace()
        expect(':')
        entries[key] = parseValue()
        skipWhitespace()
        when (peek()) {
          ',' -> position++
          '}' -> { position++; return JsonValue.JsonObject(entries) }
          else -> throw JsonParseException("expected ',' or '}' at position $position")
        }
      }
    }

    private fun parseArray(): JsonValue {
      expect('[')
      val items = mutableListOf<JsonValue>()
      skipWhitespace()
      if (peek() == ']') { position++; return JsonValue.JsonArray(items) }
      while (true) {
        items.add(parseValue())
        skipWhitespace()
        when (peek()) {
          ',' -> position++
          ']' -> { position++; return JsonValue.JsonArray(items) }
          else -> throw JsonParseException("expected ',' or ']' at position $position")
        }
      }
    }

    private fun parseString(): String {
      expect('"')
      val sb = StringBuilder()
      while (true) {
        if (atEnd()) throw JsonParseException("unterminated string")
        when (val c = text[position++]) {
          '"' -> return sb.toString()
          '\\' -> {
            if (atEnd()) throw JsonParseException("dangling escape")
            when (val esc = text[position++]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'b' -> sb.append('\b')
              'f' -> sb.append('')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (position + 4 > text.length) throw JsonParseException("truncated \\u escape")
                val hex = text.substring(position, position + 4)
                val code = hex.toIntOrNull(16)
                  ?: throw JsonParseException("invalid \\u escape '$hex'")
                sb.append(code.toChar())
                position += 4
              }
              else -> throw JsonParseException("unknown escape '\\$esc'")
            }
          }
          else -> sb.append(c)
        }
      }
    }

    private fun parseNumber(): JsonValue {
      val start = position
      if (peek() == '-') position++
      while (!atEnd() && text[position].isDigit()) position++
      if (!atEnd() && text[position] == '.') {
        position++
        while (!atEnd() && text[position].isDigit()) position++
      }
      if (!atEnd() && (text[position] == 'e' || text[position] == 'E')) {
        position++
        if (!atEnd() && (text[position] == '+' || text[position] == '-')) position++
        while (!atEnd() && text[position].isDigit()) position++
      }
      val slice = text.substring(start, position)
      val value = slice.toDoubleOrNull()
        ?: throw JsonParseException("not a valid number: '$slice'")
      return JsonValue.JsonNumber(value)
    }

    private fun peek(): Char? = if (atEnd()) null else text[position]

    private fun expect(c: Char) {
      skipWhitespace()
      if (atEnd() || text[position] != c) {
        throw JsonParseException("expected '$c' at position $position")
      }
      position++
    }
  }
}
