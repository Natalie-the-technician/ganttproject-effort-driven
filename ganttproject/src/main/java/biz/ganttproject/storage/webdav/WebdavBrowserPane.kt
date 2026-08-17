/*
Copyright 2016-2020 BarD Software s.r.o

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
package biz.ganttproject.storage.webdav

import biz.ganttproject.app.RootLocalizer
import biz.ganttproject.storage.*
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.scene.layout.Pane
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.webdav.HttpDocument
import net.sourceforge.ganttproject.document.webdav.WebDavResource
import net.sourceforge.ganttproject.document.webdav.WebDavServerDescriptor
import java.util.function.Consumer

/**
 * This is a user interface showing the contents of WebDAV storage.
 *
 * @author dbarashev@bardsoftware.com
 */
class WebdavBrowserPane(private val myServer: WebDavServerDescriptor,
                        private val myMode: StorageDialogBuilder.Mode,
                        private val myOpenDocument: (Document) -> Unit,
                        private val myDialogUi: StorageDialogBuilder.DialogUi,
                        /** [Fork-Aenderung] Sperrdauer in Minuten; negativ heisst "nicht sperren". */
                        private val myLockTimeout: Int) {
  private lateinit var path: Path
  private val myLoadService: WebdavLoadService = WebdavLoadService(myServer)
  private val myState = State(server = myServer, resource = null, filename = null, folder = null)
  private lateinit var paneElements: BrowserPaneElements<WebDavResourceAsFolderItem>

  private fun createResource(state: State): WebDavResource {
    return state.resource ?: myLoadService.createResource(state.folder, state.filename)
  }

  fun createStorageUi(): Pane {
    val builder = BrowserPaneBuilder<WebDavResourceAsFolderItem>(this.myMode, this.myDialogUi::error) { path, success, loading ->
      this.path = path
      refresh()
    }
    val isLockingSupported = SimpleBooleanProperty()
    isLockingSupported.addListener { _, _, newValue ->
      System.err.println("is locking supported=" + newValue!!)
    }
    fun onAction() {
      if (myMode == StorageDialogBuilder.Mode.SAVE) {
        myState.filename = myState.filename!!.withGanExtension()
      }
      myOpenDocument(createDocument(myState.server, createResource(myState), myLockTimeout))
    }
    builder.apply {
      withI18N(RootLocalizer.createWithRootKey("storageService.webdav", BROWSE_PANE_LOCALIZER))
      withBreadcrumbs(DocumentUri(listOf(), true, myServer.name))
      withListView(
          onSelectionChange = { item ->
            if (item.isDirectory) {
              myState.folder = item.myResource
              myState.filename = null
              myState.resource = null
            } else {
              myState.resource = item.myResource
            }
          },
          onLaunch = {
            myOpenDocument(createDocument(myState.server, createResource(myState), myLockTimeout))
          },
          onNameTyped = { filename, _, withEnter, withControl ->
            myState.filename = filename
            if (withEnter && withControl) {
              onAction()
            }
          },
          onDelete = { item ->
            deleteResource(item)
          },
          onLock = { item ->
            toggleLockResource(item)
          },
          canLock = isLockingSupported,
          canDelete = SimpleBooleanProperty(true)
      )


      withActionButton { btn ->
        btn.addEventHandler(ActionEvent.ACTION) {
          onAction()
        }
      }
    }
    paneElements = builder.build()
    paneElements.breadcrumbView?.show()
    return paneElements.browserPane
  }

  private fun refresh() {
    val success = Consumer<ObservableList<WebDavResourceAsFolderItem>> { Platform.runLater { paneElements.listView.setResources(it) } }
    val wrappers = FXCollections.observableArrayList<WebDavResourceAsFolderItem>()
    val selectedName = paneElements.listView.selectedResource.orElse(null)?.name
    val consumer = Consumer { webDavResources: ObservableList<WebDavResource> ->
      webDavResources.forEach { resource -> wrappers.add(WebDavResourceAsFolderItem(resource)) }
      success.accept(wrappers)
      if (selectedName != null) {
        val selectedIdx = webDavResources.indexOfFirst { it.name == selectedName }
        if (selectedIdx >= 0) {
          Platform.runLater { paneElements.listView.listView.selectionModel.select(selectedIdx) }
        }
      }
    }
    loadFolder(path, paneElements.busyIndicator, consumer, myDialogUi)
  }

  private fun deleteResource(folderItem: WebDavResourceAsFolderItem) {
    val resource = folderItem.myResource
    try {
      resource.delete()
      refresh()
    } catch (e: WebDavResource.WebDavException) {
      myDialogUi.error(e)
    }
  }

  private fun toggleLockResource(folderItem: WebDavResourceAsFolderItem) {
    try {
      val resource = folderItem.myResource
      if (resource.isLocked) {
        resource.unlock()
      } else {
        resource.lock(-1)
      }
      refresh()
    } catch (e: WebDavResource.WebDavException) {
      myDialogUi.error(e)
    }
  }

  private fun loadFolder(selectedPath: Path,
                         showMaskPane: Consumer<Boolean>,
                         setResult: Consumer<ObservableList<WebDavResource>>,
                         dialogUi: StorageDialogBuilder.DialogUi) {
    myLoadService.setPath(selectedPath.toString())
    myState.folder = myLoadService.createRootResource()
    myLoadService.apply {
      onSucceeded = EventHandler {
        setResult.accept(value)
        showMaskPane.accept(false)
      }
      onFailed = EventHandler {
        showMaskPane.accept(false)
        // [Fork-Aenderung] Den Grund nennen, statt ihn wegzuwerfen.
        //
        // FEHLER IM ORIGINAL: hier stand dialogUi.error("WebdavService failed!", "", null). Die
        // Ausnahme des Dienstes wurde weder angezeigt noch protokolliert -- der Benutzer bekam
        // einen roten Kasten mit leerem Text, und im Protokoll stand nichts. Am Bildschirm
        // beobachtet: der Server war nicht erreichbar, und es war nicht feststellbar, ob es an
        // Passwort, Adresse, Zertifikat oder Netz lag.
        //
        // Falsches Passwort und unerreichbarer Server sehen fuer den Benutzer sonst gleich aus,
        // fuehren aber zu voellig verschiedenen naechsten Schritten.
        val cause = myLoadService.exception
        GPLogger.log(cause ?: RuntimeException("WebdavService failed, aber ohne Ausnahme"))
        // Die UNTERSTE Ursache zeigen, nicht die oberste. Am Bildschirm gesehen: oben steht
        // "I/O problems when accessing <Servername>" -- das nennt weder Rechner noch Grund und
        // ist fuer den Benutzer wertlos. Unten steht "Der angegebene Host ist unbekannt
        // (beispiel.ungueltig)", und damit kann er etwas anfangen.
        dialogUi.error("WebdavService failed!", describeCause(cause), cause)
      }
      onCancelled = EventHandler {
        showMaskPane.accept(false)
          GPLogger.log("WebdavService cancelled!")
      }
      restart()
    }
    showMaskPane.accept(true)
  }
}

/**
 * [Fork-Aenderung] Die unterste Ursache einer Ausnahmekette als Text.
 *
 * Die oberste Meldung ist hier regelmaessig die nichtssagende: "I/O problems when accessing
 * <Servername>". Der Servername steht dort, weil `WebdavLoadService` ihn absichtlich als
 * Anzeigenamen in die WebDavUri setzt -- er sagt also nichts ueber Rechner oder Adresse. Erst die
 * unterste Ursache nennt, was wirklich los war: unbekannter Rechner, abgelehnte Anmeldung,
 * Zeitueberschreitung.
 */
private fun describeCause(failure: Throwable?): String {
  var current = failure ?: return ""
  while (true) {
    val next = current.cause ?: break
    if (next === current) break
    current = next
  }
  return current.message ?: current.javaClass.simpleName
}

/**
 * This is an adapter class for plugging WebDavResource into FolderView.
 */
class WebDavResourceAsFolderItem(val myResource: WebDavResource) : FolderItem {
  override val isLocked: Boolean
    get() {
      try {
        return myResource.isLocked
      } catch (e: WebDavResource.WebDavException) {
        throw RuntimeException(e)
      }

    }

  override val isLockable: Boolean
    get() = myResource.isLockSupported(true)

  override val canChangeLock: Boolean
    get() = isLockable

  override val name: String
    get() = myResource.name

  override val basePath: String
    get() = myResource.parent.absolutePath

  override val isDirectory: Boolean
    get() {
      try {
        return myResource.isCollection
      } catch (e: WebDavResource.WebDavException) {
        throw RuntimeException(e)
      }

    }
  override val tags = mapOf<FolderItemTag, String>()
}

private data class State(
        val server: WebDavServerDescriptor,
        var resource: WebDavResource?,
        var filename: String?,
        var folder: WebDavResource?
)

private fun createDocument(server: WebDavServerDescriptor, resource: WebDavResource,
                           lockTimeout: Int): Document {
  // [Fork-Aenderung] Hier stand fest HttpDocument.NO_LOCK. Das ist der Weg, den ein Mensch
  // tatsaechlich benutzt -- die Ablage-Auswahl -- und darueber geoeffnete Projekte wurden deshalb
  // NIE gesperrt, egal was in den Einstellungen stand. Am Server nachgewiesen: ein Schreibversuch
  // von aussen lieferte 204 statt 423, obwohl das Projekt offen war.
  return HttpDocument(resource, server.username, server.password, lockTimeout)
}
