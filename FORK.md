# About this fork

This is an unofficial fork of [GanttProject](https://github.com/bardsoftware/ganttproject).
It adds effort-driven scheduling: instead of entering how many days a task takes, you
enter how many hours of work it needs, and the duration follows from who is assigned
and how much time they actually have.

Everything upstream does still works. Projects saved here open in the original.

---

## What it adds

**Effort-driven duration.** Enter an effort in hours on a task. Assign someone. The
duration is derived from their available hours per day, their utilisation percentage
and the share of their time allocated to that task. Change the assignment and the
duration follows.

**Capacity levelling.** Where several tasks compete for the same person on the same
days, the levelling pass spreads them out until nobody is booked beyond their
capacity. It reports what it intends to move before moving it.

**Recurring tasks.** Tasks that come back on a schedule — weekly, monthly, a fixed
number of repetitions — generated as real tasks in the plan rather than as a note.

**Estimate quality.** Compare planned effort against recorded effort across the
project, to find out where estimates are systematically off.

**Two comparison views on the chart.** The band under a task bar answers either "am I
on schedule" (against a baseline) or "did this take more hours than I thought"
(against the original estimate). One button switches between them. Two views rather
than one, because in this fork a duration is a computed value — see below.

**Toggl import.** Pull tracked time from Toggl and attribute it to tasks. Optional;
recorded hours can equally be typed in by hand, and the estimate quality report works
either way.

**Fixes to the original.** Defects found while building the above, in areas ranging
from WebDAV storage to dialog rendering. These live in separate branches (see below)
and are being reported upstream individually.

---

## Branch layout

The work is split so that each part can be taken, reviewed or ignored on its own.
Every branch builds and passes its tests independently — this is verified, not
assumed: introducing a deliberate cross-reference between sibling branches makes the
build fail.

```
upstream/master
 └─ fork-base            shared infrastructure + three fixes to the original
     ├─ effort-planning  effort, levelling, recurrence, estimate quality
     │   └─ toggl-import Toggl integration
     ├─ webdav-fixes     eleven WebDAV fixes
     └─ misc-fixes       six UI fixes
```

`main` carries the full set. If you only want part of it, take the branch you need.

`webdav-fixes` and `misc-fixes` contain no feature work at all — only corrections to
existing behaviour. They are the ones most likely to be useful to someone who does not
want the planning changes.

---

## How the duration is calculated

For each leaf task with an effort value:

```
available per day = Σ over all assignments:
                    hours_per_day × (utilisation% / 100) × (assignment load% / 100)

duration in days  = max(1, ceil(effort / available per day))
```

Multiple resources add up: two people at four hours a day give eight hours a day.
Rounding is always upward, and the minimum duration is one day.

Summary tasks are not touched — they derive their duration from their children, as
in the original.

**Missing values are handled deliberately, not defensively:**

- No effort on a task → the duration is left alone. The feature is opt-in per task.
- No assignment → availability is zero, and the task is skipped rather than given an
  infinite duration.
- No hours per day on a resource → 8.0 is assumed.

Recalculation is triggered by assignment changes, resource changes and resource
removal. It deliberately does **not** trigger on resource addition, structural
changes or a model reset — a newly added resource is not yet assigned to anything.
The pass runs before the scheduler, so that the scheduler can propagate the new dates.

---

## Two comparison views

A derived duration has a consequence that is easy to miss: **changing a person's hours
per day changes every date in the plan, without any of the work having changed.**
Measured on a real plan on 20 August 2026 — capacity set from 8.0 to 1.8 hours a day,
and 163 of 163 tasks carrying an effort grew by the factor 8.0/1.8 = 4.44. A baseline
taken before that point then coloured 194 of 276 rows red. Correctly computed, and
saying nothing: it compared two capacity assumptions, not two plans.

So the band under the task bar answers one of two questions, and you choose which:

| View | Compares | Needs a baseline |
|---|---|---|
| **Dates** | end date now against end date in the baseline | yes |
| **Effort** | recorded hours against the *original* estimate | no |

```
Dates                                Effort
  same end          → no band          no original estimate  → no band
  ends later        → red              estimate, nothing recorded → grey
  ends earlier      → green            recorded == original  → no band
                                       recorded >  original  → red
                                       recorded <  original  → green
```

The toggle sits in the chart toolbar next to *Baselines…* and is labelled with the
view currently shown, not the one it leads to. Dates is the default, so a project that
knows nothing about the effort columns behaves exactly like the original.

**The effort view needs no baseline** because both of its numbers are already on the
task: `effort_actual_hours` and `effort_original_hours`. The latter is written once and
never touched again, which makes it a better anchor than a baseline — it survives
saving a second baseline. Grey there means "nothing booked yet", which is deliberately
not the same as "on target".

**The dates view compares end dates**, which is the original's rule. Between 17 and 20
August 2026 this fork compared *durations* instead, to stop a merely-shifted task from
turning red. That was one display trying to answer both questions; the measurement
above is why it was withdrawn. Each question now has its own view.

---

## Custom properties

Eleven properties in total, stored in the project file like any other custom column.
A project therefore remains readable in the original GanttProject — the columns simply
appear as ordinary user-defined columns there.

| ID | On | Type | Created |
|---|---|---|---|
| `effort_hours` | Task | Double | on first value |
| `effort_actual_hours` | Task | Double | on first value |
| `effort_original_hours` | Task | Double | on open / new |
| `deadline` | Task | Date | on open / new |
| `wait_only` | Task | Boolean | on open / new |
| `date_fixed` | Task | Boolean | on open / new |
| `recurrence` | Task | Text | on open / new |
| `recurrence_of` | Task | Text | on open / new |
| `hours_per_day` | Resource | Double | on open / new |
| `hours_schedule` | Resource | Text | on open / new |
| `utilisation_percent` | Resource | Integer | on open / new |

Nine are created when a project is opened or created. `effort_hours` and
`effort_actual_hours` are created only when a value is actually entered, so that
projects which do not use the feature do not silently gain a column. The effort fields
sit in the task properties dialog and are there regardless.

All default to null. `DEFAULT_HOURS_PER_DAY` is 8.0.

**`utilisation_percent` is an integer, and clamped.** Values outside 1–100 fall back
to 100 without a message. A resource entered at 0 % is therefore treated as fully
available, and 120 % silently becomes 100 %. Fractional utilisation such as 62.5 %
cannot be entered.

---

## Toggl integration

One endpoint: `https://api.track.toggl.com/api/v9`, specifically `/me/time_entries`
with a from/to range. The 90-day ceiling is Toggl's, not a choice made here — larger
ranges return 400 (measured against the service: 30 days works, 99 and 300 do not).

Entries are attributed to tasks in three stages:

1. An explicit task number written as `#123` in the entry text or as a tag. This
   decides on its own.
2. A learned keyword.
3. Word overlap between the entry text and the task name, words of four characters
   or more.

**Only stage 1 applies automatically.** Stages 2 and 3 produce suggestions; a person
picks. A per-task ledger maps Toggl entry IDs to hours already booked, merged across
the project, so a second import adds only the difference rather than doubling
everything.

---

## Status and limits

732 automated tests, all green — measured on a Linux VM on 20 August 2026. The figure
of 717 that stood here was already out of date before the comparison views were added;
the count on `main` at that point was 719.

Note what that number does and does not cover: the six UI fixes in `misc-fixes` have no
automated test and never had one — they were verified on screen. One of them (the
application not exiting) is reproduced and fixed on both Windows and Linux; the other
four were observed on Windows. The sixth, the baseline colouring, **has since been
replaced** by the two comparison views described above, and those do have tests.

The comparison views themselves were checked on screen as well as in tests: ten cases
across both views, each colour read out of the screenshot rather than judged by eye.

Written and used for real planning, and every feature has been exercised. It has not
been in long-term use, and it has been used by one person on one kind of plan. Take
the following as specific rather than as a general disclaimer.

**It writes to your project file.** Opening a project creates the custom properties
above, and entering an effort rewrites the task's duration. **Make a copy before
trying it on anything you care about.**

**Opening a fork project in the original reports an error** as soon as any task has a
value in the `deadline` column:

```
Error when opening a project document
The document was loaded successfully, but something went wrong when updating
calculated properties and filters.
The reported error is: Type class java.util.GregorianCalendar is not supported
in dialect DEFAULT
```

The project still loads, and tasks, values and columns survive intact — a file
comparison after a round trip through the original showed two column widths as the
only difference. What is lost is calculated properties and filters for that session.
This is a defect in the original, not in the fork, and has been reported upstream.

Possibly worse than a dialog, and marked uncertain because it was seen once: after
that error appeared, the original's window stopped accepting input altogether — the
Project menu did not open, double-clicking a task opened no dialog, and dragging a
bar moved nothing. The project was fully loaded and drawn. Whether this follows from
the error or is unrelated was not established. If you meet it, restart before
concluding anything about the file.

**The task properties dialog drops allocations and effort while a fork column is
empty.** Pressing Ok raises "Something went wrong", and everything from the custom
columns onwards is abandoned — the column values, the predecessors, and the whole
Resources tab including effort. What you changed on the General tab does survive: the
controller saves the panels in order, and that one runs before the failure. The cause
is in the original: `PropertyTypeEncoder` decodes an empty date or number to `null`,
and `CustomColumnsValues.addCustomProperty` then unwraps that with `!!`. It was
reproduced by hand on the original `e523bedc6` — add a custom task column of type date,
leave it empty, press Ok — with an identical stack trace, so it is not something the
fork changed. What the fork changes is how often you meet it: it ships six custom task
columns, and `deadline` plus the three effort columns are exactly the types that fail,
so the first Ok in a fresh project hits it. Until this is fixed, **enter effort through
the task table columns rather than through the dialog**. That path works and
recalculates the duration correctly — measured, not assumed. Giving every empty date
and number column a value on the task itself also makes the dialog save again; setting
a *default* on the column definition does not, which was likewise tested.

**Enter effort in the effort fields, not in the custom columns tab.** The dialog
offers `Aufwand (Std.)` and `Ist-Aufwand (Std.)` in two places: this fork's own
*Effort* section on the *Resources* tab, and the generic custom-columns tab. Only
the first one sticks. The controller commits the resources panel *after* the custom
columns, so an empty effort field there overwrites whatever the custom columns tab
just wrote — measured on a task where `deadline` and `effort_original_hours` from
that tab survived and `effort_hours` did not. The task table columns work as well
and are the safest route while the dialog is affected by the defect above.

**Importing does not create this fork's columns.** They are created in two places
only: when a project file is opened, and by *Project > New*. None of the import paths
goes through either. What the imports for `.gan`, CSV and MS Project do — and pasting
from the clipboard with them — is carry the column *definitions* over from the source
project, so a file that already has the columns keeps them. A source without them
leaves you with a project that has none, and effort has nowhere to go until you add
them by hand through *Manage columns*. The text importer is worse still: it writes
straight into the target project without a buffer, so nothing is carried at all. The
calendar importer creates no tasks and is unaffected. Read in the code, not measured
on screen.

**The baseline legend only fits one of the two views.** The three lines in the
baseline dialog come from the original and talk about a task's *end* ("Task remains on
schedule", "Task completes earlier than before"). In the dates view that is now exactly
right. In the effort view it is not: there the colours are about hours, not about
finishing. What the toggle in the toolbar shows is the authority on which view you are
looking at. The legend text lives in the original's options framework and cannot be
overridden from the fork's own texts; this was tested, not assumed.

**Dialogs may open with a black button bar.** Reopening the same dialog — the new
project wizard, for instance — sometimes leaves the button strip unpainted until the
window is moved. The content area is fixed in this fork; the button bar is not. The
cause is in the original and is not yet understood: the background colour is assigned
on every dialog build, and the strip stays black anyway.

**Token storage only protects you on Windows.** The Toggl token is encrypted with
Windows DPAPI. On other systems it is not stored at all — deliberately, rather than
falling back to plain text — so you will be asked for it again each session.

**The token encryption tests measure nothing off Windows.** All four methods return
early when DPAPI is unavailable, and JUnit counts an early return as passing, not as
skipped: a green run on Linux contains four tests that measured nothing. The number is
therefore the same on both platforms — what differs is what is behind it. The
encryption itself is only ever proven by a Windows run.

**`assumeTrue` was tried here and reverted.** Between 19 and 20 August 2026 the four
methods started with `assumeTrue` instead, so that a run off Windows would count them
as skipped rather than passed. Measured on a Linux VM on 20 August 2026, it does the
opposite: `tests=4 failures=4 errors=0 skipped=0` — four red tests, and `BUILD FAILED`
on every non-Windows machine. The class extends `junit.framework.TestCase`, so the
vintage engine runs it through `JUnit38ClassRunner`, which reports any thrown exception
as an error; the special handling for `AssumptionViolatedException` lives in the JUnit 4
runner, which a JUnit 3 `TestCase` never reaches. On Windows the assumption never fires,
which is why the change went out unverified. Counting these four as skipped would mean
rewriting the class as a JUnit 4 or Jupiter test — until then the early return stays.

**Baselines only record id, start, duration and milestone flag.** They cannot answer
"did this take more work than planned" — that is what the estimate quality report and
the chart's effort view are for; both read the effort columns on the task instead. A
task created *after* a baseline has no entry in it and therefore draws no band at all,
which looks exactly like a task that has not moved. Note also that saving a second baseline does not replace the first: running the
levelling twice and saying yes to "save a baseline first" both times leaves you with
two baselines of the *already levelled* state and no record of what it looked like
before.

**Recurring tasks need exactly one limit** — an end date or a repetition count, never
both.

**WebDAV write behaviour is changed.** This fork sends conditional requests and
acquires locks where the original did neither. If you use it against a shared server,
the people you share with should know.

---

## Building

You need a **JDK 21 that ships JavaFX** — Zulu CA-FX or BellSoft Liberica Full, for
example. An ordinary JDK fails early and confusingly:

```
:biz.ganttproject.core:compileKotlin FAILED
e: …/core/option/Validators.kt:23:8 Unresolved reference 'javafx'
```

That module uses JavaFX but does not apply the openjfx plugin, so it relies on the
JDK providing it.

```
git clone <this repository>
cd ganttproject
git submodule update --init --recursive
./gradlew build test
./gradlew run
```

**On headless Linux the test task hangs indefinitely and silently** without an X
display — no error, no timeout. This is an upstream issue, not specific to this fork.
Working invocation:

```
sudo apt-get install -y xvfb libxtst6 libxrender1 libxi6 libxext6 \
                        libgtk-3-0 libgl1 libasound2t64 libfreetype6 fontconfig
xvfb-run -a --server-args="-screen 0 1920x1080x24" ./gradlew build test
```

`libxtst6` is easy to miss — without it the build fails with
`UnsatisfiedLinkError: libXtst.so.6`.

On Windows, set `git config core.autocrlf false` before cloning, or you will see
phantom changes across hundreds of files.

---

## Licence and attribution

GPL-3.0, same as the original. See `COPYING`.

Fork changes: Copyright 2026 Natalie (github.com/Natalie-the-technician).

This is not affiliated with or endorsed by BarD Software. For the original project,
its documentation and its commercial cloud offering, see
[ganttproject.biz](https://www.ganttproject.biz).

Bug reports about *this fork* belong here. Bugs in GanttProject itself are better
reported upstream — several found here already have been.
