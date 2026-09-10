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
package net.sourceforge.ganttproject.timetracking

import biz.ganttproject.core.option.DefaultStringOption
import biz.ganttproject.core.option.GPOptionGroup
import biz.ganttproject.core.option.StringOption
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.fork.SecretStore

/**
 * [fork change] Where the GitHub sign-in lives in the APPLICATION settings (`~/.ganttproject`),
 * never in the project file.
 *
 * The project file is shared and kept in a vault. A token in it would be handed to everyone who
 * receives the file, and this one may write a repository.
 *
 * ONE OPTION AND NOT TWO. Both tokens and both expiry times travel as one encoded line
 * ([GitHubTokenSet.encoded]). Splitting them across settings would make it possible for a settings
 * file to hold half a connection — an access token whose refresh token was lost is a connection
 * that dies silently in eight hours.
 *
 * The setting is called `github.githubTokens` in the file: the id of the option group plus the id
 * of the option, as `GanttOptions.java:889` composes them. That exact name is what the log line
 * below names, and a log that named the wrong one would be worse than none.
 *
 * THE CLIENT ID IS NOT HERE. It is not a secret — see [GITHUB_CLIENT_ID].
 */
object GitHubTokenOptions {
  val tokens: StringOption = DefaultStringOption("githubTokens", "")

  val optionGroup: GPOptionGroup = GPOptionGroup("github", tokens)
}

/**
 * [fork change] What the token store needs from a key store, and nothing else.
 *
 * A seam, and it is here for one reason that is worth stating plainly: WITHOUT IT, HALF OF
 * [OptionGitHubTokenStorage] CAN ONLY BE MEASURED ON A MACHINE THAT HAPPENS TO LACK A KEYRING.
 * `PlainTextTokenHintTest` has to write `if (SecretStore.isAvailable) return` for exactly that
 * reason, and a test that quietly measures nothing on half the machines is the kind that is green
 * for years and then wrong. Both branches are reachable here on any machine.
 *
 * [PlatformSecretKeeper] is the real one and does nothing but delegate.
 */
interface SecretKeeper {
  val isAvailable: Boolean
  val backendName: String
  fun protect(alias: String, plain: String): String?
  fun reveal(stored: String): String
  fun isProtected(stored: String): Boolean
}

/** [fork change] The real key store: DPAPI on Windows, libsecret on Linux/BSD, Keychain on macOS. */
object PlatformSecretKeeper : SecretKeeper {
  override val isAvailable: Boolean get() = SecretStore.isAvailable
  override val backendName: String get() = SecretStore.backendName
  override fun protect(alias: String, plain: String): String? = SecretStore.protect(alias, plain)
  override fun reveal(stored: String): String = SecretStore.reveal(stored)
  override fun isProtected(stored: String): Boolean = SecretStore.isProtected(stored)
}

/**
 * [fork change] Says once per run that the GitHub sign-in is being written in the clear.
 *
 * The same shape as [PlainTextTokenWarning], deliberately and not by accident: the decision it
 * records is the same one, taken on 09.09.2026 — where no key store answers, the secret is stored
 * ANYWAY and somebody is TOLD. Inventing a second mechanism for the second secret would mean two
 * places to get it wrong.
 *
 * ONCE, not once per write: `~/.ganttproject` is rewritten whenever the settings change, and a
 * warning that appears fifty times is one nobody reads.
 *
 * [alreadySaid] is public so a test can arm it again; the tests of one run share a JVM, and a
 * once-per-run flag fires for whichever test comes first and for none of the others.
 */
object PlainTextGitHubTokenWarning {
  /** Replaced in tests. In the running program it is the log and nothing else. */
  var sink: (String) -> Unit = { GPLogger.log(it) }
  var alreadySaid: Boolean = false
}

/**
 * [fork change] The token pair on the platform key store, with the settings file as the place the
 * reference (or, where there is no key store, the value) is written.
 *
 * ## What happens where there is no key store — stated, not implied
 *
 * The pair IS STORED, in the clear, and the fact is said out loud: once in the log of the session
 * it happened in, and again under the connect button where somebody can still decide against it
 * (`GitHubConnectPanel`). That is the rule taken for the Toggl token on 09.09.2026, applied to the
 * second secret rather than re-argued for it.
 *
 * ONE THING IS DIFFERENT HERE AND IS WORTH NAMING RATHER THAN GLOSSING. The reason given for the
 * Toggl token was that it cannot be typed again from memory — it has to be fetched from a website —
 * so refusing to store it would push people into keeping it in a text file next to the program.
 * That reason does not carry over: reconnecting to GitHub is eight characters and a browser, so
 * "do not store, sign in again" would cost far less here than it does there. What does carry over
 * is the other half of the decision, which is the half about honesty. The rule was given; this
 * follows it, and the difference is on the record.
 *
 * ## What it never does
 *
 * It never logs the line, protected or not, and never any part of one. What the warning names is
 * the FILE and the SETTING.
 */
class OptionGitHubTokenStorage(
  private val option: StringOption = GitHubTokenOptions.tokens,
  private val keeper: SecretKeeper = PlatformSecretKeeper,
  /** In full, for the warning. Passed in so a test does not depend on this machine's home. */
  private val optionsFilePath: () -> String = { net.sourceforge.ganttproject.GanttOptions.getOptionsFile().path },
) : GitHubTokenStorage {

  override fun readTokens(): String? {
    val stored = option.value?.takeIf { it.isNotEmpty() } ?: return null
    // Revealed here and not at the point of use: everything above this line works with the encoded
    // pair, and a key-store reference travelling further would be compared, decoded and stored
    // again as if it were the thing itself.
    return keeper.reveal(stored)
  }

  override fun writeTokens(value: String?) {
    if (value.isNullOrEmpty()) {
      option.value = ""
      return
    }
    val protectedValue = keeper.protect(SECRET_ALIAS, value)
    if (protectedValue != null) {
      option.value = protectedValue
      return
    }
    warnOnce()
    option.value = value
  }

  private fun warnOnce() {
    if (PlainTextGitHubTokenWarning.alreadySaid) {
      return
    }
    PlainTextGitHubTokenWarning.alreadySaid = true
    PlainTextGitHubTokenWarning.sink(
      "[fork] No secret store on this machine (backend: ${keeper.backendName}). The GitHub sign-in " +
        "is written to ${optionsFilePath()} IN PLAIN TEXT, under the setting " +
        "'$GITHUB_TOKENS_SETTING'. Anything running as this user can read it, it is in every backup " +
        "of that file, and it may write the hour-journal repository. Disconnect in GanttProject, or " +
        "revoke the app under Settings > Applications at GitHub, if that file leaves this machine."
    )
  }

  companion object {
    /**
     * What this secret IS, for the key store's own index.
     *
     * The same string at every save — a changing alias would leave an orphaned keyring entry behind
     * each time. Not a secret itself: it names the application, not the account.
     */
    const val SECRET_ALIAS = "github:$GITHUB_CLIENT_ID"

    /**
     * The setting as it is spelled in `~/.ganttproject`: group id plus option id.
     *
     * Written out here rather than composed from the two ids at runtime, so that the log line and
     * this constant can be checked against the file by a person reading either.
     */
    const val GITHUB_TOKENS_SETTING = "github.githubTokens"
  }
}
