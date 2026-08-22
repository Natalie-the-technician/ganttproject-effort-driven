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
import io.milton.httpclient.PropFindResponse;
import javax.xml.namespace.QName;
// [fork change] for D3: conditional writing and reporting a genuine conflict.
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
   * [fork change] The ETag the file carried when it was read — the version the unsaved changes
   * are based on. Null as long as nothing has been read.
   */
  private String myEtagAtRead;

  /**
   * [fork change] The ETag has to be requested explicitly in the PROPFIND.
   *
   * THE BUG THIS FIXES: on its own Milton asks only for creationdate, getlastmodified,
   * getcontentlength, displayname, resourcetype, iscollection and lockdiscovery -- getetag is not
   * among them. {@code File.getEtag()} therefore ALWAYS returned null, no matter what the server
   * sends, and D3 thereby fell back silently to "write unconditionally". Measured against the
   * server: "WebDAV: gelesen /haus.gan, ETag=null", and the write attempt from outside was
   * overwritten without comment.
   *
   * The unit check of {@code resolveIfMatch} was correct and did not help here: it checks the
   * decision, not the input.
   */
  private static final QName ETAG_PROPERTY = new QName("DAV:", "getetag");
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
   * [fork change] Asks the server for the ETag the file carries RIGHT NOW.
   *
   * A request of its own instead of {@code myImpl.getEtag()}: the remembered object carries the
   * value from the last fetch, and that is precisely not what matters here — the question is
   * about the current state.
   *
   * Returns null when the question cannot be answered. That is deliberate: the caller handles
   * "do not know" explicitly, and an invented version would be worse than none.
   */
  private String fetchCurrentEtag() {
    try {
      List<PropFindResponse> responses =
          getHost().propFind(Path.path(myUrl.path), 0, Collections.singletonList(ETAG_PROPERTY));
      return (responses == null || responses.isEmpty()) ? null : responses.get(0).getEtag();
    } catch (Exception e) {
      // RuntimeException too: this method must never make saving crash, it is only an
      // additional question.
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
        // With a lock the case is already covered: the token goes out as an If: header.
        // IfMatchCheck carries exactly ONE string, so token and ETag cannot be sent together
        // anyway -- If-Match is needed precisely when no token is present.
        parentFolder.upload(getName(), is, Long.valueOf(byteArray.length),
            "application/xml", new IfMatchCheck(myImpl.getLockToken(), false, true), null);
      } else {
        // [fork change] D3: write conditionally instead of unconditionally.
        //
        // Previously a bare upload(...) stood here without any condition — without a lock, after
        // the lock timeout had expired, against a server without lock support, or after "open
        // without lock" the desktop overwrote other people's changes silently.
        IfMatchDecision decision = IfMatchResolutionKt.resolveIfMatch(myEtagAtRead, this::fetchCurrentEtag);
        GPLogger.log("WebDAV: schreibe " + myUrl.path + ", gemerkter ETag=" + myEtagAtRead
            + ", Entscheidung=" + decision);
        if (decision instanceof IfMatchDecision.Conflict) {
          throw new WebDavConflictException(MessageFormat.format(
              "The file {0} was changed by somebody else since it was read", myUrl.path));
        }
        if (decision instanceof IfMatchDecision.VersioningUnavailable) {
          // [fork change] Better not to write than to write blind. Formerly a fallback to
          // "unconditional" stood here, justified with Apache's one-second window. If the server
          // delivers weak ETags permanently -- compression, proxy, CDN -- that was no longer an
          // edge case but every single write, and D3 would have been switched off silently.
          throw new WebDavVersioningUnavailableException(MessageFormat.format(
              "The server does not provide a strong ETag for {0}, so no write can be made"
                  + " conditional", myUrl.path));
        }
        if (decision instanceof IfMatchDecision.Send) {
          String etag = ((IfMatchDecision.Send) decision).getEtag();
          parentFolder.upload(getName(), is, Long.valueOf(byteArray.length),
              "application/xml", new IfMatchCheck(etag, true, false), null);
        } else {
          parentFolder.upload(getName(), is, Long.valueOf(byteArray.length), null);
        }
      }
      // Fetch the new version after writing. Apache returns no ETag on PUT, so it has to be
      // asked for. If that fails the value stays null: better to write unconditionally next time
      // than to invent a version against which every comparison then fails.
      myEtagAtRead = fetchCurrentEtag();
    } catch (NotAuthorizedException e) {
      throw new WebDavException(MessageFormat.format("User {0} is probably not authorized to access {1}", getUsername(), myUrl.hostName), e);
    } catch (BadRequestException e) {
      throw new WebDavException(MessageFormat.format("Bad request when accessing {0}", myUrl.hostName), e);
    } catch (HttpException e) {
      // [fork change] 412 is not a network problem but the server's answer to If-Match: the
      // file has changed since it was read. Milton maps 412 onto GenericHttpException --
      // processResultCode treats only 400/401/404/409 separately -- the status is in
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
      // [fork change] Remember the version the coming changes are based on. It is exactly this
      // value -- not one fetched freshly later -- that If-Match requires when writing: the
      // question is "has anything changed since MY read".
      //
      // BEFORE downloading, not after, and that is no accident. If the file changes in between,
      // the remembered ETag is older than the content that was read: saving then reports a
      // conflict that strictly speaking does not exist. The other way round -- ETag newer than
      // content -- would silently overwrite somebody else's change. Of the two errors, a
      // superfluous query is the harmless one.
      myEtagAtRead = fetchCurrentEtag();
      file.download(content, PROGRESS_LISTENER_STUB);
      // [fork change] Without this line there is no way to tell whether the protection takes
      // effect: if the server returns no ETag, D3 silently writes unconditionally -- that is,
      // exactly as unsafe as before, only invisible. Measured against the server: T3 overwrote
      // silently, and only this line showed why.
      GPLogger.log("WebDAV: gelesen " + myUrl.path + ", ETag=" + myEtagAtRead);
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
    // [fork change] A lock WE hold does not make the file unwritable.
    //
    // THE BUG D1 EXPOSED: in the PROPFIND Milton sets lockToken AND lockOwner together, but after
    // a lock() of its own only the token -- the owner stays null. getLockOwners() then returns
    // the placeholder "Unknown user", which never matches one's own user name, and doCanLock
    // reports LOCK_UNAVAILABLE. isWritable() is thereby false, and the desktop refuses to save
    // the very file it has just locked itself.
    //
    // In the original this could not show up: acquireLock() had no caller there, so the desktop
    // never held a lock. Seen against the server as soon as the lock timeout really took effect
    // -- "Dokument kann nicht geschrieben werden" (document cannot be written), without it ever
    // getting as far as the PUT.
    if (myImpl.getLockToken() != null) {
      return CanLockStatus.LOCK_AVAILABLE;
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
