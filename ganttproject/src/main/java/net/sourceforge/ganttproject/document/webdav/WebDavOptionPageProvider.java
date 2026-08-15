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
// [Fork-Aenderung] Hinweistext zur Sperrdauer.
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

  @Override
  public GPOptionGroup[] getOptionGroups() {
    // TODO Auto-generated method stub
    return new GPOptionGroup[0];
  }

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
    serversPanel.add(serverList.createDefaultComponent(), BorderLayout.CENTER);

    OptionsPageBuilder builder = new OptionsPageBuilder();
    GPOptionGroup lockingGroup = new GPOptionGroup("webdav.lock", webdavStorage.getWebDavLockTimeoutOption(), webdavStorage.getWebDavReleaseLockOption());
    lockingGroup.setI18Nkey(builder.getI18N().getCanonicalOptionLabelKey(webdavStorage.getWebDavLockTimeoutOption()), "webdav.lockTimeout.label");
    lockingGroup.setI18Nkey(builder.getI18N().getCanonicalOptionLabelKey(webdavStorage.getWebDavReleaseLockOption()), "option.webdav.lock.releaseOnProjectClose.label");
    // [Fork-Aenderung] D2: benennen, was eine negative Sperrdauer abschaltet.
    //
    // "Timeout (Minuten)" sagt nicht, dass ein Wert unter 0 "nie sperren" bedeutet -- und
    // HttpDocument.acquireLock() meldet dabei Erfolg, ohne etwas zu tun. Wer den Wert einmal
    // negativ gesetzt hat, arbeitet seither ohne Sperre, und nichts auf dieser Seite sagt es ihm.
    //
    // Der Hinweis nennt auch, was dann NOCH schuetzt. Ohne diesen Halbsatz liest sich die Zeile
    // wie "du bist ungeschuetzt", und das waere seit D3 schlicht falsch.
    JPanel lockingPanel = new JPanel(new BorderLayout());
    lockingPanel.add(builder.buildPlanePage(new GPOptionGroup[] {lockingGroup}), BorderLayout.CENTER);
    JLabel lockingHint = new JLabel(ForkI18nKt.forkText("fork.webdav.lockTimeout.hint"));
    lockingHint.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
    lockingPanel.add(lockingHint, BorderLayout.SOUTH);
    serversPanel.add(lockingPanel, BorderLayout.SOUTH);

    builder = new OptionsPageBuilder(null, OptionsPageBuilder.ONE_COLUMN_LAYOUT);
    JPanel result = new JPanel(new BorderLayout());
    result.add(serversPanel, BorderLayout.WEST);
    JComponent serverDetails = builder.buildPlanePage(new GPOptionGroup[] {optionGroup});
    serverDetails.setPreferredSize(new Dimension(300, 300));
    result.add(serverDetails, BorderLayout.CENTER);
    //result.add(Box.createHorizontalGlue());
    return OptionPageProviderBase.wrapContentComponent(result, getCanonicalPageTitle(), null);
  }
}
