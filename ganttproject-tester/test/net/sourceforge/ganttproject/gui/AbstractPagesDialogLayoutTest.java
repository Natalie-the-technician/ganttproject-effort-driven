package net.sourceforge.ganttproject.gui;

import junit.framework.Assert;
import junit.framework.TestCase;
import net.sourceforge.ganttproject.gui.options.model.OptionPageProvider;
import net.sourceforge.ganttproject.language.GanttLanguage;
import org.easymock.EasyMock;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The settings dialog of {@link AbstractPagesDialog} puts the list of pages into BorderLayout.WEST
 * and every option page into BorderLayout.CENTER of the same panel. BorderLayout hands WEST its
 * full preferred width and gives CENTER only the remainder, even when that remainder is negative.
 *
 * <p>These tests lay the root panel out at a given width and measure how much of the page area is
 * on screen, using the same method as WebDavOptionPageLayoutTest: recursive doLayout(), and widths
 * measured after clipping against every ancestor, because Swing paints children clipped to their
 * parents.
 */
public class AbstractPagesDialogLayoutTest extends TestCase {
  private static final Logger ourLogger = Logger.getLogger(AbstractPagesDialogLayoutTest.class.getName());

  private static final int ROOT_HEIGHT = 600;
  /** Width the stand-in option pages ask for. */
  private static final int PAGE_CONTENT_WIDTH = 400;

  /** Can the page area get a width <= 0 at all, and at which root width does it tip over? */
  public void testPageAreaWidthAcrossRootWidths() throws Exception {
    onEventDispatchThread(() -> {
      Dialog dialog = new Dialog(englishTexts(), PAGE_CONTENT_WIDTH);
      ourLogger.info("english | pages list wants " + dialog.listPreferredWidth() + " px"
          + " | root panel wants " + dialog.rootPreferredWidth() + " px"
          + " | " + dialog.pageCount() + " pages, " + dialog.headerCount() + " group headers"
          + " | names " + dialog.pageNames());
      for (int width : new int[] {100, 150, 200, 250, 300, 400, 500, 700, 847, 1000, 1200}) {
        dialog.layoutAt(width);
        ourLogger.info("PROBE " + dialog.describe(width));
      }
      ourLogger.info("TIPPING POINT english: the page area first reaches width <= 0 at root width "
          + dialog.tippingPointWidth() + " px");
    });
  }

  /**
   * The tipping point is set by the pages list alone: BorderLayout gives CENTER the remainder
   * whatever CENTER asks for. Measured with two very different content widths.
   */
  public void testTippingPointDoesNotDependOnPageContentWidth() throws Exception {
    onEventDispatchThread(() -> {
      int a = new Dialog(englishTexts(), 100).tippingPointWidth();
      int b = new Dialog(englishTexts(), 2000).tippingPointWidth();
      ourLogger.info("tipping point with 100 px page content: " + a
          + " | with 2000 px page content: " + b);
      assertEquals("the tipping point is set by the pages list, not by the page content", a, b);
    });
  }

  /**
   * Does the language move the tipping point? Measured by building the page list twice from the
   * two translation files, in one JVM and without touching the GanttLanguage singleton, so the
   * two numbers cannot contaminate each other.
   */
  public void testTippingPointInGerman() throws Exception {
    onEventDispatchThread(() -> {
      Dialog english = new Dialog(englishTexts(), PAGE_CONTENT_WIDTH);
      Dialog german = new Dialog(germanTexts(), PAGE_CONTENT_WIDTH);
      ourLogger.info("LANGUAGE en: pages list wants " + english.listPreferredWidth()
          + " px, tipping point " + english.tippingPointWidth() + " px | " + english.pageNames());
      ourLogger.info("LANGUAGE de: pages list wants " + german.listPreferredWidth()
          + " px, tipping point " + german.tippingPointWidth() + " px | " + german.pageNames());
      ourLogger.info("LANGUAGE difference: list "
          + (german.listPreferredWidth() - english.listPreferredWidth())
          + " px, tipping point "
          + (german.tippingPointWidth() - english.tippingPointWidth()) + " px");
    });
  }

  /**
   * The page area of the settings dialog as the F3 report describes it: a dialog of 847 px.
   * Reports the margin between that width and the tipping point.
   */
  public void testWidthReportedInTheF3Entry() throws Exception {
    onEventDispatchThread(() -> {
      for (Object[] language : new Object[][] {{"en", englishTexts()}, {"de", germanTexts()}}) {
        Dialog dialog = new Dialog((Properties) language[1], PAGE_CONTENT_WIDTH);
        dialog.layoutAt(847);
        ourLogger.info("F3 WIDTH " + language[0] + ": " + dialog.describe(847)
            + " | tipping point " + dialog.tippingPointWidth()
            + " px, so 847 px is " + (847 - dialog.tippingPointWidth())
            + " px above it");
      }
    });
  }


  /**
   * All option pages sit in one CardLayout inside the single CENTER component. If CENTER ever got
   * a width <= 0, every page would be blank, not some of them. This measures all seven at the
   * width where CENTER is negative.
   */
  public void testAllPagesAreBlankTogetherWhenTheCenterIsNegative() throws Exception {
    onEventDispatchThread(() -> {
      Dialog dialog = new Dialog(englishTexts(), PAGE_CONTENT_WIDTH);
      int belowTippingPoint = dialog.tippingPointWidth();
      dialog.layoutAt(belowTippingPoint);
      ourLogger.info("CARDS at root width " + belowTippingPoint
          + " (page area w=" + dialog.pageAreaWidth() + "): " + dialog.describeCards());
      assertTrue("this test needs a negative page area", dialog.pageAreaWidth() <= 0);
      assertEquals("all seven pages must be measured", 7, dialog.cardCount());
      assertEquals("every page is invisible together, none survives",
          0, dialog.visibleCardCount());

      dialog.layoutAt(847);
      ourLogger.info("CARDS at root width 847 (page area w=" + dialog.pageAreaWidth()
          + "): " + dialog.describeCards());
      assertEquals("at 847 px every page is visible, so none of the seven is blank",
          7, dialog.visibleCardCount());
    });
  }

  /**
   * How much wider would the page names have to be for the tipping point to reach the 847 px the
   * F3 entry reports? Padding the longest name one character at a time until it tips.
   */
  public void testHowMuchLongerTheNamesWouldHaveToBe() throws Exception {
    onEventDispatchThread(() -> {
      for (Object[] language : new Object[][] {{"en", englishTexts()}, {"de", germanTexts()}}) {
        Properties texts = (Properties) language[1];
        int plain = new Dialog(texts, PAGE_CONTENT_WIDTH).tippingPointWidth();
        int padding = 0;
        int tipping = plain;
        while (tipping < 847 && padding < 400) {
          padding++;
          tipping = new Dialog(pad(texts, padding), PAGE_CONTENT_WIDTH).tippingPointWidth();
        }
        ourLogger.info("NAME PADDING " + language[0] + ": tipping point is " + plain
            + " px with the real names; it reaches 847 px only when every page name is "
            + padding + " characters longer (tipping point then " + tipping + " px)");
      }
    });
  }

  /** The texts with every option page label padded by {@code n} extra characters. */
  private static Properties pad(Properties texts, int n) {
    Properties result = new Properties();
    result.putAll(englishTexts());
    result.putAll(texts);
    StringBuilder tail = new StringBuilder();
    for (int i = 0; i < n; i++) {
      tail.append('W');
    }
    for (String key : result.stringPropertyNames()) {
      if (key.startsWith("optionPage.") && key.endsWith(".label")) {
        result.setProperty(key, result.getProperty(key) + tail);
      }
    }
    return result;
  }

  /** The real root panel of the real AbstractPagesDialog, with the real page list. */
  private static class Dialog {
    private final JComponent myRoot;
    private final JList<?> myPagesList;
    private final Component myPageArea;
    private final List<String> myPageNames = new ArrayList<>();
    private int myHeaderCount;

    private final Properties myTexts;

    Dialog(Properties texts, int contentWidth) {
      myTexts = texts;
      UIFacade uiFacade = EasyMock.createNiceMock(UIFacade.class);
      EasyMock.replay(uiFacade);
      List<AbstractPagesDialog.ListItem> items = buildRealListItems(contentWidth);
      Probe probe = new Probe(uiFacade, items);
      myRoot = probe.root();
      myPagesList = findFirst(myRoot, JList.class);
      Assert.assertNotNull("the root panel has no JList", myPagesList);
      myPageArea = otherChildOf(myRoot, myPagesList);
    }

    /**
     * The pages exactly as SettingsDialog2 builds them: the order and the names come from the
     * settings.app.pageOrder resource. The page components are stand-ins of a fixed preferred
     * width; testTippingPointDoesNotDependOnPageContentWidth shows that this does not affect the
     * measured tipping point.
     */
    private List<AbstractPagesDialog.ListItem> buildRealListItems(int contentWidth) {
      List<AbstractPagesDialog.ListItem> items = new ArrayList<>();
      String[] order = text("settings.app.pageOrder").split(",");
      for (String pageId : order) {
        OptionPageProvider provider = EasyMock.createNiceMock(OptionPageProvider.class);
        EasyMock.replay(provider);
        if (pageId.startsWith("pageGroup.")) {
          myHeaderCount++;
          items.add(new AbstractPagesDialog.ListItem(
              true, pageId, GanttLanguage.correctLabel(text(pageId)), null, null));
        } else {
          String name = GanttLanguage.correctLabel(text("optionPage." + pageId + ".label"));
          myPageNames.add(name);
          items.add(new AbstractPagesDialog.ListItem(
              false, pageId, name, fixedWidthPage(contentWidth), provider));
        }
      }
      return items;
    }

    /** The translated text, falling back to the English one exactly as the resource bundles do. */
    private String text(String key) {
      String value = myTexts.getProperty(key);
      if (value == null) {
        value = englishTexts().getProperty(key);
      }
      Assert.assertNotNull("no text for key " + key, value);
      return value;
    }

    private static Container fixedWidthPage(final int contentWidth) {
      JPanel panel = new JPanel();
      panel.setPreferredSize(new Dimension(contentWidth, 300));
      panel.setMinimumSize(new Dimension(contentWidth, 300));
      return panel;
    }

    int pageCount() {
      return myPageNames.size();
    }

    int headerCount() {
      return myHeaderCount;
    }

    List<String> pageNames() {
      return myPageNames;
    }

    int listPreferredWidth() {
      return myPagesList.getPreferredSize().width;
    }

    int rootPreferredWidth() {
      return myRoot.getPreferredSize().width;
    }

    void layoutAt(int width) {
      myRoot.setSize(width, ROOT_HEIGHT);
      layoutTree(myRoot);
    }

    int listWidth() {
      return myPagesList.getWidth();
    }

    int pageAreaWidth() {
      return myPageArea.getWidth();
    }

    int visiblePageAreaWidth() {
      return visibleWidth(myPageArea, myRoot);
    }

    /** The smallest root width at which the page area still has a width > 0, minus one. */
    int tippingPointWidth() {
      for (int width = 2000; width >= 0; width--) {
        layoutAt(width);
        if (pageAreaWidth() <= 0) {
          return width;
        }
      }
      return -1;
    }

    /** The option pages, which all live in one CardLayout below the page area. */
    private List<Component> cards() {
      Container cardHolder = (Container) myPageArea;
      while (!(cardHolder.getLayout() instanceof java.awt.CardLayout)) {
        Assert.assertEquals("expected a single child on the way to the CardLayout",
            1, cardHolder.getComponentCount());
        cardHolder = (Container) cardHolder.getComponent(0);
      }
      List<Component> result = new ArrayList<>();
      for (Component card : cardHolder.getComponents()) {
        result.add(card);
      }
      return result;
    }

    int cardCount() {
      return cards().size();
    }

    int visibleCardCount() {
      int count = 0;
      for (Component card : cards()) {
        if (visibleWidth(card, myRoot) > 0) {
          count++;
        }
      }
      return count;
    }

    String describeCards() {
      StringBuilder result = new StringBuilder();
      for (Component card : cards()) {
        result.append(String.format("[w=%d visible=%d]", card.getWidth(), visibleWidth(card, myRoot)));
      }
      return result.toString();
    }

    String describe(int rootWidth) {
      return String.format(
          "root width=%4d | pages list x=%d w=%4d | page area x=%4d w=%5d visible=%4d",
          rootWidth, xIn(myPagesList, myRoot), listWidth(),
          xIn(myPageArea, myRoot), pageAreaWidth(), visiblePageAreaWidth());
    }

    private static Component otherChildOf(Container parent, Component notThisOne) {
      for (Component child : parent.getComponents()) {
        if (!isAncestorOf(child, notThisOne)) {
          return child;
        }
      }
      throw new AssertionError("the root panel has no child besides the pages list");
    }
  }

  /** Reaches the private getComponent() of the real AbstractPagesDialog. */
  private static class Probe extends AbstractPagesDialog {
    Probe(UIFacade uiFacade, List<ListItem> items) {
      super("settings.app", uiFacade, items);
    }

    @Override
    protected void onOk() {
    }

    JComponent root() {
      try {
        java.lang.reflect.Method method = AbstractPagesDialog.class.getDeclaredMethod("getComponent");
        method.setAccessible(true);
        return (JComponent) method.invoke(this);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  private static void onEventDispatchThread(Runnable body) throws Exception {
    final Throwable[] thrown = new Throwable[1];
    SwingUtilities.invokeAndWait(() -> {
      try {
        body.run();
      } catch (Throwable e) {
        thrown[0] = e;
      }
    });
    if (thrown[0] instanceof Error) {
      throw (Error) thrown[0];
    }
    if (thrown[0] != null) {
      throw new AssertionError(thrown[0]);
    }
  }

  /** Lays out the whole subtree; the panel is not in a window, so validate() does nothing here. */
  private static void layoutTree(Component component) {
    if (component instanceof Container) {
      Container container = (Container) component;
      container.doLayout();
      for (Component child : container.getComponents()) {
        layoutTree(child);
      }
    }
  }

  /** Width of {@code component} after clipping against every ancestor up to {@code root}. */
  private static int visibleWidth(Component component, Component root) {
    Rectangle clip = new Rectangle(xIn(component, root), 0, component.getWidth(), 1);
    for (Component c = component.getParent(); c != null; c = c.getParent()) {
      clip = clip.intersection(new Rectangle(xIn(c, root), 0, c.getWidth(), 1));
      if (c == root) {
        break;
      }
    }
    return Math.max(0, clip.width);
  }

  /** x of {@code component} in the coordinates of {@code ancestor}. */
  private static int xIn(Component component, Component ancestor) {
    int x = 0;
    for (Component c = component; c != null && c != ancestor; c = c.getParent()) {
      x += c.getX();
    }
    return x;
  }

  private static boolean isAncestorOf(Component ancestor, Component descendant) {
    for (Component c = descendant; c != null; c = c.getParent()) {
      if (c == ancestor) {
        return true;
      }
    }
    return false;
  }

  private static <T> T findFirst(Component component, Class<T> type) {
    if (type.isInstance(component)) {
      return type.cast(component);
    }
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        T found = findFirst(child, type);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static Properties ourEnglishTexts;
  private static Properties ourGermanTexts;

  private static synchronized Properties englishTexts() {
    if (ourEnglishTexts == null) {
      ourEnglishTexts = loadTranslation("i18n.properties");
    }
    return ourEnglishTexts;
  }

  private static synchronized Properties germanTexts() {
    if (ourGermanTexts == null) {
      ourGermanTexts = loadTranslation("i18n_de_DE.properties");
    }
    return ourGermanTexts;
  }

  /**
   * The translation file, read straight from the source tree. The GanttLanguage singleton has no
   * resource bundle in the test class path, and reading the files keeps the two languages from
   * contaminating each other through the singleton.
   */
  private static Properties loadTranslation(String fileName) {
    java.io.File file = null;
    for (java.io.File dir = new java.io.File("").getAbsoluteFile();
         dir != null; dir = dir.getParentFile()) {
      java.io.File candidate =
          new java.io.File(dir, "biz.ganttproject.app.localization/translations/" + fileName);
      if (candidate.isFile()) {
        file = candidate;
        break;
      }
    }
    Assert.assertNotNull("cannot find " + fileName + " above "
        + new java.io.File("").getAbsolutePath(), file);
    Properties properties = new Properties();
    try (java.io.InputStream in = new java.io.FileInputStream(file)) {
      properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
    ourLogger.info("read " + properties.size() + " texts from " + file);
    return properties;
  }
}
