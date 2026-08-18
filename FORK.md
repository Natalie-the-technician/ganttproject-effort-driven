# About this fork

This is an unofficial fork of [GanttProject](https://github.com/bardsoftware/ganttproject).
It adds effort-driven scheduling: instead of entering how many days a task takes, you
enter how many hours of work it needs, and the duration follows from who is assigned
and how much time they actually have.

Everything upstream does still works. Projects saved here open in the original, and
projects from the original open here.

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

**Toggl import.** Pull tracked time from Toggl and attribute it to tasks. Optional;
recorded hours can equally be typed in by hand, and the estimate quality report works
either way.

**Fixes to the original.** Twenty defects found while building the above, in areas
ranging from WebDAV storage to dialog rendering. These live in separate branches (see
below) and are being reported upstream individually.

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

## Custom properties

The fork creates these on first use. They are stored in the project file like any
other custom column, so a project remains readable in the original GanttProject —
the columns simply appear as ordinary user-defined columns there.

| ID | On | Type |
|---|---|---|
| `effort_hours` | Task | Double |
| `effort_actual_hours` | Task | Double |
| `effort_original_hours` | Task | Double |
| `deadline` | Task | Date |
| `wait_only` | Task | Boolean |
| `date_fixed` | Task | Boolean |
| `recurrence` | Task | Text |
| `recurrence_of` | Task | Text |
| `hours_per_day` | Resource | Double |
| `hours_schedule` | Resource | Text |
| `utilisation_percent` | Resource | Double |

All default to null. `DEFAULT_HOURS_PER_DAY` is 8.0.

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

The full branch carries 717 automated tests, all green. Note what that number
does and does not cover: the six UI fixes in `misc-fixes` have no automated
test and never had one — they were verified on screen. Two of them (baseline
colouring, application not exiting) are reproduced and fixed on both Windows
and Linux; the other four were only observed on Windows.

Written and used for real planning, and every feature has been exercised. It has not
been in long-term use, and it has been used by one person on one kind of plan. Take
the following as specific rather than as a general disclaimer:

**It writes to your project file.** Opening a project creates the custom properties
above, and entering an effort rewrites the task's duration. **Make a copy before
trying it on anything you care about.**

**Token storage only protects you on Windows.** The Toggl token is encrypted with
Windows DPAPI. On other systems it is not stored at all — deliberately, rather than
falling back to plain text — so you will be asked for it again each session.

**The token encryption tests do not test anything off Windows.** All four methods
return early when DPAPI is unavailable, and JUnit counts that as passing, not as
skipped. A green test run on Linux includes four tests that measured nothing. This is
documented in the class comment; it is worth knowing before you read the numbers.

**Baselines only record id, start, duration and milestone flag.** They cannot answer
"did this take more work than planned" — that is what the estimate quality report is
for.

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
