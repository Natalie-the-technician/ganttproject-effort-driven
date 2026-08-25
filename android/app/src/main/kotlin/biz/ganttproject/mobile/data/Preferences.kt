/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import biz.ganttproject.mobile.core.EditScope
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the time-tracking API token encrypted with a key held in the Android
 * keystore.
 *
 * The key is generated inside the keystore and is not extractable, so the
 * stored ciphertext is useless off this device — including in a cloud backup,
 * which is why the backup rules exclude this file as well.
 *
 * Written by hand rather than with `androidx.security:security-crypto`
 * because that library has been stuck in alpha for years; this is the same
 * primitive (AES-GCM with a keystore-held key) in far less code and with no
 * dependency to keep up with.
 */
/** Which project the home-screen widget shows, and where it lives. */
sealed interface WidgetTarget {
  data object None : WidgetTarget

  /** A file on the device, reachable through the `ContentResolver`. */
  data class Local(val uri: String) : WidgetTarget

  /** A project on the sync server, reachable only over HTTP. */
  data class Remote(val name: String) : WidgetTarget
}

class SecureStore(context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun putSecret(key: String, value: String?) {
    if (value.isNullOrEmpty()) {
      prefs.edit().remove(key).apply()
      return
    }
    val encrypted = runCatching { encrypt(value) }.getOrNull() ?: return
    prefs.edit().putString(key, encrypted).apply()
  }

  fun getSecret(key: String): String? {
    val stored = prefs.getString(key, null) ?: return null
    // A failure here means the keystore entry is gone — after a device
    // restore, or when the user removed their screen lock on some OEM
    // builds. Dropping the unreadable value beats crashing on every launch.
    return runCatching { decrypt(stored) }.getOrElse {
      prefs.edit().remove(key).apply()
      null
    }
  }

  private fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val iv = cipher.iv
    val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    // The IV is not secret but must be stored alongside the ciphertext.
    return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
      Base64.encodeToString(body, Base64.NO_WRAP)
  }

  private fun decrypt(stored: String): String {
    val (ivPart, bodyPart) = stored.split(":", limit = 2).let { it[0] to it[1] }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      secretKey(),
      GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivPart, Base64.NO_WRAP))
    )
    return String(cipher.doFinal(Base64.decode(bodyPart, Base64.NO_WRAP)), Charsets.UTF_8)
  }

  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        // No user authentication requirement: the import has to work without
        // an unlock prompt mid-flow, and the threat model here is another app
        // reading the file, not someone holding the unlocked phone.
        .setUserAuthenticationRequired(false)
        .build()
    )
    return generator.generateKey()
  }

  companion object {
    /** Excluded from backups in res/xml/backup_rules.xml. */
    private const val PREFS_NAME = "secure_prefs"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gantt_mobile_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    const val KEY_TOGGL_TOKEN = "toggl_token"

    /**
     * The WebDAV password. Here rather than in app_prefs because Basic auth
     * sends it on every single request — it is a live credential, not a
     * setting.
     */
    const val KEY_DAV_PASSWORD = "dav_password"
  }
}

/** One entry in the recent-files list. */
data class RecentFile(val uri: String, val displayName: String, val openedAt: Long)

/**
 * Ordinary (unencrypted) app preferences: just the recent-files list.
 * Nothing here is a secret — a file name is already visible in the picker.
 *
 * The import ledger deliberately does NOT live here. It is stored in the
 * project file itself (see [biz.ganttproject.mobile.core.GanttDocument.importedHoursByEntry]),
 * so it travels with the project instead of being stranded on one device.
 */
class AppPreferences(context: Context) {

  private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

  // ---------------------------------------------------------- Recent files

  fun recentFiles(): List<RecentFile> =
    prefs.getStringSet(KEY_RECENT, emptySet())
      .orEmpty()
      .mapNotNull { entry ->
        // uri|name|timestamp — the URI is percent-encoded and never contains
        // a bare '|', so a plain split is safe here.
        val parts = entry.split("|")
        if (parts.size < 3) return@mapNotNull null
        RecentFile(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
      }
      .sortedByDescending { it.openedAt }
      .take(MAX_RECENT)

  fun rememberFile(uri: String, displayName: String, now: Long) {
    val kept = recentFiles().filterNot { it.uri == uri }.take(MAX_RECENT - 1)
    val updated = (listOf(RecentFile(uri, displayName, now)) + kept)
      .map { "${it.uri}|${it.displayName}|${it.openedAt}" }
      .toSet()
    prefs.edit().putStringSet(KEY_RECENT, updated).apply()
  }

  fun forgetFile(uri: String) {
    val kept = recentFiles()
      .filterNot { it.uri == uri }
      .map { "${it.uri}|${it.displayName}|${it.openedAt}" }
      .toSet()
    prefs.edit().putStringSet(KEY_RECENT, kept).apply()
  }

  fun clearRecentFiles() = prefs.edit().remove(KEY_RECENT).apply()

  // ------------------------------------------------------- Widget settings

  /**
   * Which project the home-screen widget shows.
   *
   * Stored as the document URI, whose read/write grant the app persisted when
   * the file was first opened — that grant is what lets a widget touch the
   * file at all, without any storage permission.
   */
  private fun widgetProjectUri(): String? = prefs.getString(KEY_WIDGET_URI, null)

  /**
   * Where the widget's project lives, as one answer that cannot be half-read.
   *
   * [widgetProjectUri] returns an `https` address for a server project, and a
   * caller that reads it without asking [widgetRemoteName] first hands that
   * address to the `ContentResolver`, which cannot open it. That mistake was
   * made three times in one evening — in the save path, in the auto-save
   * path, and in the widget tap — always by code that had no reason to
   * suspect the value was not a file.
   *
   * A caller of this cannot forget, because there is nothing to forget: the
   * type does not let a URI out without saying it is one.
   */
  fun widgetTarget(): WidgetTarget {
    widgetRemoteName()?.let { return WidgetTarget.Remote(it) }
    val uri = widgetProjectUri() ?: return WidgetTarget.None
    return WidgetTarget.Local(uri)
  }

  fun widgetProjectName(): String? = prefs.getString(KEY_WIDGET_NAME, null)

  fun setWidgetProject(uri: String?, displayName: String?, remoteName: String? = null) {
    prefs.edit()
      .putString(KEY_WIDGET_URI, uri)
      .putString(KEY_WIDGET_NAME, displayName)
      .putString(KEY_WIDGET_REMOTE, remoteName)
      .apply()
  }

  /**
   * The project name on the sync server, or null when the widget points at a
   * local file.
   *
   * The widget draws from a snapshot either way; this decides where a tap
   * writes to and what the refresh button fetches.
   */
  fun widgetRemoteName(): String? = prefs.getString(KEY_WIDGET_REMOTE, null)?.ifBlank { null }

  /**
   * When the snapshot behind the widget was taken, or 0 when there is none.
   *
   * Shown on the widget rather than kept internal. A widget that draws
   * yesterday's numbers as though they were current is the same class of
   * mistake as an address field that shows only its first thirty characters:
   * correct in what it displays, wrong in what it lets you conclude.
   */
  fun widgetSnapshotAt(): Long = prefs.getLong(KEY_WIDGET_SNAPSHOT_AT, 0L)

  fun setWidgetSnapshotAt(millis: Long) {
    prefs.edit().putLong(KEY_WIDGET_SNAPSHOT_AT, millis).apply()
  }

  /**
   * Why the last tap on the widget did nothing, for the seconds after it.
   *
   * A widget has no dialog and no snackbar, so a refused tap would otherwise
   * be indistinguishable from a tap that did not register — the failure mode
   * that had somebody pressing "open from server" three times in two seconds
   * because an empty result looked exactly like nothing happening.
   *
   * Expires by itself rather than being cleared. A redraw can come from
   * anywhere at any time, and a flag that has to be reset somewhere would
   * eventually be shown by the redraw that nobody thought about.
   */
  fun widgetNotice(): String? {
    val at = prefs.getLong(KEY_WIDGET_NOTICE_AT, 0L)
    if (at == 0L || System.currentTimeMillis() - at > NOTICE_LIFETIME_MS) return null
    return prefs.getString(KEY_WIDGET_NOTICE, null)?.ifBlank { null }
  }

  fun setWidgetNotice(key: String?) {
    prefs.edit()
      .putString(KEY_WIDGET_NOTICE, key)
      .putLong(KEY_WIDGET_NOTICE_AT, if (key == null) 0L else System.currentTimeMillis())
      .apply()
  }

  /** How many days ahead the widget looks. */
  fun widgetWindowDays(): Int =
    prefs.getInt(KEY_WIDGET_DAYS, biz.ganttproject.mobile.core.AgendaWindow.DEFAULT_DAYS)

  fun setWidgetWindowDays(days: Int) = prefs.edit().putInt(KEY_WIDGET_DAYS, days).apply()

  // --------------------------------------------------------------- Sync server

  /**
   * Address of the WebDAV collection holding the projects.
   *
   * Only the address and the user name live here; the password belongs to
   * [SecureStore]. Splitting them is not tidiness — app_prefs is plain XML in
   * the app's data directory and is included in device backups, and a
   * password there would travel to wherever a backup goes.
   */
  fun davBaseUrl(): String? = prefs.getString(KEY_DAV_URL, null)?.ifBlank { null }

  fun davUsername(): String? = prefs.getString(KEY_DAV_USER, null)?.ifBlank { null }

  fun setDavServer(baseUrl: String?, username: String?) {
    prefs.edit()
      .putString(KEY_DAV_URL, baseUrl?.trim())
      .putString(KEY_DAV_USER, username?.trim())
      .apply()
  }

  /**
   * Whether the server was observed to support locking, from the last
   * connection check.
   *
   * Recorded rather than assumed. It decides whether the app may treat the
   * storage as managed and drop the edit-protection warning, and a server
   * that cannot lock is — for conflicts — no better than a synced folder.
   */
  fun davSupportsLocking(): Boolean = prefs.getBoolean(KEY_DAV_LOCKING, false)

  fun setDavSupportsLocking(value: Boolean) =
    prefs.edit().putBoolean(KEY_DAV_LOCKING, value).apply()

  // ---------------------------------------------------------- Edit protection

  /**
   * How much this device is allowed to change; see [EditScope].
   *
   * Read from disk on every call rather than cached, because the widget runs
   * in a different process from the app: a cached copy in the widget would go
   * on permitting edits after the user switched them off in the app.
   */
  fun editScope(): EditScope = EditScope.fromKey(prefs.getString(KEY_EDIT_SCOPE, null))

  fun setEditScope(scope: EditScope) =
    prefs.edit().putString(KEY_EDIT_SCOPE, scope.key).apply()

  // ------------------------------------------------------- Time recording

  /**
   * The timer that is currently running, as one encoded line, or null.
   *
   * Kept here rather than in memory because the process does not survive: the
   * system kills the app while a timer runs and nobody would notice until the
   * hours were gone. A start timestamp on disk survives that, and the elapsed
   * time is a subtraction whenever somebody asks — which is why there is no
   * service and nothing that has to keep ticking.
   *
   * Deliberately not in the project file: a timer is a thing this device is
   * doing right now, not a fact about the plan, and writing it would dirty a
   * document the user has not changed.
   */
  fun runningTimer(): String? = prefs.getString(KEY_RUNNING_TIMER, null)?.ifBlank { null }

  fun setRunningTimer(encoded: String?) =
    prefs.edit().putString(KEY_RUNNING_TIMER, encoded).apply()

  /**
   * Who the records of this device belong to, or null when nobody said.
   *
   * Free text, and only ever copied into a record: the app has no notion of
   * accounts and must not invent one. Null stays null rather than becoming a
   * guess from the device owner or the WebDAV login — "nobody said who" is a
   * different statement from a name, and a report that quietly attributes
   * work is worse than one that admits the gap.
   */
  fun person(): String? = prefs.getString(KEY_PERSON, null)?.trim()?.ifBlank { null }

  fun setPerson(name: String?) =
    prefs.edit().putString(KEY_PERSON, name?.trim()?.ifBlank { null }).apply()

  companion object {
    private const val KEY_RECENT = "recent_files"
    private const val KEY_WIDGET_URI = "widget_project_uri"
    private const val KEY_WIDGET_NAME = "widget_project_name"
    private const val KEY_WIDGET_DAYS = "widget_window_days"
    private const val KEY_WIDGET_REMOTE = "widget_remote_name"
    private const val KEY_WIDGET_SNAPSHOT_AT = "widget_snapshot_at"
    private const val KEY_WIDGET_NOTICE = "widget_notice"
    private const val KEY_WIDGET_NOTICE_AT = "widget_notice_at"

    /** Long enough to be read, short enough not to outlive its occasion. */
    private const val NOTICE_LIFETIME_MS = 30_000L
    private const val KEY_EDIT_SCOPE = "edit_scope"
    private const val KEY_DAV_URL = "dav_base_url"
    private const val KEY_DAV_USER = "dav_username"
    private const val KEY_DAV_LOCKING = "dav_supports_locking"
    private const val KEY_RUNNING_TIMER = "running_timer"
    private const val KEY_PERSON = "record_person"
    private const val MAX_RECENT = 10
  }
}
