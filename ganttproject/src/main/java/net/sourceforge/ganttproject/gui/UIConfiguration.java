/*
GanttProject is an opensource project management tool.
Copyright (C) 2004-2011 GanttProject Team

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.sourceforge.ganttproject.gui;

import biz.ganttproject.core.chart.render.AlphaRenderingOption;
import biz.ganttproject.core.option.*;
import biz.ganttproject.lib.fx.TreeTableCellsKt;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import net.sourceforge.ganttproject.fork.ChartComparison;
import net.sourceforge.ganttproject.fork.ForkI18nKt;
import net.sourceforge.ganttproject.gui.options.model.GP1XOptionConverter;

import java.awt.*;

/**
 * @author bard
 */
public class UIConfiguration {
//  private final Font myChartMainFont;

  /** default resource color */
  private Color myResColor;

  /** overload resource color */
  private Color myResOverColor;

  /** underload resource color */
  private Color myResUnderColor;

  private Color myEarlierPreviousTaskColor;

  private Color myLaterPreviousTaskColor;

  private Color myPreviousTaskColor;

  /** Color used for weekend indications */
  private Color myWeekEndColor;

  /** Color used for days off (and holidays) */
  private Color myDayOffColor;

  /**
   * [fork change] B4 -- colour of the home-working band in the resource chart.
   *
   * A FIELD OF ITS OWN BESIDE {@link #myDayOffColor} AND NOT DERIVED FROM IT. Whoever later wants
   * the two to be told apart has to be able to move one without the other; a shade computed from
   * the day-off colour would follow it wherever the user drags that one, and the day the user
   * picks a violet for their holidays the home office would become invisible.
   *
   * The default is measured rather than picked by eye, against the four colours a resource-chart
   * row can already show. Greyscale brightness (0.299 R + 0.587 G + 0.114 B), as painted:
   *
   *     ordinary working day  #FFFFFF  255   (the chart background)
   *     weekend column        #EEEEEE  238   (chart.properties, calendar.weekend)
   *     public holiday column #EEDDEE  228   (chart.properties, calendar.holiday)
   *     holiday band          #F5FFAC  243   (this colour at alpha 100 over white)
   *     load bar              #8CB6CE  172
   *     HOME-WORKING BAND     #947DCA  141   (the colour below at alpha 170 over white)
   *
   * 141 is 102 brightness steps from the holiday band and 114 from an empty working day, so the
   * three are told apart on a black-and-white printout and by somebody who does not see the hue at
   * all. That was the point of measuring: everything the chart shows today sits between 172 and
   * 255, and a further light tint would have been a fifth pale shade among four.
   */
  private Color myHomeWorkColor;

  private boolean isRedlineOn;

  private boolean isCriticalPathOn;

  private final AlphaRenderingOption myWeekendAlphaRenderingOption;
  private final RedlineOption myRedlineOption = new RedlineOption();

  /**
   * [Fork change] Which comparison the chart shows when the program starts. See
   * {@link ChartComparison}. It sits here and in the `ganttChartGridDetails` group for the same
   * reason {@link RedlineOption} does: that is the path along which `GanttOptions` writes an
   * option into `~/.ganttproject` and reads it back -- the key becomes
   * `ganttChartGridDetails.comparisonAtStartup`.
   *
   * It is a STARTUP setting, not a mirror of the toolbar. Switching the view in the toolbar does
   * not write here; this is what the program comes up with, and the toolbar is where one departs
   * from it for the session.
   */
  private final DefaultEnumerationOption<ChartComparison> myComparisonAtStartupOption =
      new DefaultEnumerationOption<>("comparisonAtStartup", ChartComparison.values());
  {
    myComparisonAtStartupOption.setValueLocalizer(
        value -> ForkI18nKt.forkText("fork.comparison." + value.toLowerCase(java.util.Locale.ROOT)));
    myComparisonAtStartupOption.setSelectedValue(ChartComparison.DATES);
  }
  private BooleanOption myProjectDatesOption = new DefaultBooleanOption("showProjectDates");
  private final BooleanOption myTimelineMilestonesOption = new DefaultBooleanOption("timeline.showMilestones", true);

  private FontOption myChartFontOption;
  private Supplier<Integer> myAppFontSize;
  private IntegerOption myDpiOption;

  public UIConfiguration(Color taskColor, boolean isRedlineOn) {
//    myChartMainFont = chartMainFont == null ? Fonts.DEFAULT_CHART_FONT : chartMainFont;
    this.isRedlineOn = isRedlineOn;
    myResColor = new Color(140, 182, 206);
    myResOverColor = new Color(229, 50, 50);
    myResUnderColor = new Color(50, 229, 50);
    myEarlierPreviousTaskColor = new Color(50, 229, 50);
    myLaterPreviousTaskColor = new Color(229, 50, 50);
    myPreviousTaskColor = Color.LIGHT_GRAY;
    myWeekEndColor = Color.GRAY;
    myDayOffColor = new Color(0.9f, 1f, 0.17f);
    // [fork change] B4. See the field comment for how this value was arrived at.
    myHomeWorkColor = new Color(95, 60, 175);
    myWeekendAlphaRenderingOption = new AlphaRenderingOption();
    myAppFontSize = new Supplier<Integer>() {
      @Override
      public Integer get() {
        //Font tableFont = (Font) UIManager.get("Table.font");
        //return tableFont.getSize() + 8;
        return (int)TreeTableCellsKt.getMinCellHeight().get();
      }
    };
  }

//  public Font getChartMainFont() {
//    return myChartMainFont;
//  }
//
  public Color getResourceColor() {
    return myResColor;
  }

  public void setResourceColor(Color myResColor) {
    this.myResColor = myResColor;
  }

  public Color getResourceOverloadColor() {
    return myResOverColor;
  }

  public void setResourceOverloadColor(Color myResOverColor) {
    this.myResOverColor = myResOverColor;
  }

  public Color getResourceUnderloadColor() {
    return myResUnderColor;
  }

  public void setResourceUnderloadColor(Color myResUnderColor) {
    this.myResUnderColor = myResUnderColor;
  }

  public Color getEarlierPreviousTaskColor() {
    return myEarlierPreviousTaskColor;
  }

  public void setEarlierPreviousTaskColor(Color earlierTaskColor) {
    this.myEarlierPreviousTaskColor = earlierTaskColor;
  }

  public Color getLaterPreviousTaskColor() {
    return myLaterPreviousTaskColor;
  }

  public void setLaterPreviousTaskColor(Color laterTaskColor) {
    this.myLaterPreviousTaskColor = laterTaskColor;
  }

  public Color getPreviousTaskColor() {
    return myPreviousTaskColor;
  }

  public void setPreviousTaskColor(Color previousTaskColor) {
    this.myPreviousTaskColor = previousTaskColor;
  }

  public Color getWeekEndColor() {
    return myWeekEndColor;
  }

  public Color getDayOffColor() {
    return myDayOffColor;
  }

  public void setWeekEndColor(Color myWeekEndColor) {
    this.myWeekEndColor = myWeekEndColor;
  }

  public void setDayOffColor(Color dayOffColor) {
    this.myDayOffColor = dayOffColor;
  }

  /** [fork change] B4 -- the colour of the home-working band. */
  public Color getHomeWorkColor() {
    return myHomeWorkColor;
  }

  /** [fork change] B4 -- the colour of the home-working band. */
  public void setHomeWorkColor(Color homeWorkColor) {
    this.myHomeWorkColor = homeWorkColor;
  }

  public boolean isRedlineOn() {
    return isRedlineOn;
  }

  public void setRedlineOn(boolean redlineOn) {
    isRedlineOn = redlineOn;
  }

  public boolean isCriticalPathOn() {
    return isCriticalPathOn;
  }

  public void setCriticalPathOn(boolean isOn) {
    this.isCriticalPathOn = isOn;
  }

  public AlphaRenderingOption getWeekendAlphaRenderingOption() {
    return myWeekendAlphaRenderingOption;
  }


  class RedlineOption extends DefaultBooleanOption implements GP1XOptionConverter {
    RedlineOption() {
      super("showTodayLine");
    }

    @Override
    public String getTagName() {
      return "redline";
    }

    @Override
    public String getAttributeName() {
      return "value";
    }

    @Override
    public void loadValue(String legacyValue) {
      lock();
      loadPersistentValue(legacyValue);
      commit();
    }

    @Override
    public void commit() {
      super.commit();
      setRedlineOn(isChecked());
    }
  };

  /** [Fork change] Which comparison the chart shows at startup. */
  public DefaultEnumerationOption<ChartComparison> getComparisonAtStartupOption() {
    return myComparisonAtStartupOption;
  }

  public BooleanOption getRedlineOption() {
    return myRedlineOption;
  }

  public BooleanOption getProjectBoundariesOption() {
    return myProjectDatesOption;
  }

  public BooleanOption getTimelineMilestonesOption() { return myTimelineMilestonesOption; }
  public void setChartFontOption(FontOption chartFontOption) {
    myChartFontOption = chartFontOption;
  }

  public FontOption getChartFontOption() {
    return myChartFontOption;
  }

  public Supplier<Integer> getAppFontSize() {
    return myAppFontSize;
  }

  public IntegerOption getDpiOption() {
    return myDpiOption;
  }

  public void setDpiOption(IntegerOption dpiOption) {
    myDpiOption = Preconditions.checkNotNull(dpiOption);
  }
}
