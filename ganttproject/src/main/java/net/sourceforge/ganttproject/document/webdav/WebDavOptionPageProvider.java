package net.sourceforge.ganttproject.document.webdav;

import biz.ganttproject.core.option.ChangeValueEvent;
import biz.ganttproject.core.option.ChangeValueListener;
import biz.ganttproject.core.option.DefaultBooleanOption;
import biz.ganttproject.core.option.DefaultStringOption;
import biz.ganttproject.core.option.GPOptionGroup;
import biz.ganttproject.core.option.ListOption;
import com.google.common.collect.Lists;
import net.sourceforge.ganttproject.gui.AbstractTableAndActionsComponent.SelectionListener;
import net.sourceforge.ganttproject.gui.EditableList;
import net.sourceforge.ganttproject.gui.options.OptionPageProviderBase;
import net.sourceforge.ganttproject.gui.options.OptionsPageBuilder;
import net.sourceforge.ganttproject.language.GanttLanguage;
// [fork change] hint text for the lock timeout.
import net.sourceforge.ganttproject.fork.ForkI18nKt;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;

public class WebDavOptionPageProvider extends OptionPageProviderBase {


  public WebDavOptionPageProvider() {
    super("storage.webdav");
    // TODO Auto-generated constructor stub
  }

  /**
   * [fork change] The groups of this page, so that "Uebernehmen" actually applies them.
   *
   * BUG IN THE ORIGINAL: `return new GPOptionGroup[0];` stood here with the comment
   * "TODO Auto-generated method stub". {@link OptionPageProviderBase#commit()} runs over exactly
   * this list -- if it was empty, NOTHING was applied: neither address nor user name, password
   * or lock timeout. A newly created server was left without an address, and the next connection
   * attempt failed with "I/O problems when accessing <Servername>" -- the name stood where the
   * host name should have been. Seen on screen exactly like that.
   *
   * The fields are only filled in {@link #buildPageComponent()}. Returning an empty list until
   * then is correct and not a fallback: before the page is built there is nothing to apply.
   */
  @Override
  public GPOptionGroup[] getOptionGroups() {
    if (myServerOptions == null || myLockingOptions == null) {
      return new GPOptionGroup[0];
    }
    return new GPOptionGroup[] {myServerOptions, myLockingOptions};
  }

  private GPOptionGroup myServerOptions;
  private GPOptionGroup myLockingOptions;

  @Override
  public boolean hasCustomComponent() {
    return true;
  }

  @Override
  public JComponent buildPageComponent() {
    WebDavStorageImpl webdavStorage = (WebDavStorageImpl) getProject().getDocumentManager().getWebDavStorageUi();
    final ListOption<WebDavServerDescriptor> serversOption = webdavStorage.getServersOption();
    final EditableList<WebDavServerDescriptor> serverList = new EditableList<WebDavServerDescriptor>(
        Lists.newArrayList(serversOption.getValues()), Collections.EMPTY_LIST) {

          @Override
          protected WebDavServerDescriptor updateValue(WebDavServerDescriptor newValue, WebDavServerDescriptor curValue) {
            newValue.setUsername(curValue.getUsername());
            newValue.setPassword(curValue.getPassword());
            newValue.setRootUrl(curValue.getRootUrl());
            serversOption.updateValue(curValue, newValue);
            return newValue;
          }

          @Override
          protected WebDavServerDescriptor createValue(WebDavServerDescriptor prototype) {
            serversOption.addValue(prototype);
            return prototype;
          }

          @Override
          protected void deleteValue(WebDavServerDescriptor value) {
            serversOption.removeValueIndex(findIndex(value));
          }

          private int findIndex(WebDavServerDescriptor value) {
            return Lists.newArrayList(serversOption.getValues()).indexOf(value);
          }

          @Override
          protected WebDavServerDescriptor createPrototype(Object editValue) {
            return new WebDavServerDescriptor(String.valueOf(editValue), "", "");
          }

          @Override
          protected String getStringValue(WebDavServerDescriptor t) {
            return t.getName();
          }
    };
    serverList.getTableComponent().setPreferredSize(new Dimension(150, 300));
    serverList.setUndefinedValueLabel(GanttLanguage.getInstance().getText("webdav.serverNamePrompt"));

    final DefaultStringOption urlOption = new DefaultStringOption("webdav.server.url");
    urlOption.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        if (serverList.getSelectedObject() != null) {
          serverList.getSelectedObject().setRootUrl(urlOption.getValue());
        }
      }
    });

    final DefaultStringOption usernameOption = new DefaultStringOption("webdav.server.username");
    usernameOption.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        if (serverList.getSelectedObject() != null) {
          serverList.getSelectedObject().setUsername(usernameOption.getValue());
        }
      }
    });

    final DefaultStringOption passwordOption = new DefaultStringOption("webdav.server.password");
    passwordOption.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        if (serverList.getSelectedObject() != null) {
          serverList.getSelectedObject().setPassword(passwordOption.getValue());
        }
      }
    });
    passwordOption.setScreened(true);

    final DefaultBooleanOption savePasswordOption = new DefaultBooleanOption("webdav.server.savePassword", false);
    savePasswordOption.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        if (serverList.getSelectedObject() != null) {
          serverList.getSelectedObject().setSavePassword(savePasswordOption.getValue());
        }
      }
    });

    GPOptionGroup optionGroup = new GPOptionGroup("webdav.server", urlOption, usernameOption, passwordOption, savePasswordOption);
    // [fork change] remember them, so getOptionGroups() returns them and "Uebernehmen" works.
    myServerOptions = optionGroup;

    serverList.getTableAndActions().addSelectionListener(new SelectionListener<WebDavServerDescriptor>() {
      @Override
      public void selectionChanged(List<WebDavServerDescriptor> selection) {
        if (selection.size() == 1) {
          WebDavServerDescriptor selected = selection.get(0);
          urlOption.setValue(selected.getRootUrl());
          usernameOption.setValue(selected.getUsername());
          passwordOption.setValue(selected.getPassword());
          savePasswordOption.setValue(selected.getSavePassword());
        }
      }
    });
    int selected = Lists.newArrayList(serversOption.getValues()).indexOf(serversOption.getValue());
    if (selected >= 0) {
      serverList.getTableAndActions().setSelection(selected);
    }
    //Box result = Box.createHorizontalBox();
    JPanel serversPanel = new JPanel(new BorderLayout());
    final JComponent listComponent = serverList.createDefaultComponent();
    serversPanel.add(listComponent, BorderLayout.CENTER);

    OptionsPageBuilder builder = new OptionsPageBuilder();
    GPOptionGroup lockingGroup = new GPOptionGroup("webdav.lock", webdavStorage.getWebDavLockTimeoutOption(), webdavStorage.getWebDavReleaseLockOption());
    lockingGroup.setI18Nkey(builder.getI18N().getCanonicalOptionLabelKey(webdavStorage.getWebDavLockTimeoutOption()), "webdav.lockTimeout.label");
    lockingGroup.setI18Nkey(builder.getI18N().getCanonicalOptionLabelKey(webdavStorage.getWebDavReleaseLockOption()), "option.webdav.lock.releaseOnProjectClose.label");
    myLockingOptions = lockingGroup;
    // [fork change] D2: name what a negative lock timeout switches off.
    //
    // "Timeout (Minuten)" does not say that a value below 0 means "never lock" -- and
    // HttpDocument.acquireLock() reports success while doing nothing. Whoever once set the value
    // negative has been working without a lock ever since, and nothing on this page says so.
    //
    // The hint also names what STILL protects in that case. Without that half-sentence the line
    // reads like "you are unprotected", and since D3 that would simply be wrong.
    JPanel lockingPanel = new JPanel(new BorderLayout());
    final JComponent lockingOptions = builder.buildPlanePage(new GPOptionGroup[] {lockingGroup});
    lockingPanel.add(lockingOptions, BorderLayout.CENTER);
    JLabel lockingHint = new JLabel(ForkI18nKt.forkText("fork.webdav.lockTimeout.hint"));
    lockingHint.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
    lockingPanel.add(lockingHint, BorderLayout.SOUTH);
    serversPanel.add(lockingPanel, BorderLayout.SOUTH);

    builder = new OptionsPageBuilder(null, OptionsPageBuilder.ONE_COLUMN_LAYOUT);
    JComponent serverDetails = builder.buildPlanePage(new GPOptionGroup[] {optionGroup});
    serverDetails.setPreferredSize(new Dimension(300, 300));

    // [fork change] A split area instead of BorderLayout WEST/CENTER.
    //
    // BUG IN THE ORIGINAL: BorderLayout gives WEST its full preferred width and the centre only
    // the remainder -- even when that is negative. Measured on screen with the built-in
    // diagnostic:
    //
    //   page           width= 591
    //   server list    x=  5  width= 656   (sticks out by 70 px)
    //   server details x=661  width= -75   (negative, so not present)
    //
    // Servers could thereby be created, but address, user and password never seen or changed.
    // That is precisely what "the server settings are broken" meant.
    //
    // JSplitPane instead of fixed pixel values: it divides what is there and gives neither side
    // a negative width. If the space is not enough, the divider can be dragged -- a hard-coded
    // width would be wrong again at a different font size or screen scaling.
    JSplitPane result = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, serversPanel, serverDetails);
    result.setResizeWeight(0.5);
    result.setBorder(BorderFactory.createEmptyBorder());
    // NO setDividerLocation(double) here. The proportion is computed against the CURRENT size,
    // and at this point that is still zero -- the divider then lands at the edge and one side
    // gets width 0. resizeWeight alone divides correctly on the first layout.
    final JComponent page = OptionPageProviderBase.wrapContentComponent(result, getCanonicalPageTitle(), null);

    return page;
  }

}
