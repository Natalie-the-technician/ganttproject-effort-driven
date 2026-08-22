/*
GanttProject is an opensource project management tool.
Copyright (C) 2003-2011 GanttProject team

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

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.sourceforge.ganttproject.document.webdav.WebDavResource.WebDavException;
// [fork change] A write conflict is reported as a version conflict, not as an IO error.
import biz.ganttproject.storage.VersionMismatchException;


/**
 * This class implements an OutputStream for documents on
 * WebDAV-enabled-servers. It is a helper class for HttpDocument.
 *
 * @see HttpDocument
 * @author Michael Haeusler (michael at akatose.de)
 */
class HttpDocumentOutputStream extends ByteArrayOutputStream {

  private final HttpDocument myDocument;

  HttpDocumentOutputStream(HttpDocument document) {
    super();
    myDocument = document;
  }

  @Override
  public void close() throws IOException {
    super.close();
    WebDavResource wr = myDocument.getWebdavResource();
    try {
      wr.write(toByteArray());
    } catch (WebDavResource.WebDavConflictException e) {
      // [fork change] A conflict is not an input/output error. Wrapped as an IOException it
      // would appear as "something went wrong", and the saving logic could not offer the choice
      // that exists here: save as a copy instead of overwriting.
      //
      // canOverwrite=false, because the path to forced writing has deliberately not been built
      // for WebDAV yet -- see ProjectUIFacadeImpl.saveProjectTrySave. Better no button at all
      // than one that does nothing.
      throw new VersionMismatchException(false);
    } catch (WebDavResource.WebDavVersioningUnavailableException e) {
      // [fork change] Different cause, different message. Here NOBODY changed the file -- the
      // server merely cannot answer the question. Were this passed on as a conflict, the user
      // would go looking for a colleague who does not exist.
      throw new VersionMismatchException(false, true);
    } catch (WebDavException e) {
      throw new IOException(e);
    }
  }
}
