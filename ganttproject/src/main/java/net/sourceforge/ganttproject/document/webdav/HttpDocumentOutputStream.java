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
// [Fork-Aenderung] Ein Schreibkonflikt wird als Versionskonflikt gemeldet, nicht als IO-Fehler.
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
      // [Fork-Aenderung] Ein Konflikt ist kein Ein-/Ausgabefehler. Als IOException verpackt
      // erschiene er als "irgendetwas ging schief", und die Speicherlogik koennte dem Menschen
      // nicht die Wahl anbieten, die es hier gibt: als Kopie speichern statt zu ueberschreiben.
      //
      // canOverwrite=false, weil der Weg zum erzwungenen Schreiben fuer WebDAV bewusst noch nicht
      // gebaut ist -- siehe ProjectUIFacadeImpl.saveProjectTrySave. Lieber gar kein Knopf als
      // einer, der nichts tut.
      throw new VersionMismatchException(false);
    } catch (WebDavResource.WebDavVersioningUnavailableException e) {
      // [Fork-Aenderung] Andere Ursache, andere Meldung. Hier hat NIEMAND die Datei geaendert --
      // der Server kann die Frage nur nicht beantworten. Wuerde das als Konflikt durchgereicht,
      // suchte der Benutzer nach einem Kollegen, den es nicht gibt.
      throw new VersionMismatchException(false, true);
    } catch (WebDavException e) {
      throw new IOException(e);
    }
  }
}
