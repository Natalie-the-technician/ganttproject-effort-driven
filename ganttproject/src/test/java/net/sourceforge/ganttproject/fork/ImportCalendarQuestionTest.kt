/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

Does an unanswered calendar question really leave everything alone?

NOT "IS THE DEFAULT EMPTY". That would be the cheap version of this test and it would pass over
a build in which the import runs anyway and merely takes no calendar with it — which is the old
behaviour under a new name. What is measured here is the pair Natalie named: with no answer given,
NO calendar is taken over AND none is discarded, and the import DOES NOT PROCEED.

Her reason is the finding of 08.10.2026: a project with working hours of its own computes
differently after an import than it did before, and nobody notices. A silent case, made loud.

EVERY CASE HERE HAS A POSITIVE CONTROL NEXT TO IT, because "nothing happened" is the state a
broken measurement reports too: the same calls with a real answer (REPLACE) have to show the
holiday arriving, otherwise the assertions about UNDECIDED prove only that the test cannot see
anything at all.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.calendar.CalendarEvent
import biz.ganttproject.core.calendar.GPCalendar
import biz.ganttproject.core.calendar.ImportCalendarOption
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.importer.AskingImportCalendarOption
import net.sourceforge.ganttproject.importer.BufferProject
import net.sourceforge.ganttproject.importer.ImporterFromGanttFile
import net.sourceforge.ganttproject.importer.ImporterWizardModel
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.resource.HumanResourceMerger
import net.sourceforge.ganttproject.resource.MergeResourcesEnum
import org.easymock.EasyMock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.osgi.service.prefs.Preferences
import java.io.File
import java.text.DateFormat
import java.util.Locale

class ImportCalendarQuestionTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private fun holiday(year: Int, month0: Int, day: Int, title: String): CalendarEvent =
    CalendarEvent.newEvent(
      CalendarFactory.createGanttCalendar(year, month0, day).time, false,
      CalendarEvent.Type.HOLIDAY, title, null
    )

  private fun titlesOf(calendar: GPCalendar): List<String> =
    calendar.publicHolidays.map { it.title }

  /** The target project: one holiday of its own, and Saturday turned into a working day. */
  private fun targetCalendar(): WeekendCalendarImpl = WeekendCalendarImpl().also {
    it.setPublicHolidays(listOf(holiday(2026, 9, 3, "Zielfeiertag 03.10.2026")))
    it.setWeekDayType(java.util.Calendar.SATURDAY, GPCalendar.DayType.WORKING)
  }

  /** The imported project: a different holiday, and Saturday a weekend as usual. */
  private fun sourceCalendar(): WeekendCalendarImpl = WeekendCalendarImpl().also {
    it.setPublicHolidays(listOf(holiday(2026, 11, 24, "Quellfeiertag 24.12.2026")))
  }

  @Test
  fun `die kalenderfrage ist nicht vorbelegt`() {
    val option = AskingImportCalendarOption()
    assertTrue(option.isUndecided(), "frisch gebaut ist die Frage unbeantwortet")
    assertEquals(ImportCalendarOption.Values.UNDECIDED, option.getSelectedValue())
    // SAID OUT LOUD AND NOT DERIVED: neither of the two answers Natalie named is pre-selected.
    // Asserting only `== UNDECIDED` would still pass if UNDECIDED were later made an alias of one
    // of them.
    assertFalse(option.getSelectedValue() == ImportCalendarOption.Values.NO, "'bestehende behalten' ist NICHT vorausgewaehlt")
    assertFalse(option.getSelectedValue() == ImportCalendarOption.Values.REPLACE, "'importierten uebernehmen' ist NICHT vorausgewaehlt")
    assertFalse(option.getSelectedValue() == ImportCalendarOption.Values.MERGE, "'zusammenfuehren' ist NICHT vorausgewaehlt")
  }

  @Test
  fun `ohne antwort wird kein kalender uebernommen und keiner verworfen`() {
    val ziel = targetCalendar()
    ziel.importCalendar(sourceCalendar(), ImportCalendarOption(ImportCalendarOption.Values.UNDECIDED))

    // NOT taken over.
    assertFalse(
      titlesOf(ziel).contains("Quellfeiertag 24.12.2026"),
      "der Feiertag der Quelle darf ohne Antwort NICHT uebernommen werden"
    )
    // NOT discarded either -- the other half of the pair, and the one a mere `return` in the wrong
    // place would break.
    assertEquals(
      listOf("Zielfeiertag 03.10.2026"), titlesOf(ziel),
      "der eigene Feiertag des Ziels muss unangetastet bleiben"
    )
    // The week day types are the part that used to fall through the if-chain without an else:
    // Saturday was a working day here and the source would have made it a weekend again.
    assertEquals(
      GPCalendar.DayType.WORKING, ziel.getWeekDayType(java.util.Calendar.SATURDAY),
      "auch die Wochentagstypen des Ziels bleiben, wie sie waren"
    )
  }

  @Test
  fun `positivkontrolle - mit antwort kommt der kalender an`() {
    val ziel = targetCalendar()
    ziel.importCalendar(sourceCalendar(), ImportCalendarOption(ImportCalendarOption.Values.REPLACE))
    assertEquals(
      listOf("Quellfeiertag 24.12.2026"), titlesOf(ziel),
      "mit REPLACE ersetzt der importierte Kalender den bestehenden -- sonst misst der Test oben nichts"
    )
    assertEquals(
      GPCalendar.DayType.WEEKEND, ziel.getWeekDayType(java.util.Calendar.SATURDAY),
      "und auch die Wochentagstypen wandern mit"
    )
  }

  @Test
  fun `der importeur nennt die offene frage, solange sie offen ist`() {
    val importer = contextualizedImporter(EasyMock.createNiceMock<UIFacade>(UIFacade::class.java))
    val question = importer.unansweredQuestion
    assertNotNull(question, "ohne Antwort meldet der Importeur die offene Frage")
    assertTrue(
      question!!.isNotBlank() && !question.startsWith("fork."),
      "und zwar als lesbaren Text, nicht als unaufgeloesten Schluessel: <$question>"
    )

    // Answered -- the question has to go away, otherwise it would be unanswerable rather than
    // unanswered and the wizard's finish button would never come back.
    importer.calendarOptionForTesting.setSelectedValue(ImportCalendarOption.Values.NO)
    assertNull(importer.unansweredQuestion, "mit Antwort ist keine Frage mehr offen")
  }

  @Test
  fun `ohne antwort geht der import nicht weiter`() {
    // A STRICT mock, and that is the whole case: every call the importer makes has to be declared
    // here. The declared one is the message; getUndoManager(), which is the first thing the real
    // import does, is NOT declared, so reaching it fails the test. "Nothing happens" is measured
    // as "nothing is called", not as "no exception was thrown".
    //
    // THE IMPORTER GETS A MODEL WITH A REAL FILE IN IT, and that detail is what makes this case
    // sharp rather than merely red. Measured on 08.10.2026: without it, removing the guard made
    // this case fail with `NullPointerException: ... ImporterWizardModel.getFile() because
    // "this.myModel" is null` -- red, but one statement BEFORE the undo manager, so the red said
    // nothing about whether the import proceeded. With a model and a file, the only thing between
    // run() and getUndoManager() is the guard itself.
    val uiFacade = EasyMock.createStrictMock<UIFacade>(UIFacade::class.java)
    EasyMock.expect(uiFacade.showErrorDialog(EasyMock.anyString()))
    EasyMock.replay(uiFacade)

    val importer = contextualizedImporter(uiFacade)
    importer.run()

    EasyMock.verify(uiFacade)
  }

  @Test
  fun `der assistent laesst den import nicht zu, solange die frage offen ist`() {
    // THE LOCK THE USER SEES. Wizard.kt:276 sets `finishButton.isDisable = !canFinish()`, so this
    // predicate IS the enabled state of the Import button -- measured here instead of at a
    // screenshot, because a picture shows the button but not why it is grey.
    val model = ImporterWizardModel()
    val file = File.createTempFile("ImportCalendarQuestionTest", ".gan").also { it.deleteOnExit() }
    val importer = ImporterFromGanttFile().also {
      it.setContext(GanttProjectImpl(), EasyMock.createNiceMock<UIFacade>(UIFacade::class.java), nicePreferences())
      it.setModel(model)
    }
    model.importer = importer
    model.file = file

    assertFalse(model.canFinish(), "mit offener Kalenderfrage ist der Import-Knopf gesperrt")

    // Answered -- and now it has to open. Without the ChangeValueListener in the importer the
    // predicate would flip here but the button would not, which is why the listener exists; this
    // case covers the predicate, the running program covers the button.
    importer.calendarOptionForTesting.setSelectedValue(ImportCalendarOption.Values.NO)
    assertTrue(model.canFinish(), "mit Antwort ist er frei -- sonst waere die Frage unbeantwortbar")
  }

  @Test
  fun `importProject wirft, bevor es das erste mal schreibt`() {
    val project = GanttProjectImpl()
    val bufferProject = EasyMock.createStrictMock<BufferProject>(BufferProject::class.java)
    EasyMock.replay(bufferProject)

    val thrown = assertThrows(IllegalArgumentException::class.java) {
      project.importProject(
        bufferProject,
        HumanResourceMerger.MergeResourcesOption().also { it.setValue(MergeResourcesEnum.BY_NAME.name.lowercase()) },
        ImportCalendarOption(ImportCalendarOption.Values.UNDECIDED),
        false
      )
    }
    assertTrue(
      thrown.message!!.contains("UNDECIDED"),
      "die Meldung nennt den Grund, nicht nur dass etwas schiefging: <${thrown.message}>"
    )
    // The buffer project is a strict mock with nothing declared: had importProject read a single
    // thing out of it before throwing, verify() would fail. That is what "before the first write"
    // means here -- the check is the first statement of the method, ahead of roleManager.importData.
    EasyMock.verify(bufferProject)
    assertTrue(project.taskManager.tasks.isEmpty(), "und im Zielprojekt steht danach kein Vorgang")
  }

  /**
   * An importer with a context, which is what it needs before [ImporterFromGanttFile.run] may be
   * called at all.
   *
   * The preferences are a nice mock on purpose: what the importer stores there is the resource
   * merge option, which this test does not measure. The calendar answer is deliberately NOT
   * persisted by the importer -- a stored answer would be a pre-selection again.
   */
  private fun nicePreferences(): Preferences {
    val node = EasyMock.createNiceMock<Preferences>(Preferences::class.java)
    EasyMock.expect(node.get(EasyMock.anyString(), EasyMock.anyString()))
      .andReturn(MergeResourcesEnum.DEFAULT.name.lowercase()).anyTimes()
    EasyMock.replay(node)
    val prefs = EasyMock.createNiceMock<Preferences>(Preferences::class.java)
    EasyMock.expect(prefs.node(EasyMock.anyString())).andReturn(node).anyTimes()
    EasyMock.replay(prefs)
    return prefs
  }

  private fun contextualizedImporter(uiFacade: UIFacade): ImporterFromGanttFile {
    val prefs = nicePreferences()

    // A REAL wizard model with a REAL file. See the comment in the "geht nicht weiter" case: an
    // importer without a model fails one statement too early, and the case would then be red for
    // the wrong reason.
    val model = ImporterWizardModel()
    val file = File.createTempFile("ImportCalendarQuestionTest", ".gan").also { it.deleteOnExit() }
    model.file = file

    return ImporterFromGanttFile().also {
      it.setContext(GanttProjectImpl(), uiFacade, prefs)
      it.setModel(model)
    }
  }
}

/** The importer's calendar option, reachable for the test without opening it to everybody. */
private val ImporterFromGanttFile.calendarOptionForTesting: ImportCalendarOption
  get() = ImporterFromGanttFile::class.java.getDeclaredField("myImportCalendarOption")
    .also { it.isAccessible = true }
    .get(this) as ImportCalendarOption
