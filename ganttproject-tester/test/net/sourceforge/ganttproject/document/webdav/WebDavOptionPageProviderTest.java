package net.sourceforge.ganttproject.document.webdav;

import biz.ganttproject.core.option.GPOptionGroup;
import junit.framework.TestCase;
import net.sourceforge.ganttproject.IGanttProject;
import net.sourceforge.ganttproject.document.DocumentManager;
import net.sourceforge.ganttproject.gui.UIFacade;
import org.easymock.EasyMock;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that what the user types on the WebDAV settings page is still there after the
 * settings dialog applies the page, that is, after {@code commit()}.
 */
public class WebDavOptionPageProviderTest extends TestCase {
  private static final String URL_BEFORE = "http://before.example.com/dav";
  private static final String URL_TYPED = "http://after.example.com/dav";

  /** What the server descriptor held right after typing and before commit(). */
  private String myUrlBeforeCommit;

  private IGanttProject myProject;
  private UIFacade myUiFacade;
  private WebDavStorageImpl myStorage;

  @Override
  protected void setUp() {
    myProject = EasyMock.createNiceMock(IGanttProject.class);
    myUiFacade = EasyMock.createNiceMock(UIFacade.class);
    DocumentManager documentManager = EasyMock.createNiceMock(DocumentManager.class);
    EasyMock.replay(myProject, myUiFacade, documentManager);

    myStorage = new WebDavStorageImpl(myProject, myUiFacade);

    EasyMock.reset(myProject, documentManager);
    EasyMock.expect(myProject.getDocumentManager()).andReturn(documentManager).anyTimes();
    EasyMock.expect(documentManager.getWebDavStorageUi()).andReturn(myStorage).anyTimes();
    EasyMock.replay(myProject, documentManager);
  }

  public void testTypedServerUrlSurvivesCommit() throws Exception {
    final WebDavServerDescriptor server = new WebDavServerDescriptor();
    server.setName("test server");
    server.setRootUrl(URL_BEFORE);
    myStorage.getServersOption().addValue(server);
    myStorage.getServersOption().setValueIndex(0);

    final WebDavOptionPageProvider provider = new WebDavOptionPageProvider();
    provider.init(myProject, myUiFacade);

    final String[] failure = new String[1];
    SwingUtilities.invokeAndWait(() -> {
      JComponent page = (JComponent) provider.buildPageComponent();
      JTextField urlField = findTextFieldWithText(page, URL_BEFORE);
      if (urlField == null) {
        failure[0] = "the URL text field was not found on the built page";
        return;
      }
      urlField.setText(URL_TYPED);
      myUrlBeforeCommit = server.getRootUrl();
      provider.commit();
    });
    if (failure[0] != null) {
      fail(failure[0]);
    }
    System.out.println("PROBE url after typing, before commit() = " + myUrlBeforeCommit);
    System.out.println("PROBE url after commit()               = " + server.getRootUrl());
    assertEquals("the typed server URL was lost when the settings page was applied",
        URL_TYPED, server.getRootUrl());
  }

  /** Reports what {@code getOptionGroups()} exposes once the page has been built. */
  public void testOptionGroupsAfterPageIsBuilt() throws Exception {
    final WebDavServerDescriptor server = new WebDavServerDescriptor();
    server.setName("test server");
    server.setRootUrl(URL_BEFORE);
    myStorage.getServersOption().addValue(server);
    myStorage.getServersOption().setValueIndex(0);

    final WebDavOptionPageProvider provider = new WebDavOptionPageProvider();
    provider.init(myProject, myUiFacade);
    SwingUtilities.invokeAndWait(provider::buildPageComponent);

    GPOptionGroup[] groups = provider.getOptionGroups();
    List<String> ids = new ArrayList<>();
    for (GPOptionGroup group : groups) {
      ids.add(group.getID());
    }
    assertEquals("getOptionGroups() returned " + ids, 2, groups.length);
  }

  private static JTextField findTextFieldWithText(Component component, String text) {
    if (component instanceof JTextField && text.equals(((JTextField) component).getText())) {
      return (JTextField) component;
    }
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        JTextField found = findTextFieldWithText(child, text);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
