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

  companion object {
    private const val KEY_RECENT = "recent_files"
    private const val MAX_RECENT = 10
  }
}
