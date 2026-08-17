// Copyright (C) 2016 BarD Software
package biz.ganttproject.storage.cloud;

import biz.ganttproject.core.option.EnumerationOption;
import biz.ganttproject.core.option.GPAbstractOption;
import biz.ganttproject.core.option.ListOption;
import com.google.common.base.Strings;
// [Fork-Aenderung] Passwoerter nicht mehr im Klartext ablegen.
import net.sourceforge.ganttproject.fork.SecretStore;
import com.google.common.collect.ImmutableSet;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import net.sourceforge.ganttproject.document.webdav.WebDavServerDescriptor;

import java.util.*;

/**
 * @author dbarashev@bardsoftware.com
 */
public class GPCloudStorageOptions extends GPAbstractOption<WebDavServerDescriptor> implements ListOption<WebDavServerDescriptor> {
  public static final String CANONICAL_GANTTPROJECT_CLOUD_URL = "http://webdav.ganttproject.biz";
  private static final Set<String> GANTTPROJECT_CLOUD_SERVERS = ImmutableSet.of(
      CANONICAL_GANTTPROJECT_CLOUD_URL, "https://webdav.ganttproject.biz", "https://webdav.ganttproject.cloud", "http://ganttproject-cloud.appspot.com/webdav"
  );

  private final List<WebDavServerDescriptor> myServers = new ArrayList<>();
  private final ObservableList<WebDavServerDescriptor> myObservableList = FXCollections.observableList(myServers);

  public GPCloudStorageOptions() {
    super("servers");
  }

  public ObservableList<WebDavServerDescriptor> getList() {
    return myObservableList;
  }

  public Optional<WebDavServerDescriptor> getCloudServer() {
    WebDavServerDescriptor result = findCloudServerDescriptor(GANTTPROJECT_CLOUD_SERVERS);
    return result == null ? Optional.empty() : Optional.of(result);
  }

  public void setCloudServer(CloudSettingsDto serverDto) {
    WebDavServerDescriptor cloudServer = findCloudServerDescriptor(GANTTPROJECT_CLOUD_SERVERS);
    if (cloudServer == null) {
      cloudServer = new WebDavServerDescriptor("GP Cloud", serverDto.serverUrl, serverDto.username, serverDto.password);
      addValue(cloudServer);
    } else {
      cloudServer.setUsername(serverDto.username);
      cloudServer.setPassword(serverDto.password);
      myObservableList.set(myObservableList.indexOf(cloudServer), cloudServer);
    }
  }

  public ObservableList<WebDavServerDescriptor> getWebdavServers() {
    return myObservableList.filtered(server -> {
      if (server.getName() == null || server.getRootUrl() == null) {
        return false;
      }
      return !GANTTPROJECT_CLOUD_SERVERS.contains(server.getRootUrl());
    });
  }

  private WebDavServerDescriptor findCloudServerDescriptor(Collection<String> goodUrls) {
    for (WebDavServerDescriptor server : myServers) {
      if (goodUrls.contains(server.getRootUrl())) {
        return server;
      }
    }
    return null;
  }

  @Override
  public void setValues(Iterable<WebDavServerDescriptor> values) {
    values.forEach(myObservableList::add);
  }

  @Override
  public Iterable<WebDavServerDescriptor> getValues() {
    return Collections.unmodifiableList(myServers);
  }

  @Override
  public void setValueIndex(int idx) {
    super.setValue(myServers.get(idx));
  }

  @Override
  public void addValue(WebDavServerDescriptor value) {
    myObservableList.add(value);
  }

  @Override
  public void updateValue(WebDavServerDescriptor oldValue, WebDavServerDescriptor newValue) {
    FXCollections.replaceAll(myObservableList, oldValue,newValue);
  }

  @Override
  public void removeValueIndex(int idx) {
    myObservableList.remove(idx);
  }

  @Override
  public EnumerationOption asEnumerationOption() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getPersistentValue() {
    StringBuilder result = new StringBuilder();
    for (WebDavServerDescriptor server : myServers) {
      result.append("\n").append(server.getName()).append("\t").append(server.getRootUrl()).append("\t").append(server.getUsername());
      if (server.getSavePassword()) {
        // [Fork-Aenderung] Verschluesselt statt im Klartext.
        //
        // FEHLER IM ORIGINAL: hier stand das Passwort unveraendert in der Datei. Jedes Programm
        // unter demselben Benutzer konnte es lesen, und es wanderte in jede Sicherung von
        // ~/.ganttproject. Der Haken wurde deshalb nicht gesetzt und das Passwort stattdessen bei
        // jedem Start neu getippt -- Sicherheit, die Muehe kostet, wird irgendwann abgeschaltet.
        //
        // Liefert protect() null (kein Windows, oder DPAPI nicht verfuegbar), wird NICHT
        // gespeichert. Lieber weiter fragen als stillschweigend Klartext schreiben.
        String protectedPassword = SecretStore.INSTANCE.protect(server.getPassword());
        if (protectedPassword != null) {
          result.append("\t").append(protectedPassword);
        }
      }
    }
    return result.toString();
  }

  @Override
  public void loadPersistentValue(String value) {
    for (String s : value.split("\\n")) {
      if (!Strings.isNullOrEmpty(s)) {
        String[] parts = s.split("\\t");
        WebDavServerDescriptor server = new WebDavServerDescriptor();
        if (parts.length >= 1) {
          server.setName(parts[0]);
        }
        if (parts.length >= 2) {
          server.setRootUrl(parts[1]);
        }
        if (parts.length >= 3) {
          server.setUsername(parts[2]);
        }
        if (parts.length >= 4) {
          // [Fork-Aenderung] Entschluesseln. Ein Wert ohne Kennzeichen stammt aus der Zeit vor
          // dieser Aenderung und wird unveraendert uebernommen -- beim naechsten Speichern ist er
          // verschluesselt.
          server.setPassword(SecretStore.INSTANCE.reveal(parts[3]));
          server.setSavePassword(true);
        }
        if (!server.getRootUrl().isEmpty()) {
          myObservableList.add(server);
        }
      }
    }
  }

  public void removeValue(WebDavServerDescriptor server) {
    myObservableList.remove(server);
  }
}
