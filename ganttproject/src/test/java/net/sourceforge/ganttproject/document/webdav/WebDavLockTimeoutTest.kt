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
package net.sourceforge.ganttproject.document.webdav

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.document.DocumentCreator
import net.sourceforge.ganttproject.document.ProxyDocument
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.parser.ParserFactory
import org.easymock.EasyMock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

private const val DOCUMENT_URL = "http://webdav.example.invalid/projects/test.gan"

private fun createDocumentManager(): DocumentCreator {
  val project = EasyMock.createNiceMock<IGanttProject>(IGanttProject::class.java)
  val uiFacade = EasyMock.createNiceMock<UIFacade>(UIFacade::class.java)
  val parserFactory = EasyMock.createNiceMock<ParserFactory>(ParserFactory::class.java)
  EasyMock.replay(project, uiFacade, parserFactory)
  return DocumentCreator(project, uiFacade, parserFactory)
}

/**
 * [fork] The fork writes its note through GPLogger.log(String), i.e. to the slf4j logger "App" at INFO,
 * not to a java.util.logging logger of HttpDocument at WARNING. So record the logback logger "App".
 */
private fun <T> withRecordedLog(code: () -> T): Pair<T, List<ILoggingEvent>> {
  val logger = LoggerFactory.getLogger("App") as Logger
  val appender = ListAppender<ILoggingEvent>()
  val oldLevel = logger.level
  appender.start()
  logger.addAppender(appender)
  logger.level = Level.ALL
  try {
    return code() to appender.list.toList()
  } finally {
    logger.detachAppender(appender)
    logger.level = oldLevel
  }
}

/**
 * [fork] The fork's HttpDocument has no getLockTimeout(); the value is read from its private field.
 */
private val HttpDocument.lockTimeout: Int
  get() = HttpDocument::class.java.getDeclaredField("myTimeout").also { it.isAccessible = true }.getInt(this)

class WebDavLockTimeoutTest {
  /**
   * The webdav.lockTimeout option is editable and persisted, so it must arrive at the document
   * which DocumentManager creates from a WebDAV URL.
   */
  @Test
  fun `configured lock timeout reaches the document`() {
    val documentManager = createDocumentManager()
    val timeoutOption = (documentManager.webDavStorageUi as WebDavStorageImpl).webDavLockTimeoutOption
    timeoutOption.value = 120

    val document = (documentManager.getDocument(DOCUMENT_URL) as ProxyDocument).realDocument
    assertInstanceOf(HttpDocument::class.java, document)
    assertEquals(120, (document as HttpDocument).lockTimeout)
  }

  /**
   * The default value of the option is -1, and it must arrive at the document as well:
   * we want the value which is configured, not a value which is hardcoded elsewhere.
   */
  @Test
  fun `default lock timeout reaches the document`() {
    val documentManager = createDocumentManager()
    val timeoutOption = (documentManager.webDavStorageUi as WebDavStorageImpl).webDavLockTimeoutOption
    assertEquals(HttpDocument.NO_LOCK, timeoutOption.value)

    val document = (documentManager.getDocument(DOCUMENT_URL) as ProxyDocument).realDocument
    assertEquals(HttpDocument.NO_LOCK, (document as HttpDocument).lockTimeout)
  }

  /**
   * A negative timeout means "never lock". Locking is silently skipped today: the user who has
   * once set the value negative works without locks and finds no trace of it anywhere.
   */
  @Test
  fun `negative lock timeout is reported in the log`() {
    val resource = EasyMock.createMock<WebDavResource>(WebDavResource::class.java)
    EasyMock.expect(resource.url).andReturn(DOCUMENT_URL).anyTimes()
    EasyMock.replay(resource)

    val (acquired, records) = withRecordedLog {
      HttpDocument(resource, "user", "password", HttpDocument.NO_LOCK).acquireLock()
    }

    // Nothing failed, so the return value stays `true`: the setting says "never lock".
    assertTrue(acquired)
    // No WebDAV operation whatsoever was attempted: the mock would have failed on any other call.
    EasyMock.verify(resource)
    // ... but the fact is written to the log.
    val message = records.singleOrNull { it.level.isGreaterOrEqual(Level.INFO) }?.formattedMessage
    assertTrue(message != null && message.contains(DOCUMENT_URL)) {
      "Expected a log entry mentioning $DOCUMENT_URL, got ${records.map { "${it.level}: ${it.formattedMessage}" }}"
    }
  }

  /**
   * A non-negative timeout locks the resource, and minutes are converted to seconds.
   */
  @Test
  fun `positive lock timeout locks the resource`() {
    val resource = EasyMock.createMock<WebDavResource>(WebDavResource::class.java)
    EasyMock.expect(resource.url).andReturn(DOCUMENT_URL).anyTimes()
    EasyMock.expect(resource.exists()).andReturn(true)
    resource.lock(120 * 60)
    EasyMock.expectLastCall<Any>().once()
    EasyMock.replay(resource)

    val (acquired, records) = withRecordedLog {
      HttpDocument(resource, "user", "password", 120).acquireLock()
    }

    assertTrue(acquired)
    EasyMock.verify(resource)
    assertFalse(records.any { it.level.isGreaterOrEqual(Level.INFO) }) {
      "Did not expect a log entry, got ${records.map { "${it.level}: ${it.formattedMessage}" }}"
    }
  }
}
