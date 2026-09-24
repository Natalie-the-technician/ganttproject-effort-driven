/*
Copyright 2026 BarD Software s.r.o

This file is part of GanttProject, an open-source project management tool.

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
package biz.ganttproject.settings

import biz.ganttproject.core.option.GPOptionGroup
import biz.ganttproject.core.option.ObservableObject
import javafx.application.Platform
import javafx.embed.swing.SwingNode
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.image.WritableImage
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.gui.options.model.OptionPageProvider
import org.easymock.EasyMock
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A page of the settings dialog, and the content of a Swing-in-JavaFX dialog, are embedded into the
 * scene graph with a [SwingNode] whose content is assigned later, on the Swing thread. Until the
 * embedded frame reports a size, `SwingNode.prefWidth` and `SwingNode.maxWidth` return 0, so the
 * node is laid out to 0x0 and there is nothing on screen. These tests measure the pixels that
 * actually reach the scene, because a page whose parts are correctly sized but never painted looks
 * exactly like a page that was never built.
 */
class SwingNodeRepaintTest {
  /** Long enough for the embedded frame to report its size; measured to take under 900 ms. */
  private val timeoutMs = 6000L
  private val stepMs = 200L
  private val sceneWidth = 600.0
  private val sceneHeight = 400.0

  private fun swingPage(color: java.awt.Color) = JPanel().also {
    it.background = color
    it.isOpaque = true
    it.preferredSize = Dimension(240, 120)
  }

  private fun countPixels(image: WritableImage, matches: (javafx.scene.paint.Color) -> Boolean): Int {
    var found = 0
    val reader = image.pixelReader
    for (y in 0 until image.height.toInt()) {
      for (x in 0 until image.width.toInt()) {
        if (matches(reader.getColor(x, y))) found++
      }
    }
    return found
  }

  private fun isMagenta(c: javafx.scene.paint.Color) = c.red > 0.9 && c.blue > 0.9 && c.green < 0.1
  private fun isCyan(c: javafx.scene.paint.Color) = c.green > 0.9 && c.blue > 0.9 && c.red < 0.1

  /** Waits until the scene shows at least [wanted] matching pixels, and returns what it saw last. */
  private suspend fun waitForPixels(stage: Stage, wanted: Int, matches: (javafx.scene.paint.Color) -> Boolean): Int {
    var seen = 0
    var waited = 0L
    while (waited < timeoutMs) {
      delay(stepMs)
      waited += stepMs
      seen = countPixels(stage.scene.snapshot(null), matches)
      if (seen >= wanted) return seen
    }
    return seen
  }

  private fun pageProvider(color: java.awt.Color) = object : OptionPageProvider {
    override fun getOptionGroups(): Array<GPOptionGroup> = arrayOf()
    override fun getPageID() = "test-page"
    override fun hasCustomComponent() = true
    override fun buildPageComponent(): java.awt.Component = swingPage(color)
    override fun init(project: IGanttProject, uiFacade: UIFacade) {}
    override fun commit() {}
    override fun setActive(isActive: Boolean) {}
  }

  /**
   * The settings dialog resizes itself for the first page it shows and then stops doing so, so every
   * page after the first has to become visible without a window resize.
   */
  @Test
  fun `a settings page other than the first one is painted`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      Platform.setImplicitExit(false)
      val project = EasyMock.niceMock<IGanttProject>(IGanttProject::class.java)
      val uiFacade = EasyMock.niceMock<UIFacade>(UIFacade::class.java)
      EasyMock.replay(project, uiFacade)

      val editItem = ObservableObject<OptionPageItem?>("", null)
      val stage = Stage()
      val pageUi = OptionPageUi(editItem) { stage.sizeToScene() }
      stage.scene = Scene(pageUi.node as BorderPane, sceneWidth, sceneHeight)
      stage.show()
      delay(stepMs)

      val firstPage = OptionPageItem("first", provider = pageProvider(java.awt.Color(255, 0, 255)),
        project = project, uiFacade = uiFacade)
      val secondPage = OptionPageItem("second", provider = pageProvider(java.awt.Color(0, 255, 255)),
        project = project, uiFacade = uiFacade)
      val wholePage = (sceneWidth * sceneHeight).toInt()

      editItem.set(firstPage)
      val firstSeen = waitForPixels(stage, wholePage, ::isMagenta)
      assertTrue(firstSeen >= wholePage,
        "the first settings page covers only $firstSeen of $wholePage pixels of the dialog")

      editItem.set(secondPage)
      val secondSeen = waitForPixels(stage, wholePage, ::isCyan)
      stage.close()
      assertTrue(secondSeen >= wholePage,
        "after switching pages, the second settings page covers only $secondSeen of $wholePage " +
          "pixels of the dialog, so it is at least partly unpainted")
    }
  }

  /**
   * A Swing-in-JavaFX dialog sizes its window to the scene right after handing the content to the
   * [SwingNode]. If the node has not reported a size by then, the window ends up too small and the
   * content stays invisible.
   */
  @Test
  fun `the content of a swing-in-fx dialog is painted`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      Platform.setImplicitExit(false)
      val swingNode = SwingNode()
      val contentBox = VBox()
      VBox.setVgrow(swingNode, Priority.ALWAYS)
      contentBox.children.addAll(swingNode, Button("OK"))
      val stage = Stage()
      stage.scene = Scene(contentBox)
      stage.show()
      delay(stepMs)

      SwingUtilities.invokeLater {
        swingNode.content = swingPage(java.awt.Color(255, 0, 255))
        Platform.runLater {
          contentBox.layout()
          stage.scene.window.sizeToScene()
        }
      }
      val wholeContent = 240 * 120
      val seen = waitForPixels(stage, wholeContent, ::isMagenta)
      stage.close()
      assertTrue(seen >= wholeContent,
        "the dialog shows only $seen of $wholeContent pixels of its content, so it opens unpainted; " +
          "the window is ${stage.width}x${stage.height} and the embedded node is " +
          "${swingNode.layoutBounds.width}x${swingNode.layoutBounds.height}")
    }
  }
}
