/*
Copyright 2019 BarD Software s.r.o
Copyright 2005-2018 GanttProject team

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
package net.sourceforge.ganttproject.gui

import biz.ganttproject.app.*
import biz.ganttproject.core.calendar.ImportCalendarOption
import biz.ganttproject.core.option.GPOptionGroup
import biz.ganttproject.lib.fx.VBoxBuilder
import biz.ganttproject.storage.*
import biz.ganttproject.storage.cloud.GPCloudDocument
import biz.ganttproject.storage.cloud.installColloboque
import biz.ganttproject.storage.cloud.onboard
import biz.ganttproject.storage.cloud.webSocket
import com.google.common.collect.Lists
import com.sandec.mdfx.MDFXNode
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.geometry.Pos
import javafx.stage.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.*
import net.sourceforge.ganttproject.action.CancelAction
import net.sourceforge.ganttproject.action.OkAction
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.Document.DocumentException
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.document.ProxyDocument
import net.sourceforge.ganttproject.document.webdav.WebDavStorageImpl
import net.sourceforge.ganttproject.gui.projectopen.OpenOnlineDocumentChoice
import net.sourceforge.ganttproject.gui.projectopen.showForkDialog
// [Fork-Aenderung] eigener Konflikttext ausserhalb der Cloud.
import net.sourceforge.ganttproject.fork.ForkLocalizer
import net.sourceforge.ganttproject.gui.projectopen.showOfflineIsAheadDialog
import net.sourceforge.ganttproject.gui.projectopen.signinDialog
import net.sourceforge.ganttproject.gui.projectwizard.createNewProject
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.io.File
import java.io.IOException
import java.util.logging.Level
import javax.swing.JOptionPane
import javax.swing.SwingUtilities


class ProjectUIFacadeImpl(
  private val window: Window,
  private val myWorkbenchFacade: UIFacade,
  private val documentManager: DocumentManager,
  private val undoManager: GPUndoManager,
  private val projectImpl: GanttProjectImpl
) : ProjectUIFacade {
  private val i18n = GanttLanguage.getInstance()

  private val myConverterGroup = GPOptionGroup("convert", ProjectOpenStrategy.milestonesOption)
  private var isSaving = false

  override val projectOpenActivityFactory = ProjectOpenActivityFactory

  init {
    projectOpenActivityFactory.addBuilder(this::initStateMachine)
  }
  override fun saveProject(project: IGanttProject): Barrier<Boolean> {
    if (isSaving) {
      GPLogger.logToLogger("We're saving the project now. This save request was rejected")
    }
    val saveBarrier = SimpleBarrier<Boolean>()
    isSaving = true
    try {
      saveBarrier.await {
        afterSaveProject(project)
      }
      ProjectSaveFlow(project = project, onFinish = saveBarrier,
        signin = ::signinDialog,
        error = this::onError,
        saveAs = { saveProjectAs(project) }
      ).run()
    } finally {
      isSaving = false
      myWorkbenchFacade.activeChart.focus()
    }
    return saveBarrier
  }

  fun onError(ex: Exception) {
    dialog {
      it.addStyleSheet("/biz/ganttproject/app/dialogs.css")
      it.addStyleClass("dialog-alert")
      it.setHeader(
        VBoxBuilder("header").apply {
          addTitle(RootLocalizer.create("error.channel.itemTitle")).also { hbox ->
            hbox.alignment = Pos.CENTER_LEFT
            hbox.isFillHeight = true
          }
        }.vbox
      )
      it.setContent(
        //createAlertBody(ex.message ?: ""),
        MDFXNode(ex.message ?: "").also { it.styleClass.add("content-pane") }
      )
      it.removeButtonBar()
    }
  }

  override fun saveProjectAs(project: IGanttProject) {
    StorageDialogAction(
      window,
      project, this, project.documentManager,
      (project.documentManager.webDavStorageUi as WebDavStorageImpl).serversOption,
      StorageDialogBuilder.Mode.SAVE,
      "project.save"
    ).doRun()
  }

  enum class CantWriteChoice {MAKE_COPY, CANCEL, RETRY}

//  private fun formatWriteStatusMessage(doc: Document, canWrite: IStatus): String {
//    assert(canWrite.code >= 0 && canWrite.code < Document.ErrorCode.values().size)
//    return RootLocalizer.formatText(
//        key = "document.error.write.${Document.ErrorCode.values()[canWrite.code].name.toLowerCase()}",
//        doc.fileName, canWrite.message)
//  }

  private fun afterSaveProject(project: IGanttProject) {
    val document = project.document
    documentManager.addToRecentDocuments(document)
    val title = i18n.getText("appliTitle") + " [" + document.fileName + "]"
    myWorkbenchFacade.setWorkbenchTitle(title)
    if (document.isLocal) {
      val url = document.uri
      if (url != null) {
        val file = File(url)
        documentManager.changeWorkingDirectory(file.parentFile)
      }
    }
    project.isModified = false
  }

  /**
   * Check if the project has been modified, before creating or opening another
   * project
   *
   * @return true when the project is **not** modified or is allowed to be
   * discarded
   */
  override fun ensureProjectSaved(project: IGanttProject): Barrier<Boolean> {
    if (!project.isModified) {
      return ResolvedBarrier(true)
    }
    val result = SimpleBarrier<Boolean>()
    myWorkbenchFacade.showOptionDialog(JOptionPane.QUESTION_MESSAGE, i18n.getText("msg1"), arrayOf(
      CancelAction.create("cancel") {
        result.resolve(false)
      },
      OkAction.create("yes") {
        saveProject(project).await { success ->
          result.resolve(success)
        }
      },
      OkAction.create("no") {
        result.resolve(true)
      })
    )
    return result
  }

  private suspend fun installColloboqueClient(project: IGanttProject, doc: Document) {
    doc.asOnlineDocument()?.let {
      if (it is GPCloudDocument) {
        installColloboque(it, project, undoManager, myWorkbenchFacade)
        it.onboard(documentManager, webSocket)
      }
    }
  }

  private suspend fun onDocumentReady(project: IGanttProject, doc: Document, strategy: ProjectOpenStrategy) {
    DOCUMENT_LOGGER.debug("... document is ready")
    // If document is obtained, we need to run further steps.
    // Because of historical reasons they run in Swing thread (they may modify the state of Swing components)
    withContext(Dispatchers.Swing) {
      project.close()
      strategy.openFileAsIs(doc)
        .checkLegacyMilestones()
        .checkEarliestStartConstraints()
        .runUiTasks()
    }
  }

  private var cnt = 0
  private fun initStateMachine(stateMachine: ProjectOpenStateMachine) {
    val strategy = ProjectOpenStrategy(
      project = stateMachine.project,
      uiFacade = myWorkbenchFacade,
      signin = ::signinDialog,
      stateMachine = stateMachine
    )
    stateMachine.stateStarted.await {
      strategy.start(it.document)
    }
    stateMachine.stateDocumentForked.await { forkedDocument ->
      fun handleChoice(choice: OpenOnlineDocumentChoice) {
        when (choice) {
          OpenOnlineDocumentChoice.USE_OFFLINE -> {
            forkedDocument.fetchResult.useMirror = true
            stateMachine.state = ProjectOpenActivityDocumentReady(forkedDocument.document)
          }

          OpenOnlineDocumentChoice.USE_ONLINE -> {
            stateMachine.state = ProjectOpenActivityDocumentReady(forkedDocument.document)
          }

          OpenOnlineDocumentChoice.CANCEL -> {
            stateMachine.state = ProjectOpenActivityCancelled(stateMachine.project, forkedDocument.document)
          }
        }
      }
      when (forkedDocument.forkCase) {
        ProjectOpenActivityDocumentForked.ForkCase.OFFLINE_AHEAD -> {
          showOfflineIsAheadDialog(::handleChoice)
        }
        ProjectOpenActivityDocumentForked.ForkCase.FORK -> {
          showForkDialog(::handleChoice)
        }
      }
    }
    stateMachine.transition(stateMachine.stateDocumentReady,ProjectOpenActivityMainModelReady.ID) {
//      if (cnt > 1) {
//        return@transition ProjectOpenActivityFailed("Test Failure", "Failure when fetching", RuntimeException("Foo"))
//      }
//      cnt++
      onDocumentReady(stateMachine.project, it.document, strategy)
      installColloboqueClient(stateMachine.project, it.document)
      strategy.close()
      // --------------------------------
      ProjectOpenActivityMainModelReady(it.document)
    }
    stateMachine.stateCalculatedModelReady.await {
      stateMachine.state = ProjectOpenActivityCompleted(it.project, it.document)
    }
    stateMachine.stateCompleted.await {
      undoManager.die()
    }
    stateMachine.stateCancelled.await {
      // The user cancelled the opening process, so the previously open project remains intact.
      // We keep the undo history of that project, hence no undoManager.die() here.
    }
  }

  fun installAuthFlow(sm: ProjectOpenStateMachine, authFlow: AuthenticationFlow) {
    sm.stateAuthRequired.await { state ->
      authFlow {
        if (sm.state is ProjectOpenActivityAuthRequired) {
          sm.state = ProjectOpenActivityStarted(state.document)
        }
      }
    }
  }

  @Throws(IOException::class, DocumentException::class)
  override fun openProject(document: Document, project: IGanttProject,
                           authenticationFlow: AuthenticationFlow?): ProjectOpenStateMachine {
    val stateMachine = projectOpenActivityFactory.createStateMachine(project)
    try {
      installAuthFlow(stateMachine, authenticationFlow ?: ::signinDialog)
      stateMachine.start(document)
    } catch (e: Exception) {
      throw DocumentException("Can't open document $document", e)
    }
    return stateMachine
  }

  override fun createProject(project: IGanttProject) {
    ensureProjectSaved(project).await { result ->
      if (result) {
        createNewProject(project, myWorkbenchFacade).await { projectData ->
          project.close()
          project.document = documentManager.newUntitledDocument()

          project.projectName = projectData.name
          project.description = projectData.description
          project.organization = projectData.organization
          project.webLink = projectData.webLink

          project.activeCalendar.importCalendar(projectData.calendar, ImportCalendarOption(ImportCalendarOption.Values.REPLACE))
          projectImpl.fireProjectCreated()
          // [Fork-Aenderung] Die Spalten dieses Forks im neuen Projekt anlegen.
          //
          // WAS: ensureCapacityColumns() traegt die benutzerdefinierten Spalten ein, die der Fork
          // braucht -- Tagesleistung, Auslastung, "Fertig bis", "Warten", "Termin fest",
          // Wiederholung. Ohne sie ist die aufwandsgetriebene Planung fuer ein neu angelegtes
          // Projekt unerreichbar: was man nicht sieht, kann man nicht eintragen.
          //
          // WARUM HIER UND NICHT FRUEHER, und das ist der Punkt: der Aufruf muss NACH
          // fireProjectCreated() stehen, also nach ALLEN Listenern. Einer von ihnen
          // (ProjectEventListenerImpl.projectCreated) verwirft die Spiegeldatenbank und baut sie
          // neu auf. Wer diese Zeile davor schiebt -- oder sie in einen eigenen Listener verlegt,
          // dessen Platz in der Registrierungsreihenfolge niemand festlegt --, legt die Spalten
          // gegen einen Spiegel an, der gleich danach ersetzt wird. onCustomColumnChange verwirft
          // sie dann stillschweigend, und zwar ohne Fehlermeldung. Genau diese Fehlerfamilie
          // beschreibt der Kommentar in ProjectEventListenerImpl.
          //
          // WARUM VOR isModified: das Anlegen der Spalten ist eine Modellaenderung. Stuende der
          // Aufruf danach, gaelte ein frisch angelegtes Projekt sofort als ungespeichert.
          //
          // WARUM NICHT IN newProject(): dort lief der Aufruf synchron direkt nach
          // createProject() -- also am noch offenen, alten Projekt, das der Rueckruf hier gleich
          // schliesst und dessen Manager er zuruecksetzt. Der Aufruf war damit wirkungslos, und
          // bei ABBRUCH des Assistenten veraenderte er das offene Projekt trotzdem: der Barrier
          // aus createNewProject wird ausschliesslich in onOkPressed aufgeloest, dieser Rueckruf
          // laeuft bei Abbruch also gar nicht, die alte Zeile aber schon.
          projectImpl.ensureCapacityColumns()
          // A new project just got created, so it is not yet modified
          projectImpl.isModified = false
          undoManager.die()
        }
      }
    }
  }

  override fun getOptionGroups(): Array<GPOptionGroup> {
    return arrayOf(myConverterGroup)
  }
}

class ProjectSaveFlow(
  private val project: IGanttProject,
  private val onFinish: SimpleBarrier<Boolean>,
  private val signin: (()->Unit) -> Unit,
  private val error: (Exception) -> Unit,
  private val saveAs: () -> Unit) {

  private fun done(success: Boolean) {
    onFinish.resolve(success)
  }

  fun run() {
    try {
      project.document?.let {
        if (it.asLocalDocument()?.canRead() == false) {
          saveProjectAs(project)
        } else {
          if (it is ProxyDocument) {
            it.createContents()
          }
          saveProjectTryWrite(project, it)
        }
      } ?: run {
        saveProjectAs(project)
      }
    } catch (ex: Exception) {
      error(ex)
      done(success = false)
    }
  }


  private fun saveProjectTryWrite(project: IGanttProject, document: Document) {
    val canWrite = document.canWrite()
    if (!canWrite.isOK) {
      GPLogger.getLogger(Document::class.java).log(Level.INFO, canWrite.message, canWrite.exception)
      OptionPaneBuilder<ProjectUIFacadeImpl.CantWriteChoice>().also {
        it.i18n = RootLocalizer.createWithRootKey(
          rootKey = "document.error.write.cantWrite",
          baseLocalizer = RootLocalizer
        )
        it.styleClass = "dlg-lock"
        it.styleSheets.add("/biz/ganttproject/storage/cloud/GPCloudStorage.css")
        it.styleSheets.add("/biz/ganttproject/storage/StorageDialog.css")
        it.titleString.update(document.fileName)
        it.titleHelpString?.update(canWrite.message)
        it.graphic = FontAwesomeIconView(FontAwesomeIcon.LOCK, "64").also { icon ->
          icon.styleClass.add("img")
        }
        it.elements = listOf(
          OptionElementData("document.option.makeCopy", ProjectUIFacadeImpl.CantWriteChoice.MAKE_COPY, true),
          OptionElementData("cancel", ProjectUIFacadeImpl.CantWriteChoice.CANCEL, false),
          OptionElementData("generic.retry", ProjectUIFacadeImpl.CantWriteChoice.RETRY, false),
        )
        it.showDialog { choice ->
          SwingUtilities.invokeLater {
            when (choice) {
              ProjectUIFacadeImpl.CantWriteChoice.MAKE_COPY -> {
                saveProjectAs(project)
              }
              ProjectUIFacadeImpl.CantWriteChoice.RETRY -> {
                saveProjectTryWrite(project, document)
              }
              else -> {
                done(success = false)
              }
            }
          }
        }
      }
    } else {
      saveProjectTryLock(project, document)
    }
  }

  private fun saveProjectTryLock(project: IGanttProject, document: Document) {
    saveProjectTrySave(project, document)
  }

  enum class VersionMismatchChoice { OVERWRITE, MAKE_COPY }

  private fun saveProjectTrySave(project: IGanttProject, document: Document) {
    try {
      saveProject(document)
    } catch (e: VersionMismatchException) {
      done(success = false)
      val onlineDoc = document.asOnlineDocument()
      // [Fork-Aenderung] Der Dialog erscheint auch OHNE Cloud-Dokument.
      //
      // FEHLER IM ORIGINAL: Hier stand `if (onlineDoc != null) { … }` ohne else. Ein
      // Versionskonflikt bei einem Dokument, das keine GanttProject-Cloud-Datei ist — etwa auf
      // einem WebDAV-Server — wurde damit stillschweigend verschluckt: kein Dialog, keine
      // Meldung, und das Speichern galt als erledigt, obwohl nichts geschrieben wurde.
      //
      // "Überschreiben" bleibt an das Cloud-Dokument gebunden, weil nur dieses write(force=true)
      // kennt. Für WebDAV wird der Knopf deshalb gar nicht erst angeboten — ein Knopf, der nichts
      // tut, wäre schlimmer als keiner.
      run {
        OptionPaneBuilder<VersionMismatchChoice>().also {
          // [Fork-Aenderung] Ohne Cloud-Dokument ein eigener Text.
          //
          // "cloud.versionMismatch" ist fuer die GanttProject-Cloud geschrieben und erklaert den
          // Konflikt mit "Version aus dem Projektverlauf". Auf einem WebDAV-Server stimmt das
          // nicht: dort hat schlicht jemand anders die Datei geaendert. Am Bildschirm beobachtet --
          // der Dialog nannte einen Grund, den es in diesem Fall gar nicht gab, und ein
          // falscher Grund fuehrt zur falschen Entscheidung.
          it.i18n = when {
            // Niemand hat geaendert -- der Server kann die Frage nicht beantworten. Eigener Text,
            // sonst sucht der Benutzer einen Kollegen, den es nicht gibt.
            e.versioningUnavailable -> noVersioningLocalizer
            onlineDoc != null ->
              RootLocalizer.createWithRootKey(rootKey = "cloud.versionMismatch", baseLocalizer = RootLocalizer)
            else -> foreignChangeLocalizer
          }
          it.styleClass = "dlg-lock"
          it.styleSheets.add("/biz/ganttproject/storage/cloud/GPCloudStorage.css")
          it.styleSheets.add("/biz/ganttproject/storage/StorageDialog.css")
          it.graphic = FontAwesomeIconView(FontAwesomeIcon.CODE_FORK, "64").also {icon ->
            icon.styleClass.add("img")
          }
          it.elements = Lists.newArrayList(
            OptionElementData("document.option.makeCopy", VersionMismatchChoice.MAKE_COPY, true)
          ).also { list ->
            if (e.canOverwrite && onlineDoc != null) {
              list.add(OptionElementData("option.overwrite", VersionMismatchChoice.OVERWRITE, false))
            }
          }
          it.showDialog { choice ->
            SwingUtilities.invokeLater {
              when (choice) {
                VersionMismatchChoice.OVERWRITE -> {
                  // Nur erreichbar, wenn der Knopf angeboten wurde -- und das setzt onlineDoc
                  // voraus. Der sichere Zugriff haelt die Bedingung im Code fest, statt sie nur
                  // in der Knopfliste zu haben.
                  onlineDoc?.write(force = true)
                }
                VersionMismatchChoice.MAKE_COPY -> {
                  saveProjectAs(project)
                }
              }
            }
          }

        }
      }
    } catch (e: ForbiddenException) {
      signin {
        saveProjectTrySave(project, document)
      }
    } catch (e: PaymentRequiredException) {
      done(success = false)
      error(e)
    }
  }

  @Throws(IOException::class)
  private fun saveProject(document: Document) {
    //myWorkbenchFacade.setStatusText(GanttLanguage.getInstance().getText("saving") + " " + document.path)
    document.write()
    done(success = true)
  }

  private fun saveProjectAs(project: IGanttProject) {
    done(success = false)
    saveAs()
  }
}

private val DOCUMENT_LOGGER = GPLogger.create("Document.Info")

/**
 * [Fork-Aenderung] Texte fuer einen Schreibkonflikt ausserhalb der GanttProject-Cloud.
 *
 * Stellt `fork.webdav.versionMismatch.` vor den Schluessel und faellt sonst auf den globalen
 * Schluessel zurueck. Der Rueckfall ist noetig, weil die Knopfbeschriftungen
 * (`document.option.makeCopy`) global liegen und hier nicht doppelt gepflegt werden sollen.
 */
private val foreignChangeLocalizer = forkPrefixedLocalizer("fork.webdav.versionMismatch.")

/**
 * [Fork-Aenderung] Texte fuer "der Server kann keine Versionspruefung beantworten".
 *
 * Eigener Text, weil hier NIEMAND die Datei geaendert hat. Der Konflikttext waere schlicht falsch.
 */
private val noVersioningLocalizer = forkPrefixedLocalizer("fork.webdav.noVersioning.")

/**
 * Stellt [prefix] vor den Schluessel und faellt sonst auf den globalen Schluessel zurueck. Der
 * Rueckfall ist noetig, weil die Knopfbeschriftungen (`document.option.makeCopy`) global liegen und
 * hier nicht doppelt gepflegt werden sollen.
 */
private fun forkPrefixedLocalizer(prefix: String) = object : Localizer {
  override fun create(key: String): LocalizedString = LocalizedString(key, this)
  override fun formatTextOrNull(key: String, vararg args: Any): String? =
    ForkLocalizer.formatTextOrNull("$prefix$key", *args)
      ?: RootLocalizer.formatTextOrNull(key, *args)
}
