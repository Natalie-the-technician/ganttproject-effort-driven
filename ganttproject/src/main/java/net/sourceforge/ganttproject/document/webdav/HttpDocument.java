/*
GanttProject is an opensource project management tool.
Copyright (C) 2003-2011 GanttProject Team

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

import biz.ganttproject.core.option.StringOption;
import net.sourceforge.ganttproject.GPLogger;
import net.sourceforge.ganttproject.document.AbstractURLDocument;
import net.sourceforge.ganttproject.document.Document;
import net.sourceforge.ganttproject.document.webdav.WebDavResource.WebDavException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * This class implements the interface Document for file access on HTTP-servers
 * and WebDAV-enabled-servers.
 *
 * @author Michael Haeusler (michael at akatose.de)
 */
public class HttpDocument extends AbstractURLDocument {

  public static final int NO_LOCK = -1;

  private String lastError;

  private final WebDavResource webdavResource;

  private boolean locked = false;

  private boolean malformedURL = false;

  private final String myUsername;

  private final String myPassword;

  private final int myTimeout;

  public HttpDocument(String url, String username, String password, StringOption proxyOption) throws IOException, WebDavException {
    this(url, username, password, proxyOption, NO_LOCK);
  }

  /**
   * [Fork-Aenderung] Wie oben, aber mit Sperrdauer.
   *
   * FEHLER IM ORIGINAL: Die Ueberladung darueber setzte fest {@code -1}, und sie ist der Weg, ueber
   * den GanttProject WebDAV-Dokumente ueberhaupt oeffnet. Die Einstellung {@code webdav.lockTimeout}
   * erreichte das Dokument damit nie — sie ging nur an den Oeffnen-Dialog. Wer sie auf 120 stellte,
   * aenderte nichts, ohne dass es irgendwo aufgefallen waere.
   */
  public HttpDocument(String url, String username, String password, StringOption proxyOption, int lockTimeout)
      throws IOException, WebDavException {
    this(new MiltonResourceFactory(username, password, proxyOption).createResource(new WebDavUri(url)),
        username, password, lockTimeout);
  }

  public HttpDocument(WebDavResource webdavResource, String username, String password, int lockTimeout) throws IOException {
    this.webdavResource = webdavResource;
    myUsername = username;
    myPassword = password;
    myTimeout = lockTimeout;
  }

  WebDavResource getWebdavResource() {
    return webdavResource;
  }

  @Override
  public String getFileName() {
    return getWebdavResource().getUrl();
  }

  @Override
  public boolean canRead() {
    WebDavResource res = getWebdavResource();
    try {
      return (null != res && (res.exists() && !res.isCollection()));
    } catch (WebDavException e) {
      return false;
    }
  }

  @Override
  public IStatus canWrite() {
    WebDavResource res = getWebdavResource();
    boolean exists;
    try {
      exists = res.exists();
    } catch (WebDavException e) {
      exists = false;
    }
    try {
      if (exists) {
        if (res.isCollection()) {
          return new Status(IStatus.ERROR, Document.PLUGIN_ID,  Document.ErrorCode.IS_DIRECTORY.ordinal(), res.getUrl(), null);
        }
        if (res.isWritable()) {
          return Status.OK_STATUS;
        }
        return new Status(IStatus.ERROR, Document.PLUGIN_ID, Document.ErrorCode.NOT_WRITABLE.ordinal(), res.getUrl(), null);
      }
    } catch (WebDavException e) {
      return new Status(IStatus.ERROR, Document.PLUGIN_ID,  Document.ErrorCode.GENERIC_NETWORK_ERROR.ordinal(), res.getUrl(), e);
    }
    try {
      WebDavResource parent = res.getParent();
      if (!parent.exists() || !parent.isCollection()) {
        return new Status(IStatus.ERROR, Document.PLUGIN_ID, Document.ErrorCode.PARENT_IS_NOT_DIRECTORY.ordinal(),
            parent.getUrl(), null);
      }
      return Status.OK_STATUS;
    } catch (WebDavException e) {
      return new Status(IStatus.ERROR, Document.PLUGIN_ID, Document.ErrorCode.GENERIC_NETWORK_ERROR.ordinal(),
          e.getMessage(), e);
    }
  }

  @Override
  public boolean isValidForMRU() {
    return (!malformedURL);
  }

  @Override
  public boolean acquireLock() {
    if (locked) {
      return true;
    }
    if (myTimeout < 0) {
      // [Fork-Aenderung] D2: sagen, dass hier nicht gesperrt wird.
      //
      // Der Rueckgabewert bleibt true -- der Aufrufer soll nicht warnen, denn niemand hat versagt:
      // die Einstellung steht auf "nie sperren". Aber "Erfolg" zu melden, ohne etwas getan zu
      // haben, war bisher vollkommen stumm. Wer die Sperrdauer irgendwann einmal negativ gesetzt
      // hat, arbeitet seither ohne Sperre und findet dafuer nirgends einen Beleg.
      //
      // Die Uebergabe verlangte diese Beschriftung am Knopf "ohne Sperre oeffnen". Den gibt es
      // nicht mehr: sein Dialog haengt an CloudProjectActionBase, und diese Klasse hat im ganzen
      // Repo keine Ableitung und keinen weiteren Verweis -- toter Code. Erreichbar ist die Wahl
      // heute nur ueber diese Einstellung, also gehoert der Hinweis hierher.
      GPLogger.log("WebDAV: keine Sperre fuer " + getFileName()
          + " -- die Sperrdauer steht auf \"nie sperren\". Gegen versehentliches Ueberschreiben"
          + " schuetzt weiterhin If-Match beim Speichern.");
      return true;
    }
    if (null == getWebdavResource()) {
      return false;
    }
    try {
      if (!getWebdavResource().exists()) {
        return true;
      }
      getWebdavResource().lock(myTimeout * 60);
      locked = true;
      return locked;
    } catch (WebDavException e) {
      if (!GPLogger.log(e)) {
        e.printStackTrace(System.err);
      }
    }
    return false;
  }

  @Override
  public void releaseLock() {
    if (null == getWebdavResource()) {
      return;
    }
    try {
      locked = false;
      if (!getWebdavResource().isLocked()) {
        return;
      }
      getWebdavResource().unlock();
    } catch (WebDavException e) {
      if (!GPLogger.log(e)) {
        e.printStackTrace(System.err);
      }
    }
  }

  @Override
  public InputStream getInputStream() throws IOException {
    try {
      return getWebdavResource().getInputStream();
    } catch (WebDavException e) {
      throw new IOException(e);
    }
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (null == getWebdavResource()) {
      throw new IOException(lastError);
    }
    return new HttpDocumentOutputStream(this);
  }

  @Override
  public String getPath() {
    return getFileName();
  }

  @Override
  public String getUsername() {
    return myUsername;
  }

  @Override
  public String getPassword() {
    return myPassword;
  }

  @Override
  public String getLastError() {
    return lastError;
  }

//  public static void setLockDAVMinutes(int i) {
//    // FIXME should not be static, as each derived object should have its own
//    // setting
//    lockDAVMinutes = i;
//  }

  @Override
  public void write() throws IOException {
    // TODO Auto-generated method stub
  }

  @Override
  public URI getURI() {
    try {
      return new URI(webdavResource.getUrl());
    } catch (URISyntaxException e) {
      return null;
    }
  }

  @Override
  public boolean isLocal() {
    return false;
  }

  public static String getHTTPError(int code) {
    // TODO Use language dependent texts
    switch (code) {
    case 401:
      return "Unauthorized (401)";
    default:
      return "<unspecified> (" + code + ")";
    }
  }
}
