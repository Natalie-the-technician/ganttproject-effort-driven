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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * THE PERMISSIONS OF THE SETTINGS FILE.
 *
 * `~/.ganttproject` holds the WebDAV server list and, where no keyring can be reached, the Toggl
 * API token in the clear. It used to be written with whatever the umask left behind — measured in
 * the `computer-use` container on 09.09.2026: `-rw-r--r--`, that is `0644`, readable by every user
 * of the machine. [restrictToOwner] takes it to `0600`.
 *
 * THE MODE IS READ BACK, NOT ASSUMED. Every assertion below calls
 * `Files.getPosixFilePermissions` on the file afterwards. That is the whole point of these tests:
 * the predecessor report of 09.09.2026 had to write "0644 is inferred from the umask, not read off
 * a file", and inferring is what this replaces.
 *
 * WHAT THE ORDER OF THE TESTS IS ABOUT: the interesting case is NOT a new file. It is a file that
 * ALREADY EXISTS with 0644, because `new FileOutputStream(file)` truncates it and leaves its
 * permissions exactly as they were. Anybody who has run GanttProject before has such a file, and if
 * the change did not reach it, the change would do nothing for almost everybody.
 */
class OwnerOnlyFileTest {

  /**
   * Everything here is about POSIX modes, so on a filesystem without them there is nothing to
   * measure. That case has its own test — [a filesystem without posix permissions is not an error]
   * — which runs everywhere.
   */
  private fun assumePosix(file: File) =
    assumeTrue(Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) != null,
      "this filesystem has no POSIX permissions; see the Windows test below")

  private fun tempFile(mode: String, content: String = "x"): File {
    val file = Files.createTempFile("ganttproject-options-test", ".xml").toFile()
    file.deleteOnExit()
    file.writeText(content)
    Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
      ?.setPermissions(PosixFilePermissions.fromString(mode))
    return file
  }

  private fun modeOf(file: File): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

  /**
   * A file that stands open to the whole machine is closed down to its owner.
   *
   * The precondition is asserted first. Without it the test would also pass on a machine whose
   * temp directory hands out 0600 anyway, and then it would measure nothing.
   */
  @Test
  fun `a world readable settings file becomes owner only`() {
    val file = tempFile("rw-r--r--")
    assumePosix(file)
    assertEquals("rw-r--r--", modeOf(file), "precondition: the file has to start out world readable")

    assertTrue(restrictToOwner(file), "restrictToOwner reported that it did nothing")

    assertEquals("rw-------", modeOf(file))
  }

  /**
   * THE CASE THAT DECIDES WHETHER THIS CHANGE IS WORTH ANYTHING: a file that was there before.
   *
   * `GanttOptions.save` opens the file with `new FileOutputStream(file)`. That truncates the
   * contents and does NOT touch the mode — an existing 0644 file stays 0644 for ever unless
   * somebody narrows it explicitly. This is that explicit step, run in the order the save path
   * runs it: open (truncate), narrow, write.
   */
  @Test
  fun `an existing world readable file is dragged along at the next write`() {
    val file = tempFile("rw-r--r--", "<ganttproject-options>alt</ganttproject-options>")
    assumePosix(file)
    assertEquals("rw-r--r--", modeOf(file), "precondition")

    // Exactly what GanttOptions.save does: truncate, narrow, then write the new contents.
    file.outputStream().use { out ->
      restrictToOwner(file)
      out.write("<ganttproject-options>neu</ganttproject-options>".toByteArray())
    }

    assertEquals("rw-------", modeOf(file), "an already existing file has to be dragged along")
    assertEquals("<ganttproject-options>neu</ganttproject-options>", file.readText(),
      "and its contents are what was written, unabridged")
  }

  /**
   * The nothing-changes guard for the CONTENT. Narrowing the mode must not touch a byte of the
   * file — not its length, not its text, not the trailing newline.
   */
  @Test
  fun `narrowing the permissions does not change the contents`() {
    val text = "<ganttproject-options>\n    <option id=\"ui.language\" value=\"de\"/>\n</ganttproject-options>\n"
    val file = tempFile("rw-r--r--", text)
    assumePosix(file)
    val lengthBefore = file.length()

    restrictToOwner(file)

    assertEquals(text, file.readText(), "the settings themselves must not change")
    assertEquals(lengthBefore, file.length())
  }

  /** The owner still has to be able to read and write it — otherwise the next save fails. */
  @Test
  fun `the owner keeps both read and write`() {
    val file = tempFile("rw-r--r--")
    assumePosix(file)
    restrictToOwner(file)

    val permissions = Files.getPosixFilePermissions(file.toPath())
    assertTrue(permissions.contains(PosixFilePermission.OWNER_READ))
    assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE))
    file.writeText("noch etwas")
    assertEquals("noch etwas", file.readText())
  }

  /**
   * WINDOWS, WHICH CANNOT BE RUN HERE — measured through the seam instead.
   *
   * On a filesystem without POSIX permissions `Files.getFileAttributeView(path,
   * PosixFileAttributeView.class)` returns NULL rather than throwing; that is why the production
   * code asks it that way instead of calling `Files.setPosixFilePermissions`, which would throw
   * `UnsupportedOperationException`. The lookup handed in here returns null exactly as the real one
   * does on Windows.
   *
   * What is asserted: no exception, a truthful `false`, and the file untouched. What is NOT
   * asserted, and cannot be from this machine: that a real Windows JVM returns null there. That
   * claim rests on the documented contract of `getFileAttributeView`.
   */
  @Test
  fun `a filesystem without posix permissions is not an error`() {
    val file = tempFile("rw-r--r--", "unberuehrt")

    val result = restrictToOwner(file) { null }

    assertFalse(result, "without POSIX permissions it has to say so rather than claim success")
    assertEquals("unberuehrt", file.readText())
  }

  /**
   * And a lookup that fails outright — a read-only mount, a network share, a security manager.
   * Saving the settings has to survive it.
   */
  @Test
  fun `a failing lookup does not stop the settings being saved`() {
    val file = tempFile("rw-r--r--", "unberuehrt")

    val result = restrictToOwner(file) { throw java.io.IOException("kein Zugriff auf die Rechte") }

    assertFalse(result)
    assertEquals("unberuehrt", file.readText())
  }

  /**
   * A view whose `setPermissions` throws is the same story one step further in, and it is the more
   * likely of the two: the file is there, the view exists, and the filesystem refuses the change.
   */
  @Test
  fun `a view that refuses the change does not stop the settings being saved`() {
    val file = tempFile("rw-r--r--", "unberuehrt")
    val refusing = object : PosixFileAttributeView {
      override fun name(): String = "posix"
      override fun readAttributes() = throw UnsupportedOperationException()
      override fun setTimes(a: java.nio.file.attribute.FileTime?, b: java.nio.file.attribute.FileTime?,
                            c: java.nio.file.attribute.FileTime?) = throw UnsupportedOperationException()
      override fun getOwner() = throw UnsupportedOperationException()
      override fun setOwner(owner: java.nio.file.attribute.UserPrincipal?) = throw UnsupportedOperationException()
      override fun setGroup(group: java.nio.file.attribute.GroupPrincipal?) = throw UnsupportedOperationException()
      override fun setPermissions(perms: MutableSet<PosixFilePermission>?): Unit =
        throw java.io.IOException("Read-only file system")
    }

    val result = restrictToOwner(file) { refusing }

    assertFalse(result)
    assertEquals("unberuehrt", file.readText())
  }
}
