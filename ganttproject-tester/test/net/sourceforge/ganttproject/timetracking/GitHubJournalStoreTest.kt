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
package net.sourceforge.ganttproject.timetracking

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64

/**
 * The GitHub store against [FakeGitHub]. No request ever leaves the machine.
 *
 * The cases that carry the design are the conflict ones: what happens when somebody else wrote in
 * between, and what happens to a save that changes nothing. Everything above them is the
 * groundwork they stand on.
 *
 * The last test is the only one that opens a socket, and it is there for the transport rather than
 * the store: [JdkHttpExchange] is the one class the offline cases cannot exercise.
 */
class GitHubJournalStoreTest {

  private val path = "hours/haus.timelog"
  private val token = "test-token"

  private fun storeOn(
    github: FakeGitHub,
    branch: String? = null,
    withToken: String = token,
  ) = GitHubJournalStore(github, GitHubTarget("natalie", "stunden", path, branch), withToken)

  // --- reading ---------------------------------------------------------------------------------

  @Test
  fun `load on an empty repository is null and not a failure`() {
    val github = FakeGitHub()
    val store = storeOn(github)

    assertNull(store.load())
    // The distinction the whole first run depends on: nothing there is not the same as could not
    // look. Reporting a failure here would have the program complain on the day it is set up.
    assertNull(store.lastLoadFailure)
    assertEquals(1, github.requestCount)
  }

  @Test
  fun `load returns the stored text byte for byte`() {
    // Every trap in one string: umlauts and eszett (two bytes each in UTF-8), an emoji (four, a
    // surrogate pair in Kotlin), the field separator of the journal format, and a trailing newline
    // a careless trim would eat.
    val journal = "#gp-timelog 1 record|task|start|seconds|source|person|note|created\n" +
      "r1|t7|2026-09-10T08:00+02:00|3600|manual|Natalié Müller|Meßgerät geprüft 🛠|2026-09-10T09:00Z\n"
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, journal)

    val loaded = storeOn(github).load()

    assertEquals(journal, loaded)
    // Byte for byte, not merely equal-looking: a charset slip further up produces a string that
    // prints the same and hashes differently, and the journal's check file is a SHA-256 over
    // exactly these bytes.
    assertEquals(
      journal.toByteArray(Charsets.UTF_8).toList(),
      loaded!!.toByteArray(Charsets.UTF_8).toList(),
    )
  }

  @Test
  fun `load copes with the line breaks GitHub puts in its base64`() {
    // Long enough to be wrapped several times. The basic decoder throws on a line break inside the
    // payload; only the MIME decoder skips it. Nothing about a short journal would reveal that.
    val journal = (1..40).joinToString("\n") { "r$it|t1|2026-09-10|3600|manual|Natalie||" }
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, journal)

    // Proves the premise rather than assuming it: the fake really does wrap.
    val wrapped = FakeGitHub.wrapAt60(
      Base64.getEncoder().encodeToString(journal.toByteArray(Charsets.UTF_8))
    )
    assertTrue(wrapped.contains("\n"), "the fake should wrap its base64 like GitHub does")

    assertEquals(journal, storeOn(github).load())
  }

  @Test
  fun `load asks for the named branch and leaves it out when there is none`() {
    val github = FakeGitHub()
    storeOn(github, branch = "main").load()
    assertTrue(github.requests[0].url.endsWith("?ref=main"), github.requests[0].url)

    val plain = FakeGitHub()
    storeOn(plain).load()
    assertFalse(plain.requests[0].url.contains("?"), plain.requests[0].url)
  }

  @Test
  fun `a directory where the journal should be is a failure, not an empty journal`() {
    val github = FakeGitHub()
    github.writtenBySomeoneElse("$path/something", "x")

    val store = storeOn(github)
    assertNull(store.load())
    // Null again, because the interface has no other channel — but the failure is recorded and no
    // baseline was taken, so the save that follows cannot write under the directory's name.
    assertNotNull(store.lastLoadFailure)
    assertTrue(store.lastLoadFailure!!.contains("directory"), store.lastLoadFailure!!)
  }

  // --- writing ---------------------------------------------------------------------------------

  @Test
  fun `save creates the file when there is none`() {
    val github = FakeGitHub()
    val store = storeOn(github)
    store.load()

    val outcome = store.save("first journal\n", "Hours 2026-09-10: 1 record added")

    assertTrue(outcome is SaveOutcome.Saved, outcome.toString())
    assertEquals("first journal\n", github.contentOf(path))
    assertEquals(listOf("Hours 2026-09-10: 1 record added"), github.messages)
    // A create carries no sha: there is no previous version to name.
    assertFalse(github.requests.last().body!!.contains("\"sha\""), github.requests.last().body!!)
  }

  @Test
  fun `save changes the file when there is one, naming the version it replaces`() {
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, "first journal\n")
    val shaWhenRead = github.blobShaOf(path)
    val store = storeOn(github)
    store.load()

    val outcome = store.save("second journal\n", "Hours 2026-09-10: 1 record changed")

    assertTrue(outcome is SaveOutcome.Saved, outcome.toString())
    assertEquals("second journal\n", github.contentOf(path))
    // An update carries the sha, and it is the one from the read — see the conflict test for why
    // that matters more than it looks.
    assertTrue(github.requests.last().body!!.contains(shaWhenRead!!))
  }

  @Test
  fun `revision carries the commit, and a second save carries a different one`() {
    val github = FakeGitHub()
    val store = storeOn(github)
    store.load()

    val first = store.save("one\n", "first") as SaveOutcome.Saved
    val second = store.save("two\n", "second") as SaveOutcome.Saved

    // 40 hex characters: a commit id, which is what a restore anchors on.
    assertNotNull(first.revision)
    assertEquals(40, first.revision!!.length)
    assertTrue(first.revision!!.all { it in "0123456789abcdef" }, first.revision!!)
    // Two saves, two commits. A blob sha would differ here too because the content did, which is
    // exactly why this test alone does not prove the choice — the next one does.
    assertFalse(first.revision == second.revision)
  }

  @Test
  fun `revision is the commit and not the blob sha of the content`() {
    val github = FakeGitHub()
    val store = storeOn(github)
    store.load()

    val saved = store.save("one\n", "first") as SaveOutcome.Saved

    // The distinction that decides what a restore can do. The blob sha names content only: two
    // identical journals saved weeks apart share one, and it cannot say when, by whom, or what
    // else moved with it. The commit names a point in the history, which is what `?ref=` and
    // `git show` take.
    assertFalse(
      saved.revision == github.blobShaOf(path),
      "revision must not be the blob sha — it would not identify a version in the history",
    )
  }

  @Test
  fun `the commit message arrives as given, tabs newlines and quotes included`() {
    val github = FakeGitHub()
    val store = storeOn(github)
    store.load()
    // The change list is built from real notes, and a note holds whatever somebody typed. An
    // unescaped newline makes the request body unparseable at the far end, and the journal then
    // silently stops saving.
    val message = "Hours 2026-09-10\n\n+ 2 records\t\"Meßgerät\" \\ 🛠"

    store.save("x\n", message)

    assertEquals(listOf(message), github.messages)
  }

  // --- unchanged -------------------------------------------------------------------------------

  @Test
  fun `the same text twice is Unchanged and costs no request at all`() {
    val github = FakeGitHub()
    val store = storeOn(github)
    store.load()
    val journal = "r1|t1|2026-09-10|3600|manual|Natalie||\n"
    store.save(journal, "first")

    val afterFirstSave = github.requestCount
    val outcome = store.save(journal, "again")

    assertTrue(outcome is SaveOutcome.Unchanged, outcome.toString())
    // Counted, not asserted. One GET for the load, one PUT for the save, and then nothing: the
    // second save neither commits nor looks.
    assertEquals(2, afterFirstSave)
    assertEquals(2, github.requestCount, "the second save must not talk to GitHub at all")
    assertEquals(1, github.countOf("PUT"))
    assertEquals(listOf("first"), github.messages)
  }

  @Test
  fun `text identical to what was loaded is Unchanged without a commit`() {
    val journal = "r1|t1|2026-09-10|3600|manual|Natalie||\n"
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, journal)
    val store = storeOn(github)
    store.load()

    val outcome = store.save(journal, "nothing new")

    assertTrue(outcome is SaveOutcome.Unchanged, outcome.toString())
    assertEquals(1, github.requestCount)
    assertEquals(0, github.countOf("PUT"))
  }

  // --- the conflict, which is the point of the design -------------------------------------------

  @Test
  fun `a foreign write between load and save is refused, not overwritten`() {
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, "as it was\n")
    val store = storeOn(github)
    store.load()

    // Somebody else commits. The blob sha the store is holding is now stale.
    github.writtenBySomeoneElse(path, "somebody else's work\n")

    val outcome = store.save("what we would have written\n", "ours")

    assertTrue(outcome is SaveOutcome.Failed, outcome.toString())
    // The whole point, and the same answer as #2818 on the WebDAV side: refuse, do not overwrite.
    // Their content is still there.
    assertEquals("somebody else's work\n", github.contentOf(path))
    assertTrue(github.messages.isEmpty(), "nothing may have been committed")
  }

  @Test
  fun `a refused save stays refused until the journal is read again`() {
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, "as it was\n")
    val store = storeOn(github)
    store.load()
    github.writtenBySomeoneElse(path, "somebody else's work\n")
    store.save("ours\n", "first try")

    // A caller that simply tries again must not succeed by trying. This is the trap in the obvious
    // repair: dropping the stale sha after a conflict, so the retry fetches a fresh one, turns a
    // refusal into the overwrite it was there to prevent.
    val second = store.save("ours\n", "second try")

    assertTrue(second is SaveOutcome.Failed, second.toString())
    assertEquals("somebody else's work\n", github.contentOf(path))
    assertTrue(github.messages.isEmpty())

    // Reading again is the deliberate act that makes progress possible: now the caller has seen
    // the other version and can merge it.
    assertEquals("somebody else's work\n", store.load())
    val third = store.save("somebody else's work\nours\n", "merged")
    assertTrue(third is SaveOutcome.Saved, third.toString())
    assertEquals("somebody else's work\nours\n", github.contentOf(path))
  }

  @Test
  fun `saving without ever reading refuses to replace a file it has not seen`() {
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, "a journal from another device\n")
    val store = storeOn(github)

    // No load() first. GitHub would accept a read-then-write done here, and the other device's
    // records would be gone with nothing to merge from.
    val outcome = store.save("ours\n", "ours")

    assertTrue(outcome is SaveOutcome.Failed, outcome.toString())
    assertTrue((outcome as SaveOutcome.Failed).reason.contains("not read"), outcome.reason)
    assertEquals("a journal from another device\n", github.contentOf(path))
    assertEquals(0, github.countOf("PUT"))

    // And it does not become allowed by repeating it.
    assertTrue(store.save("ours\n", "ours again") is SaveOutcome.Failed)
    assertEquals("a journal from another device\n", github.contentOf(path))
  }

  @Test
  fun `saving without ever reading may still create a file that is not there`() {
    val github = FakeGitHub()

    val outcome = storeOn(github).save("ours\n", "first")

    // Nothing is at risk when there is nothing there.
    assertTrue(outcome is SaveOutcome.Saved, outcome.toString())
    assertEquals("ours\n", github.contentOf(path))
  }

  // --- failures reach the caller as sentences ----------------------------------------------------

  @Test
  fun `a rejected token is a readable reason and not an exception`() {
    val github = FakeGitHub()
    val store = storeOn(github, withToken = "wrong-token")
    store.load()

    val outcome = store.save("x\n", "m")

    val reason = (outcome as SaveOutcome.Failed).reason
    assertTrue(reason.contains("token"), reason)
    assertTrue(reason.contains("again"), reason)
    // Not a bare number, and above all not the token.
    assertFalse(reason.contains("401"), reason)
    assertFalse(reason.contains("wrong-token"), reason)
  }

  @Test
  fun `a server error says what happened and does not throw`() {
    val github = FakeGitHub()
    val store = storeOn(github)
    store.load()
    github.failWith = 503 to "Service unavailable"

    val outcome = store.save("x\n", "m")

    val reason = (outcome as SaveOutcome.Failed).reason
    assertTrue(reason.contains("not answering"), reason)
    assertTrue(reason.contains("try again"), reason)
  }

  @Test
  fun `a transport failure becomes a reason instead of escaping`() {
    val github = FakeGitHub()
    val store = storeOn(github)
    store.load()
    // A laptop on a train loses its connection as a matter of course. An exception escaping here
    // is a crash in the middle of saving work.
    github.throwWith = IOException("Unable to resolve host api.github.com")

    val outcome = store.save("x\n", "m")

    val reason = (outcome as SaveOutcome.Failed).reason
    assertTrue(reason.contains("could not be reached"), reason)
    assertTrue(reason.contains("api.github.com"), reason)
  }

  @Test
  fun `a failed load takes no baseline, so the save after it cannot overwrite`() {
    val github = FakeGitHub()
    github.writtenBySomeoneElse(path, "a journal that is really there\n")
    val store = storeOn(github)
    github.failWith = 500 to "Internal Server Error"

    assertNull(store.load())
    assertNotNull(store.lastLoadFailure)

    // The trap this guards: load() returned null because it could not look, and a store that
    // recorded "there is nothing there" would let the next save write over a file it never saw.
    github.failWith = null
    val outcome = store.save("ours\n", "ours")

    assertTrue(outcome is SaveOutcome.Failed, outcome.toString())
    assertEquals("a journal that is really there\n", github.contentOf(path))
  }

  @Test
  fun `the name says which repository without saying the token or the address`() {
    val store = storeOn(FakeGitHub())
    assertEquals("GitHub natalie/stunden/hours/haus.timelog", store.name)
    assertFalse(store.name.contains(token))
    assertFalse(store.name.contains("http"))
  }

  // --- the one test that opens a socket ----------------------------------------------------------

  @Test
  fun `the real transport carries method, headers and body over a socket`() {
    // Everything above drives the store through the seam, which leaves JdkHttpExchange itself
    // untested — and that class is where a wrong method, a dropped header or a body that never
    // gets sent would live. So the same fake service is put behind a real server here and the real
    // transport is pointed at it. Loopback and plain HTTP: nothing secret goes over this, the
    // token is the fake's own.
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val github = FakeGitHub(apiBase = "")
    server.createContext("/") { exchange ->
      val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8).ifEmpty { null }
      val (status, payload) = github.handle(
        exchange.requestMethod,
        exchange.requestURI.toString(),
        exchange.requestHeaders.getFirst("Authorization"),
        body,
      )
      val bytes = payload.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val store = GitHubJournalStore(
        JdkHttpExchange(),
        GitHubTarget("natalie", "stunden", path),
        token,
        apiBase = base,
      )

      // The first run through, over a real socket: nothing there, create it, read it back.
      assertNull(store.load())
      val journal = "r1|t1|2026-09-10|3600|manual|Natalié Müller|Meßgerät 🛠|\n"
      val saved = store.save(journal, "over the wire")
      assertTrue(saved is SaveOutcome.Saved, saved.toString())
      assertEquals(journal, github.contentOf(path))

      val fresh = GitHubJournalStore(
        JdkHttpExchange(),
        GitHubTarget("natalie", "stunden", path),
        token,
        apiBase = base,
      )
      // Round trip through real base64 over real bytes, not a fake handing back what it was given.
      assertEquals(journal, fresh.load())
      assertEquals(listOf("over the wire"), github.messages)
    } finally {
      server.stop(0)
    }
  }
}
