/*
GanttProject is an opensource project management tool. License: GPL3
Copyright (C) 2012 GanttProject Team

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
package net.sourceforge.ganttproject.document.webdav;

import biz.ganttproject.core.option.*;
import biz.ganttproject.storage.cloud.GPCloudStorageOptions;
import biz.ganttproject.core.option.BooleanOption;
import biz.ganttproject.core.option.ChangeValueEvent;
import biz.ganttproject.core.option.ChangeValueListener;
import biz.ganttproject.core.option.DefaultBooleanOption;
import biz.ganttproject.core.option.DefaultEnumerationOption;
import biz.ganttproject.core.option.DefaultIntegerOption;
import biz.ganttproject.core.option.DefaultStringOption;
import biz.ganttproject.core.option.EnumerationOption;
import biz.ganttproject.core.option.GPAbstractOption;
import biz.ganttproject.core.option.IntegerOption;
import biz.ganttproject.core.option.ListOption;
import biz.ganttproject.core.option.StringOption;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import net.sourceforge.ganttproject.GPLogger;
import net.sourceforge.ganttproject.IGanttProject;
import net.sourceforge.ganttproject.ProjectEventListener;
// [fork change] for D1: take the lock when opening and warn on failure.
import biz.ganttproject.app.BarrierEntrance;
import biz.ganttproject.app.Barrier;
import net.sourceforge.ganttproject.gui.NotificationChannel;
import net.sourceforge.ganttproject.fork.ForkI18nKt;
import net.sourceforge.ganttproject.action.CancelAction;
import net.sourceforge.ganttproject.action.OkAction;
import net.sourceforge.ganttproject.document.Document;
import net.sourceforge.ganttproject.document.DocumentStorageUi;
import net.sourceforge.ganttproject.gui.UIFacade;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Implements storage UI for WebDAV storages
 *
 * @author dbarashev (Dmitry Barashev)
 */
public class WebDavStorageImpl implements DocumentStorageUi {
  private final GPCloudStorageOptions myServers = new GPCloudStorageOptions();
  private final StringOption myLegacyLastWebDAVDocument = new DefaultStringOption("last-webdav-document", "");
  private final StringOption myLastWebDavDocumentOption = new DefaultStringOption("lastDocument", null);
  /**
   * [fork change] Default changed from -1 to 120 minutes.
   *
   * -1 means "never lock", and {@link HttpDocument#acquireLock()} nevertheless reported success.
   * A protective feature that is off as shipped AND keeps quiet about it is worse than none at
   * all: it creates trust that is not backed by anything.
   *
   * 120 minutes: long enough for a working session, short enough that a lock forgotten after a
   * crash expires by itself and does not lock anybody out permanently.
   */
  private final IntegerOption myWebDavLockTimeoutOption = new DefaultIntegerOption("webdav.lockTimeout", 120);
  private final BooleanOption myReleaseLockOption = new DefaultBooleanOption("lockRelease", true);
  private final StringOption myUsername = new DefaultStringOption("username", "");
  private final StringOption myPassword = new DefaultStringOption("password", "");
  private final StringOption myProxy = new DefaultStringOption("proxy", "");
  private final MiltonResourceFactory myWebDavFactory = new MiltonResourceFactory();
  private final UIFacade myUiFacade;
  private final IGanttProject myProject;

  public WebDavStorageImpl(final IGanttProject project, UIFacade uiFacade) {
    myProject = project;
    myUiFacade = uiFacade;
    project.addProjectEventListener(new ProjectEventListener.Stub() {
      /**
       * [fork change] The lock is now actually taken.
       *
       * BUG IN THE ORIGINAL: NOBODY in the whole program called {@code acquireLock()} — only
       * {@code releaseLock()} below was used. So what was released had never been taken. Together
       * with the lock timeout that never reached the document this meant: GanttProject ran
       * against a lock-capable server and never locked, without a message and without a log line.
       *
       * If the lock fails, a warning is shown and the file is opened anyway. Reading thereby
       * stays possible, and accidental overwriting is caught by If-Match since D3 in any case.
       * Refusing to open would turn a lock forgotten after a crash into a lockout.
       */
      @Override
      public void projectOpened(BarrierEntrance barrierRegistry, Barrier<IGanttProject> barrier) {
        barrier.await(result -> {
          Document document = project.getDocument();
          if (document != null && !document.acquireLock()) {
            GPLogger.log("Could not acquire a WebDAV lock for " + document.getFileName());
            myUiFacade.showNotificationDialog(NotificationChannel.WARNING,
                ForkI18nKt.forkText("fork.webdav.lockFailed", document.getFileName()));
          }
          return kotlin.Unit.INSTANCE;
        });
      }

      @Override
      public void projectClosed() {
        if (myReleaseLockOption.isChecked() && project.getDocument() != null) {
          project.getDocument().releaseLock();
        }
      }
    });
    myPassword.setScreened(true);
  }

  @Override
  public Components open(Document currentDocument, final DocumentReceiver receiver) {
    final GanttURLChooser chooser = createChooser(currentDocument);
    final OkAction openAction = createNoLockAction("storage.action.open", chooser, receiver);
    //final OkAction openAndLockAction = createLockAction("storage.action.openAndLock", chooser, receiver);
//    chooser.setSelectionListener(new GanttURLChooser.SelectionListener() {
//      @Override
//      public void setSelection(WebDavResource resource) {
//        if (resource == null) {
//          return;
//        }
//        try {
//          openAndLockAction.setEnabled(resource.canLock());
//        } catch (WebDavException e) {
//          chooser.showError(e);
//        }
//      }
//    });
    JComponent contentPane = chooser.createOpenDocumentUi(openAction);
    chooser.getPathOption().addChangeValueListener(event -> {
      boolean empty = "".equals(event.getNewValue());
      openAction.setEnabled(!empty);
//        openAndLockAction.setEnabled(!empty);
    });
    return new Components(contentPane, new Action[] {openAction, /*openAndLockAction,*/ new CancelAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          receiver.setDocument(null);
        } catch (IOException | Document.DocumentException e1) {
          e1.printStackTrace();
        }
      }
    }});
  }

  @Override
  public Components save(Document currentDocument, final DocumentReceiver receiver) {
    final GanttURLChooser chooser = createChooser(currentDocument);
    OkAction saveAction = createNoLockAction("storage.action.save", chooser, receiver);
    JComponent contentPane = chooser.createSaveDocumentUi(saveAction);
    return new Components(contentPane, new Action[] {saveAction, new CancelAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          receiver.setDocument(null);
        } catch (IOException | Document.DocumentException e1) {
          e1.printStackTrace();
        }
      }
    }});
  }

  private GanttURLChooser createChooser(Document currentDocument) {
    WebDavUri currentUri;
    if (currentDocument instanceof HttpDocument) {
      currentUri = ((HttpDocument)currentDocument).getWebdavResource().getWebDavUri();
      myUsername.setValue(currentDocument.getUsername());
      myPassword.setValue(currentDocument.getPassword());
    } else {
      String lastDocument = MoreObjects.firstNonNull(
          getLastWebDavDocumentOption().getValue(), getLegacyLastWebDAVDocumentOption().getValue());
      if (lastDocument == null) {
        currentUri = null;
      } else {
        String[] savedComponents = lastDocument.split("\\t");
        if (savedComponents.length == 1) {
          currentUri = new WebDavUri(savedComponents[0]);
        } else {
          try {
            URL rootUrl = new URL(savedComponents[0]);
            currentUri = new WebDavUri(rootUrl.getHost(), savedComponents[0], savedComponents[1]);
          } catch (MalformedURLException e) {
            GPLogger.logToLogger(e);
            currentUri = null;
          }
        }
      }
    }
    myWebDavFactory.clearCache();
    return new GanttURLChooser(myProject, myUiFacade, myServers, currentUri, myUsername, myPassword, getWebDavLockTimeoutOption(), getWebDavReleaseLockOption(), myWebDavFactory);
  }

  private OkAction createNoLockAction(String key, final GanttURLChooser chooser, final DocumentReceiver receiver) {
    return new OkAction(key) {
      {
        setDefault(false);
      }
      @Override
      public void actionPerformed(ActionEvent event) {
        try {
          myWebDavFactory.setCredentials(chooser.getUsername(), chooser.getPassword());
          WebDavUri webDavUri = chooser.getUrl();
          if (webDavUri != null) {
            receiver.setDocument(new HttpDocument(
                myWebDavFactory.createResource(webDavUri), chooser.getUsername(), chooser.getPassword(), HttpDocument.NO_LOCK));
            myLastWebDavDocumentOption.setValue(webDavUri.buildRootUrl() + "\t" + webDavUri.path);
            myLegacyLastWebDAVDocument.setValue(webDavUri.buildUrl());
          }
          chooser.dispose();
        } catch (IOException e) {
          chooser.showError(e);
        } catch (Document.DocumentException e) {
          e.printStackTrace();
        }
      }
    };
  }

  public GPCloudStorageOptions getServersOption() {
    return myServers;
  }

  public StringOption getLegacyLastWebDAVDocumentOption() {
    return myLegacyLastWebDAVDocument;
  }

  public StringOption getLastWebDavDocumentOption() {
    return myLastWebDavDocumentOption;
  }

  public IntegerOption getWebDavLockTimeoutOption() {
    return myWebDavLockTimeoutOption;
  }

  public BooleanOption getWebDavReleaseLockOption() {
    return myReleaseLockOption;
  }

  public WebDavServerDescriptor findServer(String path) {
    WebDavUri uri = new WebDavUri(path);
    for (WebDavServerDescriptor server : myServers.getValues()) {
      if (server.getRootUrl().equals(uri.buildRootUrl())) {
        return server;
      }
    }
    return null;
  }

  public StringOption getProxyOption () {
    return myProxy;
  }
}
