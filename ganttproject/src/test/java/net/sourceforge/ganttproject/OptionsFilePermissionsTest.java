/*
Copyright 2026 BarD Software s.r.o

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
package net.sourceforge.ganttproject;

import net.sourceforge.ganttproject.document.DocumentManager;
import net.sourceforge.ganttproject.roles.RoleManager;
import net.sourceforge.ganttproject.roles.RoleSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The permissions of the options file which {@link GanttOptions#save()} writes.
 *
 * The file holds the GP Cloud auth token, the GP Cloud user id and the WebDAV server list, and it is
 * written through a fresh, owner-only temp file which is then renamed into place -- never through a
 * plain {@code FileOutputStream} straight onto the real file, which would leave it at whatever the
 * umask allows (0644 with the usual umask of 0022, readable by every other user of the machine) for
 * however long the write takes.
 *
 * None of this exists on a filesystem without POSIX permissions -- Windows above all, where
 * {@code Files.getPosixFilePermissions}, {@code Files.setPosixFilePermissions} and the
 * restricted-attribute temp file all throw {@link UnsupportedOperationException}. Every test that
 * reads or writes a POSIX mode itself -- not through {@link GanttOptions#save()}, which already
 * catches this on the real, non-test code path -- calls {@link #assumePosixFilePermissionsSupported}
 * before doing so, so it skips there instead of failing. This is NOT a class-wide
 * {@code @BeforeEach}: {@link #optionsAreSavedWholeWhereThePlatformCannotSetPermissions},
 * {@link #optionsAreSavedWholeWhenThePermissionCallFails} and
 * {@link #aFailedSaveDoesNotDestroyThePreviousOptionsFile} touch no POSIX API directly and are meant
 * to run, and pass, on Windows -- a blanket skip there would throw away the only coverage this class
 * has of {@code save()}'s actual behaviour on a non-POSIX filesystem.
 */
public class OptionsFilePermissionsTest {
  private String myOriginalUserHome;

  @BeforeEach
  public void pointUserHomeAtScratchDirectory(@TempDir Path home) {
    myOriginalUserHome = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
  }

  @AfterEach
  public void restoreUserHome() {
    System.setProperty("user.home", myOriginalUserHome);
  }

  /** The real save() on this machine: the file comes out readable by its owner and nobody else. */
  @Test
  public void savedOptionsFileIsReadableByItsOwnerOnly() throws IOException {
    GanttOptions options = newOptions();

    options.save();

    File file = GanttOptions.getOptionsFile();
    assertTrue(file.exists(), "save() wrote no options file at all to " + file);
    assertEquals("rw-------", modeOf(file),
        "the options file holds the cloud auth token and is open to other users of the machine");
    assertCompleteOptionsFile(file);
  }

  /**
   * The upgrade case: every existing installation already has a {@code ~/.ganttproject} created by
   * the old, unrestricted code, wide open before this fix ever runs on it. A test which only ever
   * measures a file that {@code save()} itself created from nothing would never notice if the fix
   * only worked for brand-new files and left an inherited, already-world-readable one untouched --
   * so this one plants that wide-open file by hand first.
   */
  @Test
  public void anInheritedWorldReadableOptionsFileIsNarrowedBySave() throws IOException {
    assumePosixFilePermissionsSupported();

    File file = GanttOptions.getOptionsFile();
    try (OutputStream out = new FileOutputStream(file)) {
      out.write("<ganttproject-options/>".getBytes(StandardCharsets.UTF_8));
    }
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
    assertEquals("rw-r--r--", modeOf(file), "could not plant the wide-open file this test relies on");

    GanttOptions options = newOptions();
    options.save();

    assertEquals("rw-------", modeOf(file),
        "save() left an inherited, already-world-readable options file exactly as wide open as it "
            + "found it");
    assertCompleteOptionsFile(file);
  }

  /**
   * The control for the test above: a file created the way save() used to create it before this fix,
   * in the same directory and in the same run, is NOT owner-only. Without this the assertion above
   * would also pass on a machine whose umask happens to be 0077, and it would prove nothing.
   *
   * On such a machine this control itself cannot tell the fix from its absence -- every file in the
   * directory comes out owner-only regardless of what save() does. That is not a failure of the code
   * under test, so it is skipped, not failed: a hard assertion here would be red on every hardened
   * machine (umask 0077) even though nothing is wrong.
   */
  @Test
  public void aPlainFileOutputStreamInTheSameDirectoryIsNotOwnerOnly() throws IOException {
    File control = new File(System.getProperty("user.home"), "umask-control");
    try (OutputStream out = new FileOutputStream(control)) {
      out.write(42);
    }

    assumeFalse("rw-------".equals(modeOf(control)),
        "the umask of this run is already 0077, so this control cannot tell the fix from its "
            + "absence -- skipping rather than failing. Run the tests with umask 0022 to exercise it.");
  }

  /**
   * Windows, and every other filesystem without POSIX permissions: the platform call throws
   * {@link UnsupportedOperationException}, and the options still have to reach the disk whole. The
   * throw is put in by hand because a POSIX machine cannot produce it on its own.
   *
   * What this cannot measure is the resulting file mode on a real non-POSIX filesystem -- that is not
   * observable from a POSIX machine, see the class comment, and this test is run on one. Measured
   * here instead: {@code Files.createTempFile} without a {@code FileAttribute} -- the fallback this
   * exercises -- turns out to default to owner-only permissions on THIS (POSIX) filesystem on its
   * own, regardless of the forced exception. That is a property of this machine's JDK and filesystem,
   * not of the fix, and it would not hold on Windows, which has no POSIX permissions to default. So
   * this test only checks what it safely can: that the options are not lost.
   */
  @Test
  public void optionsAreSavedWholeWhereThePlatformCannotSetPermissions() throws IOException {
    GanttOptions options = newOptionsWhoseTempFileCallThrows(
        new UnsupportedOperationException("posix:permissions not supported"));

    options.save();

    File file = GanttOptions.getOptionsFile();
    assertTrue(file.exists(), "the options were lost because the file mode could not be set");
    assertCompleteOptionsFile(file);
  }

  /** The same for an I/O failure: a FUSE mount, a share which claims POSIX support and refuses it. */
  @Test
  public void optionsAreSavedWholeWhenThePermissionCallFails() throws IOException {
    GanttOptions options = newOptionsWhoseTempFileCallThrows(new IOException("read-only file system"));

    options.save();

    File file = GanttOptions.getOptionsFile();
    assertTrue(file.exists(), "the options were lost because the file mode could not be set");
    assertCompleteOptionsFile(file);
  }

  /**
   * The point of writing into a temp file and renaming it into place, spelled out as a test: a
   * failure partway through a save must not destroy the options from the previous, successful one.
   *
   * Before this fix, {@code save()} opened the real file with a plain {@code FileOutputStream} --
   * which truncates it to empty immediately -- before anything could go wrong, so any exception
   * afterwards (a {@code RuntimeException} from the permission call among them) left an empty options
   * file on disk. Writing into a separate temp file first means the real file is only ever touched by
   * the final, successful rename.
   */
  @Test
  public void aFailedSaveDoesNotDestroyThePreviousOptionsFile() throws IOException {
    GanttOptions firstSave = newOptions();
    firstSave.save();
    File file = GanttOptions.getOptionsFile();
    assertCompleteOptionsFile(file);
    String previousContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

    GanttOptions secondSave = newOptionsWhoseTempFileCallThrows(new RuntimeException("disk went away"));
    secondSave.save();

    assertTrue(file.exists(), "the previous options file was removed by the failed save");
    String contentAfterFailedSave = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    assertEquals(previousContent, contentAfterFailedSave,
        "a save() that fails partway through must leave the previous options file untouched, not "
            + "an empty or partial one");
  }

  private void assertCompleteOptionsFile(File file) throws IOException {
    String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    assertTrue(xml.contains("<ganttproject-options"), "no options document in the file: " + xml);
    assertTrue(xml.trim().endsWith("</ganttproject-options>"),
        "the options document is cut off, so the copy did not finish: " + xml);
  }

  /**
   * The mode as `ls -l` writes it, so that a failure says `rw-r--r--` and not a set of enums.
   *
   * Calls {@link #assumePosixFilePermissionsSupported} itself, since every caller reads a POSIX
   * mode through here -- but a caller that WRITES one first (as
   * {@link #anInheritedWorldReadableOptionsFileIsNarrowedBySave} does) cannot rely on this guard to
   * cover that earlier write; it must call {@link #assumePosixFilePermissionsSupported} itself
   * before that write. The guard belongs at each place a POSIX mode is actually touched, not at one
   * shared spot a test may or may not reach first.
   */
  private String modeOf(File file) throws IOException {
    assumePosixFilePermissionsSupported();
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()));
  }

  /**
   * Skips the calling test rather than failing it on a filesystem without POSIX permissions --
   * Windows above all, where {@code Files.getPosixFilePermissions} and
   * {@code Files.setPosixFilePermissions} both throw {@link UnsupportedOperationException}. Call
   * this immediately before any direct use of either, in every test that makes one -- not only the
   * first such use in a test, and not through a helper a test may bypass.
   */
  private void assumePosixFilePermissionsSupported() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "no POSIX file permissions on this filesystem");
  }

  /**
   * The moment of writing, not only the result: at no instant while {@link GanttOptions#save()} runs
   * may anything in the options directory be readable by group or others. A file that is created at
   * the umask mode and narrowed a moment later is correct at the end and wide open in between -- and
   * whoever opened it in between keeps a descriptor that still reads what is written afterwards.
   *
   * Sampling, so it can only ever PROVE a window and never prove its absence: a watcher thread stats
   * every entry of the directory in a tight loop while the main thread saves many times, each time starting from no file. Green means "not seen in N saves",
   * red means "seen", with the mode and the file name.
   */
  @Test
  public void noEntryIsEverReadableByOthersWhileSaving() throws Exception {
    assumePosixFilePermissionsSupported();
    File file = GanttOptions.getOptionsFile();
    Path dir = file.getParentFile().toPath();
    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicBoolean inSave = new AtomicBoolean(false);
    AtomicReference<String> seen = new AtomicReference<>();
    Thread watcher = new Thread(() -> {
      while (!stop.get()) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
          for (Path entry : entries) {
            try {
              String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(entry));
              if (inSave.get() && entry.getFileName().toString().startsWith(".ganttproject") && !mode.endsWith("------")) {
                seen.compareAndSet(null, entry.getFileName() + " was " + mode);
              }
            } catch (IOException gone) {
              // renamed or deleted between listing and stat
            }
          }
        } catch (IOException ignored) {
          // directory momentarily unreadable
        }
      }
    });
    watcher.setDaemon(true);
    watcher.start();
    try {
      GanttOptions options = newOptions();
      for (int i = 0; i < 3000 && seen.get() == null; i++) {
        Files.deleteIfExists(file.toPath());
        // Fresh file only: the entry does not exist before save(), so anything wide that is seen
        // was made by save() itself. (An inherited file is legitimately wide until save() has
        // replaced it; its end state is covered by anInheritedWorldReadableOptionsFileIsNarrowedBySave.)
        inSave.set(true);
        try {
          options.save();
        } finally {
          inSave.set(false);
        }
      }
    } finally {
      stop.set(true);
      watcher.join(5000);
    }
    assertNull(seen.get(), "while save() was running, an options entry was readable by other users: " + seen.get());
    assertCompleteOptionsFile(file);
  }

  private GanttOptions newOptions() {
    return newOptions(null);
  }

  private GanttOptions newOptionsWhoseTempFileCallThrows(Exception thrown) {
    return newOptions(thrown);
  }

  /**
   * A GanttOptions with the two collaborators which doSave reads. With {@code thrown} given, the
   * call which creates the owner-only temp file throws it instead of running -- that is the only way
   * to reach the non-POSIX and failure branches from a POSIX machine that does not fail.
   */
  private GanttOptions newOptions(Exception thrown) {
    RoleManager roleManager = createNiceMock(RoleManager.class);
    expect(roleManager.getRoleSets()).andStubReturn(new RoleSet[0]);
    DocumentManager documentManager = createNiceMock(DocumentManager.class);
    expect(documentManager.getRecentDocuments()).andStubReturn(Collections.emptyList());
    replay(roleManager, documentManager);

    GanttOptions options = thrown == null
        ? new GanttOptions(roleManager, documentManager, false)
        : new GanttOptions(roleManager, documentManager, false) {
          @Override
          File createRestrictedTempFileImpl(File directory) throws IOException {
            if (thrown instanceof IOException) {
              throw (IOException) thrown;
            }
            throw (RuntimeException) thrown;
          }
        };
    // Creates the UI configuration which doSave reads; the real application sets it from outside.
    options.getUIConfiguration();
    return options;
  }
}
