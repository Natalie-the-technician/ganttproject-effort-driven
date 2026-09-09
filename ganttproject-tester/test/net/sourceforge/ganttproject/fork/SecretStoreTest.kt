/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.sourceforge.ganttproject.fork

import junit.framework.TestCase

/**
 * The store for secrets.
 *
 * WHICH OF THESE MEASURE SOMETHING WHERE, so that the count is not read as more than it is:
 *
 *  * The tests on markers, on legacy plain text, on a value from another platform and on the
 *    CHOICE of backend run everywhere and measure everywhere. The choice in particular: it is the
 *    only way the sentence "under Windows nothing changes" can be checked without Windows, and it
 *    is checked here, on Linux.
 *  * [testNothingLooksProtectedWhenNothingCanBe] asserts in BOTH cases -- with a store and without
 *    one. It is never green by having done nothing.
 *  * [testTheRoundTripWhereThereIsAStore] and [testAHandleThatWasNeverStoredGivesNoForeignSecret]
 *    need a real store. Where there is none they return, and JUnit then counts them as passed
 *    although they measured nothing -- the same bookkeeping error described at length in
 *    `TokenEncryptionTest`, and for the same reason not solved with `assumeTrue`. THEY WERE RUN
 *    against a real gnome-keyring on 09.09.2026; the log is in the report of that day. A green
 *    result from a machine without a keyring says nothing about them.
 *
 * NO REAL CREDENTIALS ARE USED ANYWHERE HERE. Every secret in this file is made up.
 */
class SecretStoreTest : TestCase() {

  /**
   * A value without a marker is legacy data and has to come back unchanged. If that were lost,
   * all stored passwords would be unusable after the change -- and quietly so, because a wrong
   * password looks like a server problem.
   */
  fun testPlainTextFromBeforeStaysReadable() {
    assertEquals("geheim123", SecretStore.reveal("geheim123"))
    assertEquals("", SecretStore.reveal(""))
    // Also something that happens to look like Base64 but carries no marker.
    assertEquals("AAECAwQ=", SecretStore.reveal("AAECAwQ="))
  }

  /**
   * A marked but unusable value must not throw. It can come from a different machine; the
   * consequence is then a rejected login, not a crash at startup.
   */
  fun testUndecryptableValueDoesNotThrow() {
    assertEquals("dpapi:###keinbase64###", SecretStore.reveal("dpapi:###keinbase64###"))
    assertEquals("libsecret:###kein-handle###", SecretStore.reveal("libsecret:###kein-handle###"))
    assertEquals("keychain:###kein-handle###", SecretStore.reveal("keychain:###kein-handle###"))
  }

  /** An empty secret is never stored -- there would be nothing to protect. */
  fun testEmptySecretIsNeverStored() {
    assertNull(SecretStore.protect("webdav:https://example.invalid|natalie", ""))
  }

  /** Without an alias nothing is stored either: the keyring backends would have no name for it. */
  fun testWithoutAnAliasNothingIsStored() {
    assertNull(SecretStore.protect("", "ein-ausgedachtes-passwort"))
  }

  /**
   * THE WINDOWS GUARD, and the reason [secretBackendsFor] is a pure function.
   *
   * Everything else about DPAPI needs Windows to run. The CHOICE does not, and the choice is where
   * a change would do its damage: if a later edit let another backend win on Windows, every
   * password already stored there would become unreadable at the next start, quietly, and would
   * look like a server problem. This runs on Linux and would catch it.
   */
  fun testOnWindowsItIsStillDpapiAndNothingElse() {
    for (osName in listOf("Windows 10", "Windows 11", "Windows Server 2022", "Windows XP")) {
      val backends = secretBackendsFor(osName)
      assertEquals("exactly one way on Windows, and no fallback behind it: " + osName,
        1, backends.size)
      assertEquals(osName + " must get DPAPI", "DPAPI", backends[0].name)
      assertEquals("the marker of every file written before this change", "dpapi:",
        backends[0].marker)
      assertTrue("DPAPI must not be made to depend on a probe: it is part of the system",
        backends[0].isAvailable())
    }
  }

  /** And the other way round: nothing that is not Windows may end up at DPAPI. */
  fun testOnlyWindowsGetsDpapi() {
    for (osName in listOf("Linux", "Mac OS X", "Darwin", "FreeBSD", "SunOS", "")) {
      val backends = secretBackendsFor(osName)
      assertEquals("one way per platform: " + osName, 1, backends.size)
      assertFalse(osName + " must not get DPAPI", "dpapi:" == backends[0].marker)
    }
    assertEquals("keychain:", secretBackendsFor("Mac OS X")[0].marker)
    assertEquals("keychain:", secretBackendsFor("Darwin")[0].marker)
    assertEquals("libsecret:", secretBackendsFor("Linux")[0].marker)
    assertEquals("libsecret:", secretBackendsFor("FreeBSD")[0].marker)
  }

  /**
   * The markers have to stay apart, and none may begin with another.
   *
   * If one were a prefix of another, a value of the longer kind would be recognised as the shorter
   * kind first and handed to the wrong backend -- with a handle it cannot use, and on Windows with
   * a string it would try to decrypt.
   */
  fun testTheMarkersAreDistinctAndNoneBeginsWithAnother() {
    val markers = listOf("Windows 10", "Mac OS X", "Linux").map { secretBackendsFor(it)[0].marker }
    assertEquals("three platforms, three different markers", markers.size, markers.toSet().size)
    for (one in markers) {
      for (other in markers) {
        if (one != other) {
          assertFalse("'" + one + "' must not begin with '" + other + "'", one.startsWith(other))
        }
      }
      assertFalse("a marker containing a tab would take the WebDAV server list apart",
        one.contains("\t"))
      assertFalse("a marker containing a line break would invent a second server",
        one.contains("\n"))
    }
  }

  /**
   * A value written on another platform is recognised as protected and passed through unchanged.
   *
   * Both halves matter. RECOGNISED, so that the next save does not take a Windows ciphertext for a
   * plain-text password and hand it to the keyring as one. UNCHANGED, so that a settings file
   * carried from a Windows machine to a Linux one and back still works on the Windows machine.
   */
  fun testAValueFromAnotherPlatformIsRecognisedAndPassedThrough() {
    for (foreign in listOf("dpapi:AQAAANCMnd8=", "libsecret:aZm9v", "keychain:aZm9v")) {
      assertTrue(foreign + " must count as protected", SecretStore.isProtected(foreign))
      if (!foreign.startsWith(currentMarkerOrNothing())) {
        assertEquals(foreign + " belongs to another platform and must not be touched",
          foreign, SecretStore.reveal(foreign))
      }
    }
    assertFalse("a bare secret carries no marker", SecretStore.isProtected("geheim123"))
    assertFalse(SecretStore.isProtected(""))
  }

  /**
   * THE GUARD AGAINST THE APPEARANCE OF SECURITY, and it asserts in both cases.
   *
   * Where there is no store, nothing may be written -- not something that looks encrypted. That is
   * the failure this whole change could most easily have introduced: a backend that quietly
   * scrambles the secret with a key lying next to it would pass every round-trip test in this file
   * and protect nobody.
   *
   * Where there IS a store, the opposite has to hold: something is written, it carries a marker,
   * and the secret is not in it.
   */
  fun testNothingLooksProtectedWhenNothingCanBe() {
    val secret = "ausgedacht-4711-kein-echtes-geheimnis"
    val stored = SecretStore.protect("webdav:https://example.invalid|natalie", secret)
    if (SecretStore.isAvailable) {
      assertNotNull("with a store (" + SecretStore.backendName + ") something must be written",
        stored)
      assertTrue("it has to be recognisable as protected: " + stored,
        SecretStore.isProtected(stored!!))
      assertFalse("the secret must not be in it", stored.contains(secret))
    } else {
      assertNull("without a store NOTHING may be written -- not even something that looks safe",
        stored)
      assertEquals("and there is no backend to name", "none", SecretStore.backendName)
    }
  }

  /**
   * The actual purpose: what gets stored no longer contains the secret, and the secret comes back
   * unchanged -- byte for byte, including the characters most likely to be mangled on the way.
   *
   * Both halves together. "Comes back" alone would also be passed by a store that protects nothing
   * at all.
   *
   * NEEDS A REAL STORE. Where there is none this returns and JUnit counts it as passed although it
   * measured nothing. See the note at the top of this class: it was run for real.
   */
  fun testTheRoundTripWhereThereIsAStore() {
    if (!SecretStore.isAvailable) {
      return
    }
    val secret = "Passwort mit Umlauten-äöü und Zeichen-!\"§\$%&/()" +
      " \t und\nZeilenumbruch am Ende\n"
    val stored = SecretStore.protect("webdav:https://example.invalid|natalie", secret)
    assertNotNull("with a store there must be something to write", stored)
    assertFalse("the stored value must not contain the secret", stored!!.contains(secret))
    assertFalse("no tab: it would take the WebDAV server list apart", stored.contains("\t"))
    assertFalse("no line break: it would invent a second server", stored.contains("\n"))
    assertEquals("and it has to come back exactly as it went in", secret, SecretStore.reveal(stored))
  }

  /**
   * THE GUARD AGAINST MIXED-UP KEYS: a secret that was never stored must not give back somebody
   * else's.
   *
   * With a keyring the settings file holds only a REFERENCE, and a reference that points nowhere is
   * a new way to go wrong that DPAPI did not have. Fetching a wrong secret would be worse than
   * fetching none: the WebDAV login would go out with another server's password.
   *
   * NEEDS A REAL STORE for its first half; the second half runs everywhere.
   */
  fun testAHandleThatWasNeverStoredGivesNoForeignSecret() {
    val known = "das-geheimnis-des-ersten-eintrags-4711"
    val stored = SecretStore.protect("webdav:https://eins.invalid|natalie", known)
    if (SecretStore.isAvailable) {
      assertNotNull(stored)
      val neverStored = stored!!.replaceRange(stored.length - 6, stored.length, "ZZZZZZ")
      assertFalse("the test would be pointless if it were the same handle", neverStored == stored)
      val answer = SecretStore.reveal(neverStored)
      assertFalse("a handle that was never stored must not give back another secret",
        answer == known)
      assertEquals("it stays what it was: an unresolvable reference", neverStored, answer)
      assertEquals("and the real one is still there", known, SecretStore.reveal(stored))
    } else {
      assertNull(stored)
      // Runs everywhere: an invented reference must never become a secret.
      assertEquals("libsecret:aZ2lidHNuaWNodA", SecretStore.reveal("libsecret:aZ2lidHNuaWNodA"))
    }
  }

  /** The marker of the backend in use, or a string that is no prefix of anything. */
  private fun currentMarkerOrNothing(): String =
    if (SecretStore.isAvailable) {
      secretBackendsFor(System.getProperty("os.name", ""))[0].marker
    } else {
      " kein-marker"
    }
}
