/*
Copyright 2003-2012 Dmitry Barashev, GanttProject Team

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
package net.sourceforge.ganttproject.chart;

import biz.ganttproject.core.chart.canvas.Canvas;
import biz.ganttproject.core.chart.canvas.Canvas.Line;
import biz.ganttproject.core.chart.canvas.Canvas.Rectangle;
import biz.ganttproject.core.chart.canvas.Canvas.Text;
import biz.ganttproject.core.chart.canvas.Canvas.TextGroup;
import biz.ganttproject.core.chart.canvas.Painter;
import biz.ganttproject.core.chart.render.*;
import net.sourceforge.ganttproject.util.PropertiesUtil;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Implements styled painters for the available primitives (see
 * {@link Canvas})
 *
 * @author bard
 */
public class StyledPainterImpl implements Painter {
  private Graphics2D myGraphics;

  private final Map<String, RectanglePainter> myStyle2painter = new HashMap<>();

  private final ChartUIConfiguration myConfig;

  private final int margin;

  /** List X coordinates used to draw polygons */
  private final int[] myXPoints = new int[4];

  /** List Y coordinates used to draw polygons */
  private final int[] myYPoints = new int[4];

  private final Properties myProperties;

  private final TextPainter myTextPainter;

  private final LineRenderer myLineRenderer;

  private final RectangleRenderer myRectangleRenderer;

  private final SummaryTaskRenderer mySummaryTaskRenderer;

  private final PolygonRenderer myPolygonRenderer;

  /** Default stroke used for the primitives */
  private final static BasicStroke defaultStroke = new BasicStroke();

  public StyledPainterImpl(final ChartUIConfiguration config) {
    myConfig = config;
    margin = myConfig.getMargin();

    myStyle2painter.put("task.progress", new ColouredRectanglePainter(Color.BLACK));
    myStyle2painter.put("task.progress.end", new ColouredRectanglePainter(Color.BLACK));
    RectanglePainter containerRectanglePainter = new RectanglePainter() {
      @Override
      public void paint(Rectangle next) {
        mySummaryTaskRenderer.render(next);
      }
    };
    myStyle2painter.put("task.projectTask", containerRectanglePainter);
    myStyle2painter.put("task.supertask", containerRectanglePainter);
    //      ResourceLoadRenderer.ResourceLoad load = (ResourceLoadRenderer.ResourceLoad) next.getModelObject();
    //      int loadInt = Math.round(load.getLoad());
    //      String loadStr = loadInt + "%";
    //      int emsLength = myTextLengthCalculator.getTextLength(loadStr);
    //      boolean displayLoad = (loadInt != 100 && emsLength <= next.getWidth());
    //      if (displayLoad) {
    //        myGraphics.drawString(loadStr, next.getMiddleX() - myTextLengthCalculator.getTextLength(loadStr) / 2,
    //            next.getTopY() + margin + next.getHeight() / 2);
    //        myGraphics.drawLine(next.getLeftX(), next.getTopY() + margin, next.getLeftX(), next.getBottomY() - margin);
    //      }
    RectanglePainter myResourceLoadPainter = next -> {
      String style = next.getStyle();
      Color c;
      if (style.indexOf("overload") > 0) {
        c = myConfig.getResourceOverloadColor();
      } else if (style.indexOf("underload") > 0) {
        c = myConfig.getResourceUnderLoadColor();
      } else {
        c = myConfig.getResourceNormalLoadColor();
      }
      myGraphics.setColor(c);

      myGraphics.fillRect(next.getLeftX(), next.getTopY() + margin, next.getWidth(), next.getHeight() - 2 * margin);
      if (style.indexOf(".first") > 0) {
        myGraphics.setColor(Color.BLACK);
        myGraphics.drawLine(next.getLeftX(), next.getTopY() + margin, next.getLeftX(), next.getBottomY() - margin);
      }
      if (style.indexOf(".last") > 0) {
        myGraphics.setColor(Color.BLACK);
        myGraphics.drawLine(next.getRightX(), next.getTopY() + margin, next.getRightX(), next.getBottomY() - margin);
      }
      myGraphics.setColor(Color.BLACK);

//      ResourceLoadRenderer.ResourceLoad load = (ResourceLoadRenderer.ResourceLoad) next.getModelObject();
//      int loadInt = Math.round(load.getLoad());
//      String loadStr = loadInt + "%";
//      int emsLength = myTextLengthCalculator.getTextLength(loadStr);
//      boolean displayLoad = (loadInt != 100 && emsLength <= next.getWidth());
//      if (displayLoad) {
//        myGraphics.drawString(loadStr, next.getMiddleX() - myTextLengthCalculator.getTextLength(loadStr) / 2,
//            next.getTopY() + margin + next.getHeight() / 2);
//        myGraphics.drawLine(next.getLeftX(), next.getTopY() + margin, next.getLeftX(), next.getBottomY() - margin);
//      }
      myGraphics.setColor(Color.BLACK);
      myGraphics.drawLine(next.getLeftX(), next.getTopY() + margin, next.getRightX(), next.getTopY() + margin);
      myGraphics.drawLine(next.getLeftX(), next.getBottomY() - margin, next.getRightX(), next.getBottomY() - margin);
    };
    myStyle2painter.put("load.normal", myResourceLoadPainter);
    myStyle2painter.put("load.normal.first", myResourceLoadPainter);
    myStyle2painter.put("load.normal.last", myResourceLoadPainter);
    myStyle2painter.put("load.normal.first.last", myResourceLoadPainter);
    myStyle2painter.put("load.overload", myResourceLoadPainter);
    RectanglePainter myArrowDownPainter = next -> {
      myXPoints[0] = next.getLeftX();
      myXPoints[1] = next.getRightX();
      myXPoints[2] = next.getMiddleX();
      myYPoints[0] = next.getTopY();
      myYPoints[1] = next.getTopY();
      myYPoints[2] = next.getBottomY();
      myGraphics.setColor(Color.BLACK);
      myGraphics.fillPolygon(myXPoints, myYPoints, 3);
    };
    myStyle2painter.put("dependency.arrow.down", myArrowDownPainter);
    myStyle2painter.put("load.overload.first", myResourceLoadPainter);
    myStyle2painter.put("load.overload.last", myResourceLoadPainter);
    myStyle2painter.put("load.overload.first.last", myResourceLoadPainter);
    RectanglePainter myArrowUpPainter = next -> {
      myXPoints[0] = next.getLeftX();
      myXPoints[1] = next.getRightX();
      myXPoints[2] = next.getMiddleX();
      myYPoints[0] = next.getBottomY();
      myYPoints[1] = next.getBottomY();
      myYPoints[2] = next.getTopY();
      myGraphics.setColor(Color.BLACK);
      myGraphics.fillPolygon(myXPoints, myYPoints, 3);
    };
    myStyle2painter.put("dependency.arrow.up", myArrowUpPainter);
    RectanglePainter myArrowLeftPainter = next -> {
      Graphics g = myGraphics;
      g.setColor(Color.BLACK);
      myXPoints[0] = next.getLeftX();
      myXPoints[1] = next.getRightX();
      myXPoints[2] = next.getRightX();
      myYPoints[0] = next.getMiddleY();
      myYPoints[1] = next.getTopY();
      myYPoints[2] = next.getBottomY();
      g.fillPolygon(myXPoints, myYPoints, 3);
    };
    myStyle2painter.put("dependency.arrow.left", myArrowLeftPainter);
    RectanglePainter myArrowRightPainter = next -> {
      myXPoints[0] = next.getLeftX();
      myXPoints[1] = next.getRightX();
      myXPoints[2] = next.getLeftX();
      myYPoints[0] = next.getTopY();
      myYPoints[1] = next.getMiddleY();
      myYPoints[2] = next.getBottomY();
      myGraphics.setColor(Color.BLACK);
      myGraphics.fillPolygon(myXPoints, myYPoints, 3);
    };
    myStyle2painter.put("dependency.arrow.right", myArrowRightPainter);
    RectanglePainter myDayOffPainter = next -> {
      int margin = StyledPainterImpl.this.margin - 3;
      Color c = myConfig.getDayOffColor();
      myGraphics.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 100));
      myGraphics.fillRect(next.getLeftX(), next.getTopY() + margin, next.getWidth(), next.getHeight() - 2 * margin);
      myGraphics.setColor(Color.BLACK);
      myGraphics.drawLine(next.getLeftX(), next.getTopY() + margin, next.getLeftX(), next.getBottomY() - margin);
      myGraphics.drawLine(next.getLeftX(), next.getTopY() + margin, next.getRightX(), next.getTopY() + margin);
      myGraphics.drawLine(next.getLeftX(), next.getBottomY() - margin, next.getRightX(), next.getBottomY() - margin);
      myGraphics.drawLine(next.getRightX(), next.getTopY() + margin, next.getRightX(), next.getBottomY() - margin);
    };
    myStyle2painter.put("dayoff", myDayOffPainter);
    myStyle2painter.put("load.underload", myResourceLoadPainter);
    myStyle2painter.put("load.underload.first", myResourceLoadPainter);
    myStyle2painter.put("load.underload.last", myResourceLoadPainter);
    myStyle2painter.put("load.underload.first.last", myResourceLoadPainter);
    RectanglePainter myPreviousStateTaskRectanglePainter = new RectanglePainter() {
      private final int[] myXPoints = new int[4];
      private final int[] myYPoints = new int[4];

      @Override
      public void paint(Rectangle next) {
        Graphics g = myGraphics;
        applyComparisonBandPaint(next);

        if (next.hasStyle("milestone")) {
          int middleX = (next.getWidth() <= next.getHeight()) ? next.getRightX() - next.getWidth() / 2 : next.getLeftX()
            + next.getHeight() / 2;
          int middleY = next.getMiddleY();

          myXPoints[0] = next.getLeftX() + 2;
          myYPoints[0] = middleY;
          myXPoints[1] = middleX + 3;
          myYPoints[1] = next.getTopY() - 1;
          myXPoints[2] = (next.getWidth() <= next.getHeight()) ? next.getRightX() + 4 : next.getLeftX() + next.getHeight() + 4;
          myYPoints[2] = middleY;
          myXPoints[3] = middleX + 3;
          myYPoints[3] = next.getBottomY() + 1;

          g.fillPolygon(myXPoints, myYPoints, 4);
        } else if (next.hasStyle("super")) {
          g.fillRect(next.getLeftX(), next.getTopY() + next.getHeight() - 6, next.getWidth(), 3);
          int topy = next.getTopY() + next.getHeight() - 3;
          int rightx = next.getLeftX() + next.getWidth();
          g.fillPolygon(new int[]{rightx - 3, rightx, rightx}, new int[]{topy, topy, topy + 3}, 3);
        } else {
          // [Fork change] In the combined view a band occupies only one of two tracks -- the
          // date half on top, the duration half below. Which one follows from the style the
          // scene builder set; see GanttChartSceneBuilder.renderBothBands.
          int topY = next.getTopY();
          int height = next.getHeight();
          if (next.hasStyle("band.upper")) {
            height = height / 2;
          } else if (next.hasStyle("band.lower")) {
            int upperHeight = height / 2;
            topY = topY + upperHeight;
            height = height - upperHeight;
          }
          int bottomY = topY + height;
          g.fillRect(next.getLeftX(), topY, next.getWidth(), height);
          g.setColor(Color.black);
          g.drawLine(next.getLeftX(), topY, next.getRightX(), topY);
          g.drawLine(next.getLeftX(), bottomY, next.getRightX(), bottomY);
          if (next.hasStyle("start")) {
            g.drawLine(next.getLeftX(), topY, next.getLeftX(), bottomY);
          }
          if (next.hasStyle("end")) {
            g.drawLine(next.getRightX(), topY, next.getRightX(), bottomY);
          }
        }
      }
    };
    myStyle2painter.put("previousStateTask", myPreviousStateTaskRectanglePainter);

    myProperties = new Properties();
    PropertiesUtil.loadProperties(myProperties, "/resources/chart.properties");
    config.getChartStylesOption().addChangeValueListener(event -> {
      for (Entry<String, String> entry : config.getChartStylesOption().getValues()) {
        myProperties.put(entry.getKey(), entry.getValue());
      }
    });
    myTextPainter = new TextPainter(myProperties, config::getChartFont);
    myLineRenderer = new LineRenderer(myProperties);
    myRectangleRenderer = new RectangleRenderer(myProperties);
    mySummaryTaskRenderer = new SummaryTaskRenderer(myProperties);
    myPolygonRenderer = new PolygonRenderer(myProperties);
  }

  public void setGraphics(Graphics g) {
    myGraphics = (Graphics2D) g;
    myTextPainter.setGraphics(myGraphics);
    myLineRenderer.setGraphics(myGraphics);
    myRectangleRenderer.setGraphics(myGraphics);
    mySummaryTaskRenderer.setGraphics(myGraphics);
    myPolygonRenderer.setGraphics(myGraphics);
    myGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
  }

  @Override
  public void prePaint() {
    myGraphics.setStroke(defaultStroke);
    myGraphics.setFont(myConfig.getChartFont());
  }

  /**
   * The colour of the comparison band underneath a task bar.
   *
   * [Fork change] Pulled out of the rectangle painter because it is now needed in two places: for
   * the band of an ordinary task and for the rhombus of a milestone. The rhombus takes a
   * different route through the painter, see {@link #paint(Canvas.Rhombus)}.
   */
  /**
   * [Fork change] The ground the date comparison is hatched onto. It marks the DATE AXIS, not a
   * result: wherever this ground appears, the band is answering "am I on schedule" -- in the
   * dates view and in the upper half of the combined one. The duration and effort bands stay
   * plain, because a shift plays no part in what they say.
   *
   * WHY A HATCH AND WHY THIS ONE, measured on 26 August 2026:
   *
   *   A PATTERN WITH COVERAGE d STRETCHES EVERY COLOUR SEPARATION TO THE FACTOR d -- no matter
   *   which ground lies underneath. The area mean of a tiled texture is
   *       d x comparisonColour + (1 - d) x ground,
   *   so the ground cancels out of the difference between two cases.
   *
   *   THICK_BACKSLASH covers 8 of 16 cells, so exactly half: the distances between red, green
   *   and grey fall from 253 / 204 / 204 units to 126 / 101 / 102. That price is known and
   *   accepted.
   *
   *   CHANGING THE LILAC CHANGES NOTHING about that -- measured with a light and a medium lilac,
   *   the same numbers came out both times. Whoever wants more separation has to raise the
   *   COVERAGE, not swap the ground. THICK_GRID with 12 of 16 would give 0.75 instead of 0.5.
   *
   * The weakest case is grey on lilac: 47 units from the unhatched ground, against 99 for red
   * and 114 for green. With a lighter lilac it would drop to 26 and become invisible.
   */
  private static final Color COMPARISON_DATES_GROUND = new Color(147, 112, 219);

  /**
   * [Fork change] Sets the fill for a comparison band: hatched over the lilac ground when the
   * date axis is speaking, a plain colour otherwise. See {@link #COMPARISON_DATES_GROUND}.
   */
  private void applyComparisonBandPaint(Canvas.Rectangle next) {
    Color colour = getComparisonBandColor(next);
    if (next.hasStyle("axis.dates")) {
      myGraphics.setPaint(new ShapePaint(ShapeConstants.THICK_BACKSLASH, colour, COMPARISON_DATES_GROUND));
    } else {
      myGraphics.setColor(colour);
    }
  }

  private Color getComparisonBandColor(Canvas.Shape shape) {
    if (shape.hasStyle("earlier")) {
      return myConfig.getEarlierPreviousTaskColor();
    }
    if (shape.hasStyle("later")) {
      return myConfig.getLaterPreviousTaskColor();
    }
    return myConfig.getPreviousTaskColor();
  }

  @Override
  public void paint(Rectangle next) {
    assert myGraphics != null;
    if (myRectangleRenderer.render(next)) {
      return;
    }
    RectanglePainter painter = myStyle2painter.get(next.getStyle());
    if (painter != null) {
      // Use found painter
      painter.paint(next);
    } else {
      // Use default painter, since no painter was provided
      if (next.getBackgroundColor() == null) {
        Color foreColor = next.getForegroundColor();
        if (foreColor == null) {
          foreColor = Color.BLACK;
        }
        myGraphics.setColor(foreColor);
        myGraphics.drawRect(next.getLeftX(), next.getTopY(), next.getWidth(), next.getHeight());
      } else {
        myGraphics.setColor(next.getBackgroundColor());
        myGraphics.fillRect(next.getLeftX(), next.getTopY(), next.getWidth(), next.getHeight());
      }
    }
  }

  /**
   * Interface providing a method to paint a rectangle (currently, used to draw
   * many more other things...)
   */
  private interface RectanglePainter {
    void paint(Rectangle next);
  }


  private class ColouredRectanglePainter implements RectanglePainter {
    private final Color myColor;

    private ColouredRectanglePainter(Color color) {
      myColor = color;
    }

    @Override
    public void paint(Rectangle next) {
      myGraphics.setColor(myColor);
      myGraphics.fillRect(next.getLeftX(), next.getTopY(), next.getWidth(), next.getHeight());
    }
  }

  @Override
  public void paint(Line line) {
    myLineRenderer.renderLine(line);
  }

  @Override
  public void paint(Text text) {
    myTextPainter.paint(text);
  }

  @Override
  public void paint(TextGroup textGroup) {
    myTextPainter.paint(textGroup);
  }

  @Override
  public void paint(Canvas.Rhombus rhombus) {
    // [Fork change] ---- begin ----
    //
    // A MILESTONE IS A RHOMBUS, NOT A RECTANGLE. TaskActivitySceneBuilder creates a
    // Canvas.Rhombus for it, and that used to land at the PolygonRenderer unchecked -- which
    // never consults myStyle2painter. A milestone's comparison band was therefore painted in the
    // TASK'S OWN COLOUR instead of one of the three comparison colours, and the milestone branch
    // in the band painter above was dead code: it can never receive a rectangle.
    //
    // Measured on a real plan on 20 August 2026: the rhombus of a shifted milestone came out as
    // srgb(255,51,51) -- that is the task colour, none of the comparison colours
    // (192,192,192 / 229,50,50 / 50,229,50). 27 rhombi were affected, 20 of which looked red
    // without a single one being a red of the comparison.
    //
    // This is a defect OF THE ORIGINAL, not of this fork: e523bedc6 carries the same rhombus
    // creation and the same route through the painter.
    //
    // [Fork change] ---- end ----
    // CAREFUL, there was a failed attempt here already: `setStyle` and `addStyle` fill TWO
    // DIFFERENT fields in Canvas.Shape. `hasStyle` only sees what `addStyle` put there; the main
    // style from `setStyle` lives in `getStyle()`. A query via hasStyle("previousStateTask") is
    // therefore always wrong.
    if ("previousStateTask".equals(rhombus.getStyle())) {
      Graphics g = myGraphics;
      g.setColor(getComparisonBandColor(rhombus));
      g.fillPolygon(rhombus.getPointsX(), rhombus.getPointsY(), rhombus.getPointCount());
      return;
    }
    myPolygonRenderer.render(rhombus);
  }
}
