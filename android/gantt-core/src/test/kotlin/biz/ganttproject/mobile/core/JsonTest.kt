/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A hand-written parser has to earn its place; these are the cases that
 * distinguish "works on the happy path" from "safe on real API output".
 */
class JsonTest {

  @Test
  fun `parses a nested object`() {
    val value = Json.parse("""{"a":1,"b":{"c":"x"},"d":[1,2]}""")
    val obj = value.asObject()!!
    assertEquals(1.0, obj["a"].asDouble())
    assertEquals("x", obj["b"].asObject()!!["c"].asString())
    assertEquals(2, obj["d"].asArray()!!.size)
  }

  @Test
  fun `parses the literals`() {
    val obj = Json.parse("""{"t":true,"f":false,"n":null}""").asObject()!!
    assertEquals(true, obj["t"].asBoolean())
    assertEquals(false, obj["f"].asBoolean())
    assertTrue(obj["n"] is JsonValue.JsonNull)
  }

  @Test
  fun `parses negative numbers and exponents`() {
    assertEquals(-42.0, Json.parse("-42").asDouble())
    assertEquals(1500.0, Json.parse("1.5e3").asDouble())
    assertEquals(0.0015, Json.parse("1.5E-3").asDouble()!!, 1e-12)
  }

  @Test
  fun `parses escapes including unicode`() {
    assertEquals(
      "quote\" back\\ slash/ tab\t newline\n ü",
      Json.parse(""""quote\" back\\ slash\/ tab\t newline\n ü"""").asString()
    )
  }

  @Test
  fun `handles empty containers`() {
    assertEquals(0, Json.parse("[]").asArray()!!.size)
    assertEquals(0, Json.parse("{}").asObject()!!.size)
    assertEquals(0, Json.parse("""[ ]""").asArray()!!.size)
  }

  @Test
  fun `tolerates whitespace everywhere`() {
    val obj = Json.parse("  {  \"a\" :  [ 1 , 2 ]  }  ").asObject()!!
    assertEquals(2, obj["a"].asArray()!!.size)
  }

  @Test
  fun `accessors return null on a type mismatch instead of throwing`() {
    val obj = Json.parse("""{"a":"text"}""").asObject()!!
    assertNull(obj["a"].asDouble())
    assertNull(obj["a"].asArray())
    assertNull(obj["missing"].asString())
  }

  @Test
  fun `rejects trailing content`() {
    // Silently ignoring what follows would let a truncated-then-concatenated
    // response parse as if it were fine.
    assertTrue(runCatching { Json.parse("""{"a":1} extra""") }.isFailure)
  }

  @Test
  fun `rejects malformed input`() {
    listOf("{", "[1,", """{"a"}""", """{"a":}""", "tru", "", "'single'").forEach { bad ->
      assertTrue(
        runCatching { Json.parse(bad) }.exceptionOrNull() is JsonParseException,
        "should have been rejected: '$bad'"
      )
    }
  }

  @Test
  fun `rejects an unterminated string`() {
    assertTrue(runCatching { Json.parse(""""no closing quote""") }.isFailure)
  }

  @Test
  fun `keeps object key order`() {
    val keys = Json.parse("""{"z":1,"a":2,"m":3}""").asObject()!!.keys.toList()
    assertEquals(listOf("z", "a", "m"), keys)
  }
}
