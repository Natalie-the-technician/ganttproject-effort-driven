/*
Copyright 2012 GanttProject Team

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
package net.sourceforge.ganttproject.document.webdav;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.milton.common.Path;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.httpclient.File;
import io.milton.httpclient.Folder;
import io.milton.httpclient.Host;
import io.milton.httpclient.HttpException;
import io.milton.httpclient.IfMatchCheck;
// [Fork-Aenderung] fuer D3: bedingtes Schreiben und die Meldung eines echten Konflikts.
import net.sourceforge.ganttproject.GPLogger;
import io.milton.httpclient.ProgressListener;
import io.milton.httpclient.Resource;
import io.milton.httpclient.Utils.CancelledException;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;

/**
 * Implementation which uses Milton client library.
 *
 * @author dbarashev (Dmitry Barashev)
 */
public class MiltonResourceImpl implements WebDavResource {
  private static final ProgressListener PROGRESS_LISTENER_STUB = null;
  private Resource myImpl;

  /**
   * [Fork-Aenderung] Der ETag, den die Datei beim Lesen trug — die Fassung, auf der die
   * ungespeicherten Aenderungen beruhen. Null, solange nichts gelesen wurde.
   */
  private String myEtagAtRead;
  private final WebDavUri myUrl;
  private final Host myHost;
  private Boolean myExistance;
  private MiltonResourceFactory myFactory;

  MiltonResourceImpl(WebDavUri webDavUri, Resource impl, MiltonResourceFactory factory) {
    myUrl = webDavUri;
    myImpl = impl;
    myFactory = factory;
    myExistance = true;
    myHost = impl.host();
  }

  MiltonResourceImpl(WebDavUri uri, Host host, MiltonResourceFactory factory) {
    myFactory = factory;
    myUrl = uri;
    myHost = host;
  }

  @Override
  public boolean exists() throws WebDavException {
    if (myExistance == null) {
      Resource impl = getOptionalImpl();
      myExistance = Boolean.valueOf(impl != null);
    }
    return myExistance;
  }

  private void assertExists() {
    try {
      if (!exists()) {
        throw new WebDavRuntimeException(MessageFormat.format("Resource {0} does not exist on {1}", myUrl.path, myUrl.hostName));
      }
    } catch (WebDavException e) {
      throw new WebDavRuntimeException(MessageFormat.format("Resource {0} does not exist on {1}", myUrl.path, myUrl.hostName), e);
    }
  }
  @Override
  public boolean isCollection() {
    assertExists();
    return (myImpl instanceof File) == false;
  }

  private Resource getOptionalImpl() throws WebDavException {
    if (myImpl != null) {
      return myImpl;
    }
    Host host = getHost();
    try {
      Resource resolved = host.find(myUrl.path);
      if (resolved != null) {
        myImpl = resolved;
        return myImpl;
      }
    } catch (NotAuthorizedException e) {
      throw new WebDavException(MessageFormat.format("User {0} is not authorized to access {1}", getUsername(), myUrl.hostName), e);
    } catch (BadRequestException e) {
      throw new WebDavException(MessageFormat.format("Bad request when accessing {0}", myUrl.hostName), e);
    } catch (IOException e) {
      throw new WebDavException(MessageFormat.format("I/O problems when accessing {0}", myUrl.hostName), e);
    } catch (HttpException e) {
      throw new WebDavException(MessageFormat.format("HTTP problems when accessing {0}", myUrl.hostName), e);
    }
    return null;
  }

  private Host getHost() {
    return myHost;
  }

  @Override
  public boolean isLockSupported(boolean exclusive) {
    assertExists();
    if (myImpl.getSupportedLock() == null) {
      return false;
    }
    if (exclusive) {
      return myImpl.getSupportedLock().exclusive;
    }
    return myImpl.getSupportedLock().shared;
  }

  @Override
  public List<String> getLockOwners() {
    if (myImpl == null) {
      return Collections.emptyList();
    }
    String lockOwner = myImpl.getLockOwner();
    if (lockOwner != null) {
      return ImmutableList.of(lockOwner);
    }
    String lockToken = myImpl.getLockToken();
    return lockToken == null ? Collections.emptyList() : ImmutableList.of("Unknown user");
  }

  public boolean canLock(String username) {
    assertExists();
    if (!isLockSupported(true)) {
      return false;
    }
    List<String> lockOwners = getLockOwners();
    return lockOwners.isEmpty() || lockOwners.equals(ImmutableList.of(username));
  }

  @Override
  public boolean isLocked() {
    return !getLockOwners().isEmpty();
  }

  @Override
  public void lock(int timeout) throws WebDavException {
    assertExists();
    try {
      myImpl.lock(timeout);
      myImpl.parent.flush();
    } catch (NotAuthorizedException e) {
      throw new WebDavException(MessageFormat.format("User {0} is probably not authorized to access {1}", getUsername(), myUrl.hostName), e);
    } catch (BadRequestException e) {
      throw new WebDavException(MessageFormat.format("Bad request when accessing {0}", myUrl.hostName), e);
    } catch (HttpException e) {
      if (e.getResult() == 423) {
        throw new WebDavException(MessageFormat.format("Document {0} at {1} seems to be already locked", myUrl.path, myUrl.hostName), e);
      } else {
        throw new WebDavException(MessageFormat.format("HTTP error {1} when accessing {0}", myUrl.hostName, e.getResult()), e);
      }
    } catch (ConflictException e) {
      throw new WebDavException(MessageFormat.format("Conflict when accessing {0}", myUrl.hostName), e);
    } catch (NotFoundException e) {
      throw new WebDavException(MessageFormat.format("Resource {0} is not found on {1}", myUrl.path, myUrl.hostName), e);
    } catch (RuntimeException e) {
      throw new WebDavException(MessageFormat.format("Something went wrong when locking {0}: {1}", myUrl.buildUrl(), e.getMessage()), e);
    } catch (IOException e) {
      throw new WebDavException(MessageFormat.format("Something went wrong when locking {0}: {1}", myUrl.buildUrl(), e.getMessage()), e);
    }
  }

  @Override
  public void unlock() throws WebDavException {
    if (!isLocked()) {
      return;
    }
    assertExists();
    try {
      myImpl.unlock();
      myImpl.parent.flush();
    } catch (NotAuthorizedException e) {
      throw new WebDavException(MessageFormat.format("User {0} is probably not authorized to access {1}", getUsername(), myUrl.hostName), e);
    } catch (BadRequestException e) {
      throw new WebDavException(MessageFormat.format("Bad request when accessing {0}", myUrl.hostName), e);
    } catch (HttpException e) {
      throw new WebDavException(MessageFormat.format("HTTP problems when accessing {0}", myUrl.hostName), e);
    } catch (ConflictException e) {
      throw new WebDavException(MessageFormat.format("Conflict when accessing {0}", myUrl.hostName), e);
    } catch (NotFoundException e) {
      throw new WebDavException(MessageFormat.format("Resource {0} is not found on {1}", myUrl.path, myUrl.hostName), e);
    } catch (IOException e) {
      throw new WebDavException(MessageFormat.format("Something went wrong when locking {0}: {1}", myUrl.buildUrl(), e.getMessage()), e);
    }
  }


  @Override
  public WebDavResource getParent() {
    if (myImpl != null) {
      return new MiltonResourceImpl(myUrl.buildParent(), myImpl.parent, myFactory);
    }
    return new MiltonResourceImpl(myUrl.buildParent(), myHost, myFactory);
  }

  @Override
  public WebDavUri getWebDavUri() {
    return myUrl;
  }

  @Override
  public String getUrl() {
    return myUrl == null ? myImpl.encodedUrl() : myUrl.buildUrl();
  }

  @Override
  public List<WebDavResource> getChildResources() throws WebDavException {
    assertExists();
    try {
      return Lists.transform(((Folder)myImpl).children(), new Function<Resource, WebDavResource>() {
        @Override
        public WebDavResource apply(Resource r) {
          return new MiltonResourceImpl(myUrl.buildChild(r.name), r, myFactory);
        }
      });
    } catch (NotAuthorizedException e) {
      throw new WebDavException(MessageFormat.format("User {0} is probably not authorized to access {1}", getUsername(), myUrl.hostName), e);
    } catch (BadRequestException e) {
      throw new WebDavException(MessageFormat.format("Bad request when accessing {0}", myUrl.hostName), e);
    } catch (IOException e) {
      throw new WebDavException(MessageFormat.format("I/O problems when accessing {0}", myUrl.hostName), e);
    } catch (HttpException e) {
      throw new WebDavException(MessageFormat.format("HTTP problems when accessing {0}", myUrl.hostName), e);
    }
  }

  @Override
  public String getAbsolutePath() {
    if (myImpl != null) {
      return myImpl.path().toPath();
    }
    return Path.path(myUrl.path).toPath();

  }
  @Override
  public String getName() {
    if (myImpl != null) {
      return myImpl.name;
    }
    return Path.path(myUrl.path).getName();
  }

  /**
   * [Fork-Aenderung] Fragt den Server nach dem ETag, den die Datei JETZT traegt.
   *
   * Eigene Anfrage statt {@code myImpl.getEtag()}: das gemerkte Objekt traegt den Wert vom letzten
   * Holen, und genau darum geht es hier nicht — gefragt ist der aktuelle Stand.
   *
   * Liefert null, wenn die Frage nicht zu beantworten ist. Das ist Absicht: der Aufrufer behandelt
   * "weiss nicht" ausdruecklich, und eine erfundene Fassung waere schlimmer als keine.
   */
  private String fetchCurrentEtag() {
    try {
      Resource fresh = getHost().find(myUrl.path);
      return (fresh instanceof File) ? ((File) fresh).getEtag() : null;
    } catch (Exception e) {
      // Auch RuntimeException: diese Methode darf das Speichern nie zum Absturz bringen, sie ist
      // nur eine Zusatzfrage.
      GPLogger.log(e);
      return null;
    }
  }

  @Override
  public void write(byte[] byteArray) throws WebDavException {
    MiltonResourceImpl parent = (MiltonResourceImpl) getParent();
    if (!parent.exists()) {
      throw new WebDavException(MessageFormat.format("Folder {0} does not exist", parent.getName()));
    }
    assert parent.myImpl instanceof Folder;
    Folder parentFolder = (Folder) parent.myImpl;
    try {
      InputStream is = new BufferedInputStream(new ByteArrayInputStream(byteArray));
      if (myImpl != null && myImpl.getLockToken() != null) {
        // Mit Sperre ist der Fall bereits abgedeckt: das Token geht als If:-Header raus.
        // IfMatchCheck traegt genau EINEN String, Token und ETag lassen sich also ohnehin nicht
        // gemeinsam senden -- gebraucht wird If-Match genau dann, wenn kein Token vorliegt.
        parentFolder.upload(getName(), is, Long.valueOf(byteArray.length),
            "application/xml", new IfMatchCheck(myImpl.getLockToken(), false, true), null);
      } else {
        // [Fork-Aenderung] D3: bedingt schreiben statt bedingungslos.
        //
        // Vorher stand hier ein nacktes upload(...) ohne jede Bedingung — ohne Sperre, nach
        // Ablauf der Sperrdauer, an einem Server ohne Sperrunterstuetzung oder nach "ohne Sperre
        // oeffnen" ueberschrieb der Desktop fremde Aenderungen lautlos.
        IfMatchDecision decision = IfMatchResolutionKt.resolveIfMatch(myEtagAtRead, this::fetchCurrentEtag);
        if (decision instanceof IfMatchDecision.Conflict) {
          throw new WebDavConflictException(MessageFormat.format(
              "The file {0} was changed by somebody else since it was read", myUrl.path));
        }
        if (decision instanceof IfMatchDecision.Send) {
          String etag = ((IfMatchDecision.Send) decision).getEtag();
          parentFolder.upload(getName(), is, Long.valueOf(byteArray.length),
              "application/xml", new IfMatchCheck(etag, true, false), null);
        } else {
          parentFolder.upload(getName(), is, Long.valueOf(byteArray.length), null);
        }
      }
      // Nach dem Schreiben die neue Fassung holen. Der Apache liefert bei PUT keinen ETag, also
      // muss gefragt werden. Schlaegt das fehl, bleibt der Wert null: lieber beim naechsten Mal
      // bedingungslos schreiben, als eine Fassung zu erfinden, gegen die dann jeder Vergleich
      // scheitert.
      myEtagAtRead = fetchCurrentEtag();
    } catch (NotAuthorizedException e) {
      throw new WebDavException(MessageFormat.format("User {0} is probably not authorized to access {1}", getUsername(), myUrl.hostName), e);
    } catch (BadRequestException e) {
      throw new WebDavException(MessageFormat.format("Bad request when accessing {0}", myUrl.hostName), e);
    } catch (HttpException e) {
      // [Fork-Aenderung] 412 ist kein Netzproblem, sondern die Antwort des Servers auf If-Match:
      // die Datei hat sich seit dem Lesen geaendert. Milton bildet 412 auf GenericHttpException
      // ab -- processResultCode kennt nur 400/401/404/409 gesondert -- der Status steht in
      // getResult().
      if (e.getResult() == 412) {
        throw new WebDavConflictException(MessageFormat.format(
            "The file {0} was changed by somebody else since it was read", myUrl.path), e);
      }
      throw new WebDavException(MessageFormat.format("HTTP problems when accessing {0}", myUrl.hostName), e);
    } catch (ConflictException e) {
      throw new WebDavException(MessageFormat.format("Conflict when accessing {0}", myUrl.hostName), e);
    } catch (NotFoundException e) {
      throw new WebDavException(MessageFormat.format("Resource {0} is not found on {1}", myUrl.path, myUrl.hostName), e);
    } catch (FileNotFoundException e) {
      throw new WebDavException(MessageFormat.format("I/O problems when uploading {0} to {1}", myUrl.path, myUrl.hostName), e);
    } catch (IOException e) {
      throw new WebDavException(MessageFormat.format("I/O problems when uploading {0} to {1}", myUrl.path, myUrl.hostName), e);
    }
  }

  @Override
  public InputStream getInputStream() throws WebDavException {
    assertExists();
    assert myImpl instanceof File;
    File file = (File) myImpl;
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    try {
      file.download(content, PROGRESS_LISTENER_STUB);
      // [Fork-Aenderung] Die Fassung merken, auf der die kommenden Aenderungen beruhen. Genau
      // diesen Wert -- nicht einen spaeter frisch geholten -- verlangt If-Match beim Schreiben:
      // die Frage lautet "hat sich seit MEINEM Lesen etwas geaendert".
      myEtagAtRead = file.getEtag();
      return new ByteArrayInputStream(content.toByteArray());
    } catch (CancelledException e) {
      throw new WebDavException("File download has been canceled", e);
    } catch (HttpException e) {
      throw new WebDavException(MessageFormat.format("HTTP error {0} while downloading file", e.getResult()), e);
    }
  }

  @Override
  public boolean isWritable() {
    try {
      if (exists()) {
        return doCanLock() != CanLockStatus.LOCK_UNAVAILABLE;
      }
      WebDavResource parent = getParent();
      return parent.exists() && parent.isWritable();
    } catch (WebDavException e) {
      e.printStackTrace();
      return false;
    }
  }

  @Override
  public boolean canLock() throws WebDavException {
    return doCanLock() == CanLockStatus.LOCK_AVAILABLE;
  }

  enum CanLockStatus {
    LOCK_AVAILABLE, LOCK_UNSUPPORTED, LOCK_UNAVAILABLE
  }
  private CanLockStatus doCanLock() {
    assertExists();
    if (myImpl.getSupportedLock() == null) {
      return CanLockStatus.LOCK_UNSUPPORTED;
    }
    if (!myImpl.getSupportedLock().exclusive) {
      return CanLockStatus.LOCK_UNSUPPORTED;
    }
    List<String> lockOwners = getLockOwners();
    if (lockOwners.isEmpty() || lockOwners.equals(ImmutableList.of(getUsername()))) {
      return CanLockStatus.LOCK_AVAILABLE;
    } else {
      return CanLockStatus.LOCK_UNAVAILABLE;
    }
  }
  private String getUsername() {
    return myHost.user;
  }

  @Override
  public void delete() throws WebDavException {
    assertExists();
    try {
      myImpl.delete();
    } catch (NotAuthorizedException e) {
      throw new WebDavException(MessageFormat.format("User {0} is probably not authorized to access {1}", getUsername(), myUrl.hostName), e);
    } catch (BadRequestException e) {
      throw new WebDavException(MessageFormat.format("Bad request when deleting {0}", myUrl.hostName), e);
    } catch (HttpException e) {
      throw new WebDavException(MessageFormat.format("HTTP problems when deleting {0}", myUrl.hostName), e);
    } catch (ConflictException e) {
      throw new WebDavException(MessageFormat.format("Conflict when deleting {0}", myUrl.hostName), e);
    } catch (NotFoundException e) {
      throw new WebDavException(MessageFormat.format("Resource {0} is not found on {1}", myUrl.path, myUrl.hostName), e);
    } catch (IOException e) {
      throw new WebDavException(MessageFormat.format("I/O problems when deleting {0}", myUrl.hostName), e);
    }
  }
}
