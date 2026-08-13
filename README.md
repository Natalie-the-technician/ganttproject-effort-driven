GanttProject — fork with effort-driven scheduling and time tracking
===================================================================

A fork of [GanttProject](https://github.com/bardsoftware/ganttproject) that adds two things the
original does not have: **deriving the duration of a task from its effort and the availability of
the assigned resources**, and **recording the hours actually spent**, optionally imported from
[Toggl Track](https://toggl.com/track/).

The first one has been open upstream since 2013 as
[issue #83](https://github.com/bardsoftware/ganttproject/issues/83).

> The working documents (`CLAUDE-NOTES.md`, `HANDOVER-Zeiterfassung.md` and the design notes) are
> written in German — they are this project's shared memory, not user documentation.

---

## What this fork does, and how far it has been verified

Honesty before completeness: the table says what is built but **not yet** exercised in the running
application.

| Part | State |
|---|---|
| **Duration from effort** — an effort field per task, daily hours per resource, duration = effort ÷ available hours per day | **done, verified in the running application** |
| **Recorded hours** — input field in the task dialog, stored as a custom property | **done, verified on screen and in the saved file** |
| **Toggl connection** — fetching, authentication, error kinds, rate limit | built, tested **against recorded answers only** |
| **Matching** — suggestions, splitting, protection against a second import | built, with tests |
| **Applying to tasks** — preview, one single undo step, values read back from the database | built, with tests |
| **Token per person** — kept in the application settings, never in the project file | built, with tests |
| **Connection check** — menu item under *Resources*, reads only | built |
| **Own message bundle** — the fork becomes translatable without touching upstream files | built, with a packaging check |
| **Matching dialog and import menu item** | **open** — this is the next step |

**It has never talked to the real Toggl service.** The connection check exists precisely for that:
to make the first real request **before** anything is written into tasks.

### Deliberately out of scope

- No automatic background sync — import on request, with a preview.
- Nothing is ever sent back to Toggl.
- Progress and completion are never derived from time data.
- **Recorded hours never feed back into the planned duration.** Time spent is not the same as work
  done; a task must not rewrite its own plan while it is still being worked on.

---

## Building and running

You need a **JDK 21 with JavaFX** (for example Liberica or Zulu "full"). A JDK without JavaFX
fails the build with `Unresolved reference 'javafx'`.

```bash
git clone <this repository>
git submodule update --init        # upstream translations
```

### Running a build by hand

```powershell
.\gradlew.bat :ganttproject-builder:clean :ganttproject-builder:distBin
.\tools\start-testbuild.bat
```

Two traps, each of which has cost a full round of manual testing here:

- **`clean` is not optional.** The version number contains the date, so every build on a new day
  drops *another* program file next to the old ones — and the **oldest** one is loaded.
  `BUILD SUCCESSFUL` therefore tells you nothing about which version starts. Close the running
  application first as well, or the files are locked and silently not replaced.
- **`dist-bin\ganttproject.exe` does not start.** It expects a bundled runtime that only `distWin`
  produces, and otherwise falls back to the system Java, which may have no JavaFX.
  `tools\start-testbuild.bat` sets `JAVA_HOME` to the JDK the build used instead — **adjust the
  path in that file for your machine.**

### Tests

Run the two modules **separately**, not in one command:

```powershell
.\gradlew.bat :ganttproject-tester:test
.\gradlew.bat :ganttproject:test
```

`:ganttproject:test` aborts in the pre-existing `GPCloudDocumentTest` (an upstream path problem on
Windows). In a combined invocation `:ganttproject-tester:test` then does not run **at all**, and
you end up reading stale result files as if they were new.

Last measured locally: **370 tests in `ganttproject-tester`, 0 failures.** Tests added after that
are not included; current numbers and the verification state live in `CLAUDE-NOTES.md`.

Anyone changing the resource path of the message bundle also has to run `tools/packcheck/` — a
unit test cannot tell a working path from a broken one here, for the reason explained in
`tools/packcheck/README.md`.

---

## Where the documentation is

| File | Contents |
|---|---|
| `CLAUDE-NOTES.md` | **the working memory**: state of every session, every trap found, every counter-test |
| `HANDOVER-Zeiterfassung.md` | handover to the next session: what is done, what is unverified, what comes next |
| `ENTWURF-Ist-Stunden-Import.md` | the implementation design, with the traps listed per step |
| `NOTIZ-Ist-Stunden.md`, `NOTIZ-Zeiterfassung-Import.md` | the original proposals |
| `ISSUE-upstream-projectCreated.md` | a bug report for the upstream project, ready to file |

Read `CLAUDE-NOTES.md` and `HANDOVER-Zeiterfassung.md` **first** if you continue this work. The
traps recorded there were paid for dearly: swallowed database errors, custom properties without
their database column, tests that rebuild an ordering instead of calling it.

---

## Finding the changes against the original

The fork is deliberately kept mergeable — no upstream text is touched, and no upstream file is
changed without need.

```bash
grep -rn "Fork-Aenderung" --include=*.kt --include=*.java .   # changes to existing files
grep -rln "NEUE DATEI DIESES FORKS" .                          # new files
```

**Branches:** `master` mirrors upstream plus the notes, `effort-driven` carries the scheduling
work, `zeiterfassung` is the current working branch.

---

## Two findings that belong upstream

Two defects turned up in the **original** while building this, not in the new work:

1. **`projectCreated` was never handled.** After *Project → New*, every change to custom columns
   was silently discarded — no error, no log entry, no column. This affects any custom column, not
   just the ones this fork creates. Written up in `ISSUE-upstream-projectCreated.md`.
2. **`DOUBLE` was mapped to `numeric`.** In H2, `NUMERIC` without a scale has **zero** decimal
   places, so 12.5 came back as 13.0. This affects any custom decimal column.

---

## License and provenance

GanttProject is free software under the **GNU General Public License v3**, and so is this fork.
All credit for the original belongs to
[BarD Software s.r.o. and the GanttProject contributors](https://github.com/bardsoftware/ganttproject).

Upstream's own README text is kept unchanged in [`README`](README); more about the original at
[ganttproject.biz](https://www.ganttproject.biz).
