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
@file:JvmName("OwnerOnlyFile")

package net.sourceforge.ganttproject.fork

import net.sourceforge.ganttproject.GPLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * [fork change] Takes a file away from everybody but its owner.
 *
 * WHAT FOR: `~/.ganttproject` is written by `GanttOptions.save` with a plain `FileOutputStream`,
 * so it gets whatever the umask leaves. The usual umask is 0022 and the usual result is therefore
 * `0644` -- readable by EVERY user of the machine, not only by programs of the same user. That was
 * measured, not assumed: a settings file written by this fork in the `computer-use` container on
 * 09.09.2026 stood there as `-rw-r--r--`.
 *
 * The file holds the WebDAV server list, and where no keyring can be reached it holds the Toggl
 * API token in the clear (see [SecretStore] and `TokenStore.kt`). `0600` does not turn that into a
 * vault -- anything running as this user still reads it -- but it removes the part that is plainly
 * wrong: the other people on the machine.
 *
 * WHY [PosixFileAttributeView] AND NOT `Files.setPosixFilePermissions`: the same job, but a
 * different way of saying "this filesystem has no such thing". `setPosixFilePermissions` THROWS
 * `UnsupportedOperationException` on a filesystem without POSIX permissions -- Windows, above all.
 * `getFileAttributeView` returns NULL there instead, so the ordinary case on the ordinary platform
 * is a null check and not an exception. That matters because this is called on the path that saves
 * the settings, and losing the settings because a file mode could not be set would be a far worse
 * bug than the one being fixed.
 *
 * WHY NOTHING IS ATTEMPTED ON WINDOWS, and this is a decision:
 *
 *  * `java.io.File.setReadable(false, false)` is documented to be a no-op returning false where
 *    the platform cannot express it, and on Windows it cannot. It buys nothing.
 *  * `File.setWritable(false, false)` DOES do something on Windows -- it sets the read-only DOS
 *    attribute. Toggling that on the settings file, to gain nothing, risks leaving the file
 *    unwritable at the next save. That is trading a real regression for no protection.
 *  * The proper Windows answer is `AclFileAttributeView`, and it is not built here for two
 *    reasons: it cannot be measured from this machine, and it is not needed. On Windows
 *    [SecretStore] has DPAPI, so the token in `~/.ganttproject` is a ciphertext, not a secret.
 *    The plain-text fallback this guards happens exactly where POSIX permissions exist.
 *
 * So on Windows this returns false and changes nothing, which is what "runs through without an
 * exception" has to mean.
 *
 * @return whether the permissions were really narrowed. False is a normal answer, not an error:
 * no POSIX on this filesystem, a read-only mount, a network share that ignores modes. The caller
 * carries on saving either way.
 */
fun restrictToOwner(file: File): Boolean = restrictToOwner(file) { path ->
  Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
}

/**
 * The same thing with the platform lookup handed in, so that the branch this machine cannot run --
 * a filesystem WITHOUT POSIX permissions -- can still be measured. A test passes a lookup that
 * returns null, which is precisely what the real one does on Windows.
 *
 * Not private, and deliberately so: a copy of this logic written inside a test would prove that the
 * copy works. Not `internal` either -- the tests of this fork live in two source sets, and only one
 * of them is compiled together with this module.
 */
fun restrictToOwner(file: File, posixView: (Path) -> PosixFileAttributeView?): Boolean {
  return try {
    val view = posixView(file.toPath()) ?: return false
    view.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    true
  } catch (e: Throwable) {
    // Never fatal. Whoever called this is in the middle of writing the settings, and a file mode
    // that could not be set is not a reason to lose them.
    try {
      GPLogger.log("[fork] Could not restrict the permissions of ${file.path} to its owner: $e")
    } catch (ignored: Throwable) {
      // Nowhere left to say it.
    }
    false
  }
}
