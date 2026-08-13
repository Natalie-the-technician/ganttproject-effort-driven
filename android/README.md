# GanttProject Mobile

Read and edit [GanttProject](https://www.ganttproject.biz) files (`.gan`) on Android.

GanttProject is a desktop application. This app is for the times you are not at
the desk: check the chart, tick off progress, note the hours you actually spent,
adjust who works how much, and pull time entries in from Toggl Track.

> **Status: early.** The file handling is covered by tests and the round trip is
> verified against a real GanttProject sample, but the app has not yet been
> through a wide range of real projects. Keep a backup of anything important.

---

## What it does

| | |
|---|---|
| **View** | Gantt chart with working-day bars, progress shading, milestones, weekend and today markers; pinch to zoom, jump to today |
| **Outline** | Fold and unfold task groups, using the same `expand` attribute the desktop writes |
| **Progress** | Set completion on leaf tasks |
| **Hours** | Planned effort and actual hours per task |
| **Resources** | Hours per day per person, assignment load in percent, assign and unassign |
| **Utilisation** | Day-by-day load per resource, with overload flagged |
| **Time import** | Pull entries from Toggl Track, match them to tasks, add the hours |

## What it deliberately does not do

- **Move dates or change durations.** GanttProject's scheduler propagates dates
  through the dependency graph. That scheduler does not run here, so editing a
  date on the phone would leave every dependent task where it was and produce a
  file that looks fine and is quietly wrong. Dates are shown, not edited.
- **Create or delete tasks.** Same reason.
- **Write back to Toggl.** The time tracker stays the source of truth for time.
- **Sync in the background.** Imports happen when you ask, with a preview first.
- **Derive progress from time.** Hours spent are not a measure of completion,
  and conflating them is how a project reports 90% done forever.

---

## Your files stay yours

The app edits the XML in place. Every attribute and element it does not
understand — view settings, column widths, calendars, roles, baselines, notes,
anything a newer GanttProject version adds — is preserved untouched, and the
diff of an edit contains only the lines actually edited.

That is not an accident of implementation; it is the reason the app parses with
SAX and keeps its own tree rather than using the standard DOM, which reorders
every attribute in the file on save. See `XmlTree.kt` for the details.

Every field this app adds is stored as a GanttProject **custom property**,
which desktop GanttProject reads and writes as a normal feature. A file can
move phone → desktop → phone without losing them; in the desktop they simply
show up as ordinary (if inert) columns.

| Property | On | Meaning |
|---|---|---|
| `effort_hours` | task | Planned effort in hours |
| `effort_actual_hours` | task | Hours actually spent |
| `hours_per_day` | resource | Working hours per day; unset means 8 |
| `toggl_match_keys` | task | Confirmed time-entry descriptions, `\|`-separated |
| `toggl_imported` | task | Imported time entries as `entryId=hours`, `\|`-separated |

New XML attributes were **not** an option: GanttProject's `TaskSaver` writes a
hard-coded attribute list, so anything it does not know is silently dropped the
next time the desktop saves — data gone, no error.

File access goes through Android's document picker, so the app works with local
storage and with any cloud provider installed on the device (Drive, Nextcloud,
OneDrive, Dropbox) and needs no storage permission at all: it can only ever
touch a file you handed it.

---

## Install

**From a release (easiest, works in the GitHub mobile app).** Open
**Releases**, take the newest `GanttProject Mobile …` entry and tap the `.apk`
attached to it. Allow installation from unknown sources when asked.

Releases are cut on demand: **Actions → Android app → Run workflow**, leave
*Publish the APK as a GitHub release* ticked. Every push builds an APK, but
only a manual run publishes one, so the release list stays meaningful.

The **Run workflow** button appears once the workflow is on the repository's
default branch. Until then the same thing can be triggered through the API.

**From a build artifact.** Every push to `android/` attaches
`ganttproject-mobile-debug-apk` to its workflow run. Artifacts download as a
ZIP and only through a browser — the GitHub mobile app cannot fetch them — and
they expire after 90 days.

Builds are signed with the standard Android debug key, which is fine for
sideloading and unfit for the Play Store.

Requires Android 8.0 (API 26) or newer.

## Build it yourself

```bash
cd android
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/
```

You need an Android SDK; Android Studio provides one, or set `ANDROID_HOME`.

The core module needs no Android SDK at all and can be built and tested
anywhere:

```bash
cd android
./gradlew :gantt-core:test
```

`settings.gradle.kts` only includes `:app` when an SDK is present, precisely so
that the logic which can corrupt a project file stays testable on machines that
have no SDK — including CI containers where the SDK download is blocked.

---

## Layout

```
android/
├── gantt-core/          Pure Kotlin. No Android. Unit-tested, no emulator.
│   ├── XmlTree.kt           SAX-based tree that preserves attribute order
│   ├── GanttDocument.kt     Load, read, edit in place, save
│   ├── Model.kt             Read-only snapshot types
│   ├── WorkingCalendar.kt   Working days, weekends, holidays
│   ├── ForkProperties.kt    Custom-property names, effort arithmetic
│   ├── ResourceLoad.kt      Per-day utilisation and overload detection
│   ├── TimeEntryMatching.kt Matching, splitting, double-import protection
│   ├── TogglClient.kt       Toggl Track API v9, behind an HTTP seam
│   └── Json.kt              Minimal JSON reader
└── app/                 Jetpack Compose UI. English and German.
```

**The split is the design.** Everything that can go quietly wrong — file
writing, date arithmetic, matching rules, the guard against importing the same
hours twice — lives in `gantt-core` and is tested without an emulator. The `app`
module contains no such logic: if something is missing there, it belongs in the
core, with a test.

---

## Time import, and how it avoids double-booking

1. Enter your Toggl API token (Toggl → Profile settings). It is encrypted with
   a key held in the Android keystore, stored only on the device, excluded from
   cloud backups, and never written into the project file.
2. Pick a date range and load the entries.
3. Each entry gets scored task suggestions. **Only a certain match is
   pre-selected** — a plausible guess is left blank on purpose, because a
   pre-filled guess gets confirmed by reflex and lands hours on the wrong task.
   You can always pick any task, not just the suggestions.
4. The preview shows exactly what will be written.
5. Applying **adds** hours; it never sets them. Combined with a ledger of what
   was already imported — keyed by time-entry id alone, so it still holds when
   an entry is later reassigned to a different task — running the same import
   twice adds nothing the second time.

Confirmed pairings are remembered on the task as match keys, so the next import
recognises them outright.

### Where the "already imported" record lives

**In the project file**, as a custom property, so it travels with the project:
import from a second device, or from the desktop, and the guard still holds.

It is stored *per task* but read as a **union across every task**. That
indirection is the design. The guard has to be keyed by time-entry id alone —
key it by (entry, task) and it stops working the moment an entry is reassigned
on a second run — but there is no project-level home for it: desktop
GanttProject writes a fixed sequence of children under `<project>` and a fixed
set of registered options, so any container invented there would be silently
dropped on its next save. Storing by task while looking up by entry gets both
properties at once.

Keeping it in the file rather than on the device has a second benefit: if you
abandon an import by closing without saving, the record is discarded along with
the hours. A device-local ledger would remember an import that never reached
the file, and those hours could then never be imported again.

### What counts as a match

- `#12`, `[12]`, `Nr. 12` in the description → certain
- A previously confirmed match key → certain
- Identical task name → certain
- Task name inside the description → suggested, never certain
- Overlapping words → suggested, never certain

A **bare number does not count**. "Meeting 13" must not book onto task 13, and
loosening that rule is the single easiest way to turn this feature into a silent
mis-booking machine. There is a test named after it.

---

## Contributing

Contributions are welcome, especially:

- more real-world `.gan` files that round-trip incorrectly (open an issue with
  the file, or a reduced version of it)
- other time trackers behind the existing `HttpBackend` seam
- translations — add `app/src/main/res/values-<lang>/strings.xml`; no
  user-facing text lives in Kotlin, so a translation never needs code changes

Two rules for pull requests:

1. **Logic goes in `gantt-core`, with a test.** The UI modules must stay
   free of rules.
2. **Test the test.** Break the code on purpose and confirm the test fails. A
   check that has never failed proves nothing.

## Licence

GNU General Public License v3.0 or later — see [LICENSE](LICENSE).

GanttProject itself is a separate project by BarD Software, also under GPL v3.
This app is an independent client for its file format and is not affiliated
with or endorsed by them.

## Publishing this as its own repository

`android/` is a self-contained Gradle project with its own wrapper, licence and
README. To split it out with its history intact:

```bash
git subtree split --prefix=android -b ganttproject-mobile
git push git@github.com:<you>/ganttproject-mobile.git ganttproject-mobile:main
```

The CI workflow (`.github/workflows/android-app.yml` in the parent repository)
then needs its `working-directory: android` lines and `paths:` filters removed.
