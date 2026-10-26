/*
Copyright 2026 BarD Software s.r.o

This file is part of GanttProject, an open-source project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package biz.ganttproject.storage.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [fork change] localFileName(): a WebDAV URL in the file name field of the local storage.
 */
class LocalFileNameTest {
  @Test
  fun `a webdav url gives its last component`() {
    assertEquals("plan.gan", localFileName("https://dav.example.com/projects/plan.gan"))
  }

  @Test
  fun `a plain name stays as it is`() {
    assertEquals("plan.gan", localFileName("plan.gan"))
  }

  @Test
  fun `a missing or blank name gives the default`() {
    assertEquals(DEFAULT_LOCAL_FILE_NAME, localFileName(null))
    assertEquals(DEFAULT_LOCAL_FILE_NAME, localFileName(" "))
  }

  @Test
  fun `an address ending in a slash gives the default`() {
    assertEquals(DEFAULT_LOCAL_FILE_NAME, localFileName("https://dav.example.com/projects/"))
  }

  @Test
  fun `the breadcrumb fallback uses the last component of an address as the file name`() {
    val folder = File(System.getProperty("java.io.tmpdir"), "gp-test")
    assertEquals(
      folder.toPath().resolve("plan neu.gan"),
      localBreadcrumbPath("https://dav.example.com/p/plan neu.gan", "https://dav.example.com/p/plan neu.gan", folder)
    )
  }
}
