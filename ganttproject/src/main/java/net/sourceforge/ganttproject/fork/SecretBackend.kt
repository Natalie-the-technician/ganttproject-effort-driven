/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
package net.sourceforge.ganttproject.fork

import net.sourceforge.ganttproject.GPLogger
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [fork change] One way of keeping a secret, on one platform.
 *
 * WHY AN INTERFACE AT ALL: until 09.09.2026 [SecretStore] was DPAPI and nothing else, so on Linux
 * and macOS the WebDAV password was not stored at all and the Toggl token was written in plain
 * text. Both are the same problem seen from two sides, and both need a platform store to fix. The
 * three stores have nothing in common except this interface -- DPAPI encrypts, the other two keep
 * the secret themselves -- which is exactly why the difference belongs behind an interface and not
 * in an `if`.
 *
 * THE TWO KINDS ARE NOT THE SAME THING, and the interface has to carry both:
 *
 *  * DPAPI ENCRYPTS. What goes into `~/.ganttproject` is the ciphertext itself; nothing is kept
 *    anywhere else. The [handle] passed to [protect] is not used.
 *  * libsecret and the macOS Keychain STORE. The secret goes to the keyring; what goes into
 *    `~/.ganttproject` is only a REFERENCE to it. The [handle] is that reference, and it has to be
 *    stable across saves -- otherwise every write would leave another orphaned keyring entry
 *    behind.
 *
 * What both have in common, and all the callers need, is: a string that may be written into the
 * settings file, and a way back. That is the whole interface.
 */
interface SecretBackend {

  /** For the log. Never a secret. */
  val name: String

  /**
   * Prefix of every value this backend writes into the settings file.
   *
   * It has to be recognisable WITHOUT the backend being available, because a file written on one
   * machine gets read on another. It must contain neither a tab nor a line break: the WebDAV server
   * list separates its fields by tabs and its entries by line breaks.
   */
  val marker: String

  /**
   * Whether a secret can really be kept here RIGHT NOW -- not whether the operating system is the
   * right one.
   *
   * That difference is the point. `os.name` says nothing about whether a keyring is installed,
   * running and unlocked. An `isAvailable` that says yes and then fails is worse than one that says
   * no, because the caller has already decided not to ask the user again.
   */
  fun isAvailable(): Boolean

  /**
   * @param handle the reference under which the secret is to be found again. Stable across saves.
   * @return what belongs in the settings file, already carrying [marker] -- or null, meaning
   * "nothing may be written". Null NEVER means "write it in the clear".
   */
  fun protect(handle: String, plain: String): String?

  /**
   * @param handle the part of the stored value AFTER the marker.
   * @return the secret, or null if it cannot be produced -- keyring locked, entry deleted,
   * ciphertext from another machine.
   */
  fun reveal(handle: String): String?
}

/**
 * [fork change] The candidate ways for an operating system, most preferred first.
 *
 * A PURE FUNCTION ON PURPOSE, and public on purpose: this is the only place where the sentence
 * "under Windows nothing changes" can be checked mechanically, and it has to be checkable on a
 * machine that is not Windows. Everything else about DPAPI needs Windows to run; the CHOICE does
 * not. See `SecretStoreTest.testOnWindowsItIsStillDpapiAndNothingElse`.
 */
fun secretBackendsFor(osName: String): List<SecretBackend> = when {
  osName.startsWith("Windows") -> listOf(DpapiBackend)
  // `os.name` is "Mac OS X" on every macOS to date; "Darwin" appears on some JVMs.
  osName.startsWith("Mac") || osName.startsWith("Darwin") -> listOf(MacKeychainBackend)
  // Everything else is taken to be a freedesktop system. libsecret is not Linux-only; it works on
  // the BSDs too, and where it is missing `isAvailable` says so.
  else -> listOf(LibsecretBackend)
}

/**
 * [fork change] Windows, unchanged since 17.08.2026.
 *
 * MEASURED, not assumed: `jna-platform` only comes in indirectly via `appdirs`, the core `jna` sits
 * beside it in a different version (5.16 against 5.13). Whether DPAPI runs at all in this mixture
 * was open and was checked with `tools/dpapiprobe` against the shipped classpath: round trip in
 * order, 246 bytes of ciphertext, no plain text in the result.
 *
 * DPAPI ties the ciphertext to the Windows account: a copied file is worthless elsewhere. This is
 * no vault -- whoever can run programs as this user can decrypt as well. It removes exactly the
 * class of mistakes at issue here: passwords visible in backups, in data stores, and over the
 * shoulder.
 *
 * [isAvailable] IS UNCONDITIONALLY TRUE, and that is deliberate. DPAPI is part of the operating
 * system; there is nothing to install, start or unlock, so there is nothing a probe could find out.
 * A probe would have to encrypt something at startup to learn anything at all -- new behaviour on
 * the one platform this change is NOT meant to touch, and unmeasurable from here. The failure that
 * a probe would catch (the JNA version mixture) is caught by [protect] returning null.
 */
object DpapiBackend : SecretBackend {
  override val name = "DPAPI"
  override val marker = "dpapi:"

  override fun isAvailable() = true

  /** [handle] is unused: DPAPI needs no reference, the ciphertext is self-contained. */
  override fun protect(handle: String, plain: String): String? = try {
    val cipher = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(
      plain.toByteArray(StandardCharsets.UTF_8))
    marker + Base64.getEncoder().encodeToString(cipher)
  } catch (e: Throwable) {
    // Error too: a missing library must not abort the saving of the settings.
    GPLogger.log(e)
    null
  }

  override fun reveal(handle: String): String? = try {
    String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
      Base64.getDecoder().decode(handle)), StandardCharsets.UTF_8)
  } catch (e: Throwable) {
    GPLogger.log(e)
    null
  }
}

/**
 * [fork change] Linux and the BSDs: the Secret Service, reached through `secret-tool`.
 *
 * WHY A PROGRAM AND NOT D-BUS DIRECTLY -- measured on 09.09.2026, not assumed:
 *
 * The Secret Service is a D-Bus interface, so calling it without a helper program looks like the
 * cleaner way. It is not reachable from here. `Collection.CreateItem` takes `a{sv}` -- a dictionary
 * whose values are variants, one of them itself an `a{ss}` of the attributes -- and a struct
 * `(oayays)` for the secret. `dbus-send`, the only D-Bus program that can be relied on to be
 * present, cannot build either: `dbus-send ... dict:string:variant:...` answers
 * `dbus-send: Unknown type "variant"`. It has no syntax for nested containers and none for structs
 * at all. `OpenSession`, whose signature is flat, does go through -- which is what makes the limit
 * visible rather than a guess.
 *
 * Speaking D-Bus from Java without `dbus-send` would mean a D-Bus library, and this fork is to
 * build without new jars. Mapping libsecret's C API through the JNA that is already on the
 * classpath was the third candidate; it needs `GHashTable` from glib built by hand through JNA, and
 * a wrong signature there is a JVM crash rather than an error. Measured against the actual benefit
 * it would buy -- `libsecret-1.so.0` being present where `secret-tool` is not -- it buys nothing on
 * this machine: neither is installed here.
 *
 * WHAT WAS MEASURED ABOUT `secret-tool` (Ubuntu 22.04 in the container `computer-use`,
 * libsecret-tools, gnome-keyring 40.0):
 *
 *  * `store` reads the secret from STDIN. It does not appear in `/proc/<pid>/cmdline`, which is
 *    readable by every user on the machine. Checked by looking at the command line of a `store`
 *    that was blocked on a FIFO.
 *  * `lookup` writes the secret to stdout WITHOUT adding anything: "zeile1\nzeile2\n" went in as 14
 *    bytes and came back as the same 14 bytes. Nothing is trimmed here for that reason -- trimming
 *    would silently damage a secret ending in a line break.
 *  * `store` with attributes that already exist REPLACES the entry instead of adding a second one.
 *    That is what makes a stable [handle] enough to keep repeated saves from piling up orphans.
 *  * With no Secret Service reachable it fails LOUDLY: exit code 1 and a message on stderr
 *    ("Cannot autolaunch D-Bus without X11 $DISPLAY"). It does not fall back to anything.
 */
object LibsecretBackend : SecretBackend {
  override val name = "libsecret"
  override val marker = "libsecret:"

  override fun isAvailable(): Boolean = availability

  override fun protect(handle: String, plain: String): String? {
    if (!isSafeHandle(handle)) {
      return null
    }
    val result = runSecretCommand(
      listOf(SECRET_TOOL, "store", "--label=$LABEL_PREFIX$handle",
        ATTR_APPLICATION, APPLICATION, ATTR_HANDLE, handle),
      plain.toByteArray(StandardCharsets.UTF_8))
    return if (result != null && result.exitCode == 0) marker + handle else null
  }

  override fun reveal(handle: String): String? {
    if (!isSafeHandle(handle)) {
      return null
    }
    val result = runSecretCommand(
      listOf(SECRET_TOOL, "lookup", ATTR_APPLICATION, APPLICATION, ATTR_HANDLE, handle), null)
    return if (result != null && result.exitCode == 0) {
      String(result.stdout, StandardCharsets.UTF_8)
    } else {
      null
    }
  }

  private const val SECRET_TOOL = "secret-tool"
  private const val APPLICATION = "ganttproject"
  private const val ATTR_APPLICATION = "application"
  private const val ATTR_HANDLE = "handle"
  private const val LABEL_PREFIX = "GanttProject "

  /**
   * The end-to-end probe: put something there and take it back out.
   *
   * NOT "is the program installed" and not "does the exit code look right". Both lie in the case
   * that matters. `secret-tool lookup` for a key that is not there exits 1, and `secret-tool
   * lookup` with no Secret Service at all ALSO exits 1 -- both measured. Telling them apart by the
   * text on stderr would mean depending on a message wording. Writing and reading back is the only
   * check that cannot be right for the wrong reason.
   *
   * The probe value is a fixed, public string. NO SECRET IS USED FOR THE PROBE.
   *
   * It runs at most once per program run, and lazily: not at startup but the first time a secret is
   * actually to be kept or fetched. That matters because a locked keyring answers with a dialogue,
   * and a dialogue is expected at the moment somebody saves a password -- not while the program is
   * still starting.
   */
  private val availability: Boolean by lazy {
    val probe = "probe_availability"
    val stored = runSecretCommand(
      listOf(SECRET_TOOL, "store", "--label=$LABEL_PREFIX$probe",
        ATTR_APPLICATION, APPLICATION, ATTR_HANDLE, probe),
      PROBE_VALUE.toByteArray(StandardCharsets.UTF_8))
    if (stored == null || stored.exitCode != 0) {
      // The probe value is public, so its stderr may be logged. That does NOT hold for the calls
      // in protect/reveal, and they log nothing for that reason.
      logQuietly("[fork] No Secret Service available (secret-tool store exit "
        + "${stored?.exitCode ?: "timeout/not found"}): ${stored?.stderr?.trim() ?: ""}")
      return@lazy false
    }
    val read = runSecretCommand(
      listOf(SECRET_TOOL, "lookup", ATTR_APPLICATION, APPLICATION, ATTR_HANDLE, probe), null)
    val ok = read != null && read.exitCode == 0 &&
      String(read.stdout, StandardCharsets.UTF_8) == PROBE_VALUE
    if (!ok) {
      logQuietly("[fork] Secret Service reachable but the probe did not come back unchanged; "
        + "secrets will NOT be stored there.")
    }
    ok
  }
}

/**
 * [fork change] macOS: the Keychain, reached through the `security` program.
 *
 * **NOT MEASURED -- NEEDS macOS.** There was no macOS machine. Everything below is written from the
 * documented behaviour of `security(1)` and has never run. It is handled the way F27 is handled:
 * named as unmeasured rather than quietly counted as done. The check to run is in the report of
 * 09.09.2026.
 *
 * TWO DELIBERATE DIFFERENCES FROM THE LINUX WAY, both because this code could not be measured:
 *
 *  1. THE SECRET IS STORED BASE64-ENCODED. That is not a security measure -- the Keychain does the
 *     protecting -- it removes two failure modes that could not be checked from here.
 *     `security find-generic-password -w` is documented to print the password FOLLOWED BY A LINE
 *     BREAK, so a secret ending in a line break could not be told from one that does not; and a
 *     secret beginning with "-" would be read by `security` as an option. Base64 has neither
 *     problem. Whoever gets to measure this on macOS may well be able to drop the encoding; until
 *     then it is the difference between "works" and "probably works".
 *  2. THE SECRET GOES ON THE COMMAND LINE, in `-w <value>`. On Linux this was deliberately avoided
 *     because `/proc/<pid>/cmdline` is readable by every user; macOS has no `/proc`, and how much
 *     `ps` shows of another user's arguments there is exactly the sort of thing that must not be
 *     assumed. `security -i` would read the command from stdin and keep the secret out of the
 *     arguments, but it parses its input with quoting rules of its own, and hand-written escaping
 *     that has never run against a real `security` is a worse risk than the one it removes.
 *     Whoever measures this on macOS should check it -- the step is in the report.
 */
object MacKeychainBackend : SecretBackend {
  override val name = "macOS Keychain"
  override val marker = "keychain:"

  override fun isAvailable(): Boolean = availability

  override fun protect(handle: String, plain: String): String? {
    if (!isSafeHandle(handle)) {
      return null
    }
    return if (store(handle, plain)) marker + handle else null
  }

  override fun reveal(handle: String): String? {
    if (!isSafeHandle(handle)) {
      return null
    }
    val result = runSecretCommand(
      listOf(SECURITY, "find-generic-password", "-a", handle, "-s", SERVICE, "-w"), null)
    if (result == null || result.exitCode != 0) {
      return null
    }
    // `security` appends a line break of its own; base64 never contains one.
    val encoded = String(result.stdout, StandardCharsets.UTF_8).trim()
    return try {
      String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
    } catch (e: IllegalArgumentException) {
      // Not written by this code. Better a rejected login than a wrong secret.
      null
    }
  }

  private const val SECURITY = "/usr/bin/security"
  private const val SERVICE = "GanttProject"

  private fun store(handle: String, plain: String): Boolean {
    val encoded = Base64.getEncoder().encodeToString(plain.toByteArray(StandardCharsets.UTF_8))
    // -U updates an entry that is already there instead of failing with "already exists".
    val result = runSecretCommand(
      listOf(SECURITY, "add-generic-password", "-U", "-a", handle, "-s", SERVICE,
        "-l", "GanttProject $handle", "-w", encoded), null)
    return result != null && result.exitCode == 0
  }

  /** Same reasoning as [LibsecretBackend]: write and read back, with a value that is not a secret. */
  private val availability: Boolean by lazy {
    val probe = "probe_availability"
    if (!store(probe, PROBE_VALUE)) {
      logQuietly("[fork] macOS Keychain not usable; secrets will NOT be stored there.")
      return@lazy false
    }
    reveal(probe) == PROBE_VALUE
  }
}

/**
 * [fork change] Says something, and never makes matters worse by trying.
 *
 * MEASURED on 09.09.2026, and the reason this exists: a probe run with an incomplete classpath came
 * back as `NoClassDefFoundError: biz/ganttproject/LoggingKt`, thrown out of `GPLogger.create` --
 * from inside `isAvailable`. In the packaged program that class is there and it cannot happen; the
 * point is what the shape of the failure was. `isAvailable` is asked BEFORE anything is stored, and
 * a question about whether a keyring exists had turned into an exception that came out of the
 * caller. "There is no store here" is an answer. Falling over is not, and least of all because the
 * complaint about it could not be written down.
 *
 * The message never contains a secret. Only the probes call this, and what they store is
 * [PROBE_VALUE].
 */
private fun logQuietly(message: String) {
  try {
    GPLogger.log(message)
  } catch (e: Throwable) {
    // Nowhere left to say it. Not being able to log is not a reason to lose a password.
  }
}

/** The value the probes write. Public, constant, and never a secret. */
private const val PROBE_VALUE = "ganttproject-secret-store-probe"

/**
 * How long a keyring program may take before it is given up on.
 *
 * A locked keyring answers with a dialogue and the program then waits for a person. Waiting for
 * ever would freeze GanttProject; twenty seconds is long enough to type a passphrase and short
 * enough not to look like a hang.
 */
private const val TIMEOUT_SECONDS = 20L

/**
 * The handles this code will pass to a program.
 *
 * [SecretStore] builds every handle itself and it can only ever look like this. The check is here
 * anyway, because a handle also comes back OUT of the settings file, where anybody may have put
 * anything -- and from there it would go straight into a command line. Nothing that fails this goes
 * near a process.
 */
private val SAFE_HANDLE = Regex("[A-Za-z0-9_-]{1,512}")

internal fun isSafeHandle(handle: String) = SAFE_HANDLE.matches(handle)

internal class SecretCommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

/**
 * Runs a keyring program.
 *
 * NOTHING FROM [stdout] IS EVER LOGGED. That is where the secret comes back, and a log line is a
 * file. [stderr] is returned but only the probes -- whose value is public -- log it.
 *
 * @param stdin what to write to the program, or null. Only [LibsecretBackend] uses this, and
 * `secret-tool store` writes nothing at all while it reads, so writing before reading cannot
 * deadlock here.
 * @return null if the program is not there, fails to start, or does not finish in time.
 */
internal fun runSecretCommand(command: List<String>, stdin: ByteArray?): SecretCommandResult? {
  var process: Process? = null
  return try {
    process = ProcessBuilder(command).start()
    process.outputStream.use { if (stdin != null) it.write(stdin) }
    val stdout = process.inputStream.readBytes()
    val stderr = String(process.errorStream.readBytes(), StandardCharsets.UTF_8)
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return null
    }
    SecretCommandResult(process.exitValue(), stdout, stderr)
  } catch (e: Exception) {
    // An absent program lands here as an IOException. That is the normal case on a machine without
    // a keyring, not a fault, so it is not logged as one.
    process?.destroyForcibly()
    null
  }
}
