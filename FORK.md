# About this fork

This is an unofficial fork of [GanttProject](https://github.com/bardsoftware/ganttproject).
It adds effort-driven scheduling: instead of entering how many days a task takes, you
enter how many hours of work it needs, and the duration follows from who is assigned
and how much time they actually have.

Everything upstream does still works. Projects saved here open in the original.

The base is upstream `08564e556` (25 August 2026).

---

## What it adds

**Effort-driven duration.** Enter an effort in hours on a task. Assign someone. The
duration is derived from their available hours per day, their utilisation percentage
and the share of their time allocated to that task. Change the assignment and the
duration follows.

**Days off lengthen the task.** A holiday of an assigned person takes that person's hours
out of the day but does not remove the day. Five days of work with one day off in the
middle is six days long — see below.

**Two axes on an assignment.** Two check boxes per assigned person in the *Resources*
tab: *Absence blocks* ("the task cannot run while this person is away") and *No effort*
("this person is on the task but contributes no working hours"). They are independent,
and both default to off, which is what the program did before they existed.

**Capacity levelling.** Where several tasks compete for the same person on the same
days, the levelling pass spreads them out until nobody is booked beyond their
capacity. It reports what it intends to move before moving it, and it says so when a
task has no possible date at all rather than silently laying it on its earliest one.

**Recurring tasks.** Tasks that come back on a schedule — weekly, monthly, a fixed
number of repetitions — generated as real tasks in the plan rather than as a note.

**Estimate quality.** Compare planned effort against recorded effort across the
project, to find out where estimates are systematically off.

**Four comparison views on the chart.** The band under a task bar answers "am I on
schedule", "did this take more hours than I thought", "does the work still take as long
as it was planned to", or the first and third at once. A dropdown in the chart toolbar
picks one. Four views rather than one, because in this fork a duration is a computed
value — see below.

**Named views for large plans.** Several named sets of hidden tasks live side by side in
one project, are switchable and are saved with it. Hiding a summary task removes it from
the task table and from the chart together with its whole subtree. Hiding is display
only: the scheduler, the effort algorithm and the levelling go on seeing the whole plan.

**Toggl import.** Pull tracked time from Toggl and attribute it to tasks. Optional;
recorded hours can equally be typed in by hand, and the estimate quality report works
either way.

**Fixes to the original.** Defects found while building the above, in areas ranging
from WebDAV storage to dialog rendering. These live in separate branches (see below)
and are being reported upstream individually. Several have since been fixed upstream
independently; where that is the case this fork no longer carries them.

---

## Branch layout

The work is split so that each part can be taken, reviewed or ignored on its own.
Every branch builds and passes its tests independently — this is verified, not
assumed: introducing a deliberate cross-reference between sibling branches makes the
build fail.

```
upstream 08564e556
 └─ fork-base            shared infrastructure + three fixes to the original
     ├─ effort-planning  effort, levelling, recurrence, estimate quality
     │   ├─ toggl-import         Toggl integration
     │   ├─ chart-comparison     the four comparison views
     │   ├─ ansichten            named views
     │   ├─ basisplan-ergaenzen  appending a task to an existing baseline
     │   └─ resource-days-off    days off and the assignment axes — chain below
     ├─ webdav-fixes     eleven WebDAV fixes
     └─ misc-fixes       five UI fixes
```

`main` carries the full set. If you only want part of it, take the branch you need.

`webdav-fixes` and `misc-fixes` contain no feature work at all — only corrections to
existing behaviour. They are the ones most likely to be useful to someone who does not
want the planning changes.

**The newest work is not carved into standalone slices.** Days off, the two assignment
axes and the levelling messages were built as a chain of small branches, each sitting on
the one before it:

```
resource-days-off
 ├─ levelling-daysoff
 └─ assignment-axes
     ├─ axes-effort
     └─ axes-blocking
         ├─ p4-kompatibilitaet
         └─ p5-schnittmenge-meldung → p6-kapazitaetsmeldung
                                    → p7-gemeinsame-meldung
                                    → f25-in-die-kette

kette-integriert   merges axes-effort, p4-kompatibilitaet and f25-in-die-kette
 └─ f29-in-die-kette
     └─ verteilung-ereignisse
```

Every one of them is an ancestor of `main`, and none of them is a slice worth taking on
its own. For this part, take `main`.

---

## How the duration is calculated

There are two passes, and they answer the same question with different amounts of
detail. Both only ever touch **leaf** tasks that carry an effort value.

**Pass one, before the scheduler.** For each leaf task with an effort value:

```
available per day = Σ over all assignments NOT marked "No effort":
                    hours_per_day × (utilisation% / 100) × (assignment load% / 100)

duration in days  = max(1, ceil(effort / available per day))
```

Multiple resources add up: two people at four hours a day give eight hours a day.
Rounding is always upward, and the minimum duration is one day. Where a resource carries
an `hours_schedule` — a daily rate that changes over time — this pass walks the days
instead of dividing, so a task that runs across a changeover is computed with the old
rate before it and the new one after it. On a faulty schedule text the fixed number stays
in force; the two levelling menu items are where that error is reported.

**Pass two, inside the scheduler.** Where the scheduler places a task, the duration is
derived again, this time day by day, and a **day off of an assigned person contributes
zero hours to that day without removing the day**. Five days of work with one holiday in
the middle is six days long. This pass honours *No effort* as well: such an assignment is
dropped whole, which takes that person's days off out of the walk with it — somebody who
contributes no hours cannot have hours taken away by a holiday.

Pass two runs later and therefore wins for every task the scheduler touches. It is
deliberately silent whenever anything is missing — no resource manager, no effort, nobody
assigned, or an effort that cannot be worked off within 10 000 working days. In each of
those cases the task keeps the duration it has: this pass adds information, it never
takes any away.

When a project is **opened**, a duration corrected this way is listed in the original's
own *Scheduler report* dialog under "Duration changed". During ordinary work no dialog
appears.

Summary tasks are not touched — they derive their duration from their children, as
in the original.

**Missing values are handled deliberately, not defensively:**

- No effort on a task → the duration is left alone. The feature is opt-in per task.
- No assignment → availability is zero, and the task is skipped rather than given an
  infinite duration.
- No hours per day on a resource → 8.0 is assumed.
- Every assignment marked *No effort* → the same answer as "nobody assigned": the task
  keeps its duration. A task attended only by onlookers is not a task of zero length.

Recalculation is triggered by assignment changes, resource changes and resource
removal. It deliberately does **not** trigger on resource addition, structural
changes or a model reset — a newly added resource is not yet assigned to anything.
Pass one runs before the scheduler, so that the scheduler can propagate the new dates.

---

## The two axes of an assignment

Two check box columns in the *Resources* tab of the task properties dialog, one per
assigned person. Both are negative-by-default on purpose: unticked is what the program
has always done, so an untouched box and a file written before this fork mean the same
thing.

| Column | Ticked means | Read by |
|---|---|---|
| **Absence blocks** | the task cannot run while this person is away | the levelling pass |
| **No effort** | this person contributes no working hours to the effort | both duration passes |

They are independent. Someone can block without contributing — the person who has to be
present at an acceptance but does no work — and contribute without blocking. The blocking
axis is read separately from the loads, so that an assignment at load 0 % still moves the
task; folding the two together would drop exactly the case the axis exists for.

Stored as the XML attributes `blocking` and `no-effort` on `<allocation>`. **They are not
custom properties**, and they do not survive a round trip through the original — see
*Status and limits*.

---

## Four comparison views

A derived duration has a consequence that is easy to miss: **changing a person's hours
per day changes every date in the plan, without any of the work having changed.**
Measured on a real plan on 20 August 2026 — capacity set from 8.0 to 1.8 hours a day,
and 163 of 163 tasks carrying an effort grew by the factor 8.0/1.8 = 4.44. A baseline
taken before that point then coloured 194 of 276 rows red. Correctly computed, and
saying nothing: it compared two capacity assumptions, not two plans.

So the band under the task bar answers one of these questions, and you choose which:

| View | Compares | Needs a baseline |
|---|---|---|
| **Compare: dates** | end date now against end date in the baseline | yes |
| **Compare: effort** | recorded hours against the *original* estimate | no |
| **Compare: durations** | length now against length in the baseline, shift left out | for an answer |
| **Compare: dates over durations** | both of the above, in one band split in two | for an answer |

```
Dates                          Effort
  same end        → no band      no original estimate       → no band
  ends later      → red          estimate, nothing recorded → grey
  ends earlier    → green        recorded == original       → no band
                                 recorded >  original       → red
                                 recorded <  original       → green

Durations                      Dates over durations
  no baseline     → grey         no baseline → grey, both halves
  same length     → no band      upper half: the Dates rule, at the planned start
  longer          → red          lower half: the Durations rule, at today's start
  shorter         → green        a half with nothing to say is not drawn at all
```

The **anchors differ on purpose**. The dates band hangs off the planned start, so its
overhang shows the whole displacement between plan and reality. The durations band hangs
off today's start, flush with the bar, so its overhang is exclusively the difference in
length. A task that starts a week later but still runs five days shows no deviation under
*durations* and a red band under *dates*; that is not a contradiction but two views
answering different questions. In the combined view each half keeps its own anchor, which
is why it is a fourth view and not a replacement for the other two.

**Lilac marks the date axis** wherever it speaks — in the dates view and in the upper
half of the combined one. The comparison colour is hatched over it. The duration and
effort bands stay plain, because a shift plays no part in what they say.

The dropdown sits in the chart toolbar next to *Baselines…* and always names the view
currently on screen. Which view the **program starts with** is a setting: *Settings →
Gantt chart → Grid details → Comparison at startup*, persisted in `~/.ganttproject` as
`ganttChartGridDetails.comparisonAtStartup`. It ships as `DATES`, so a project that knows
nothing about the effort columns behaves exactly like the original. Switching the view in
the toolbar deliberately does **not** write back to the setting: it changes the session,
not what the program comes up with.

**The effort view needs no baseline** because both of its numbers are already on the
task: `effort_actual_hours` and `effort_original_hours`. The latter is written once and
never touched again, which makes it a better anchor than a baseline — it survives
saving a second baseline. Grey there means "nothing booked yet", which is deliberately
not the same as "on target".

**The dates view compares end dates**, which is the original's rule. Between 17 and 20
August 2026 this fork compared *durations* instead of dates, to stop a merely-shifted
task from turning red. That was one display trying to answer both questions; the
measurement above is why it was withdrawn. Since 25 August the duration comparison is
back as a view of its own, standing beside the date one rather than replacing it. Each
question has its own view.

**A summary task keeps the date axis and loses the duration one** in the combined view.
Its band is three pixels high and cannot be split, and for a package of work the question
that matters is whether it is on schedule.

---

## Named views

A view is a **named set of hidden tasks**, saved with the project. Several can exist side
by side and one is active at a time.

The set holds what is **hidden**, not what is shown. A task created after a view was
saved therefore stays visible in every view, instead of falling out of all of them at
once without a word.

A view is a **second condition next to the task filter**, not instead of it. There is
only one active filter, so a view built as a filter would switch off "hide completed" the
moment somebody picked a view.

A **views button** in the toolbar above the task table opens the list of views, plus
*Show everything* and *Manage views …*. Tasks go into and out of the active view through
the task table's own context menu (*Hide in this view* / *Show again in this view*) — a
view could otherwise be switched on but never filled.

Beside that button, when anything is hidden, stands a **clickable** line — "12 tasks are
hidden - show everything". Clicking it clears the view *and* the task filter, both:
the counter does not say which of the two hid what, so a way back that cleared only one
would leave you pressing without effect. The stock text in that place is a label that
cannot be clicked.

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

**The two assignment axes are not in this table** and are not custom properties. They are
attributes on `<allocation>`, and they behave differently on a round trip — see
*Status and limits*.

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

896 automated tests, all green — measured on a Linux VM on 29 August 2026, summed out of
the 161 JUnit XML reports of a single `--continue` run: `biz.ganttproject.core` 20,
`biz.ganttproject.impex.ical` 1, `ganttproject` 320, `ganttproject-tester` 555; 0
failures, 0 errors, 0 skipped. The figure of 732 that stood here was measured on 20
August 2026 and is long out of date.

Note what that number does and does not cover: the five UI fixes in `misc-fixes` have no
automated test and never had one — they were verified on screen, on Windows. A sixth fix
used to sit there, for an application that would not exit; **upstream has since fixed
that itself** (`c6b965ad1`), and this fork no longer carries any change to that file. The
baseline colouring that `misc-fixes` still carries has been **replaced on `main`** by the
four comparison views described above, and those do have tests.

The comparison views were checked on screen as well as in tests, each colour read out of
a screenshot rather than judged by eye: ten cases when there were two views, and seven
more for the combined one on 26 August 2026. The startup setting was observed the same
way on 25 August — set, quit, `~/.ganttproject` inspected, restarted.

Written and used for real planning, and every feature has been exercised. It has not
been in long-term use, and it has been used by one person on one kind of plan. Take
the following as specific rather than as a general disclaimer.

**It writes to your project file.** Opening a project creates the custom properties
above, and entering an effort rewrites the task's duration. **Make a copy before
trying it on anything you care about.**

**A trip through the original silently erases both assignment axes.** Measured on
27 August 2026 against a GanttProject built from the upstream commit this fork sits on:
all four combinations of `blocking` and `no-effort` were opened in it and saved again. It
starts, opens the file and says nothing — no error, no warning. Task, person, load and
coordinator arrive unchanged. **But on saving, both attributes are gone**, because the
original reads `<allocation>` into a record that has no field for them and writes its own
fixed list of five attributes back out. Nobody is told. What comes back is a correct
project that has forgotten two answers: everything reverts to off, which is the behaviour
before the axes existed. The two files that measurement produced are checked in beside
the test that pins it.

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
Measured in August 2026 and not re-measured since.

Possibly worse than a dialog, and marked uncertain because it was seen once: after
that error appeared, the original's window stopped accepting input altogether — the
Project menu did not open, double-clicking a task opened no dialog, and dragging a
bar moved nothing. The project was fully loaded and drawn. Whether this follows from
the error or is unrelated was not established. If you meet it, restart before
concluding anything about the file.

**The task properties dialog used to drop allocations and effort while a fork column was
empty.** Pressing Ok raised "Something went wrong", and everything from the custom
columns onwards was abandoned — the column values, the predecessors, and the whole
Resources tab including effort. The cause was in the original: `PropertyTypeEncoder`
decoded an empty date or number to `null`, and `CustomColumnsValues.addCustomProperty`
then unwrapped that with `!!`. **Upstream has fixed it** — `101b382f3`, issue #2817,
which is part of the commit this fork is based on. `addCustomProperty` now stores `null`
and returns `null` instead of throwing. That was read in the code on 29 August 2026;
**the dialog itself has not been re-tried on screen since the fix**, so if you still meet
the symptom, it is a new one and worth reporting.

**Enter effort in the effort fields, not in the custom columns tab.** The dialog offers
effort and actual hours in two places: this fork's own *Effort* section on the
*Resources* tab, and the generic custom-columns tab. Only the first one sticks. The two
effort fields are applied *inside* the custom-columns commit, after that tab has written
its own rows into the holder — so a value in the effort field wins, and an **empty**
effort field clears the property the tab has just written. That order is deliberate:
writing anywhere else would let the tab overwrite the fields with the copy it took when
the dialog was opened. The task table columns are a third route and work as well. Read in
the code, not measured on screen.

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

**The baseline legend no longer names a quantity.** The three lines in the baseline
dialog come from the original and used to talk about a task's *end* ("Task remains on
schedule"). That fits the dates view and nothing else, and the dialog does not know which
view is showing. The fork therefore redirects all three labels to its own texts — "One of
the two values is missing", "Below the planned value", "Above the planned value" — which
hold in all four views, because what stays constant across them is the *direction*, not
the quantity. An earlier version of this file said the legend could not be overridden
from the fork's own texts; that was true when it was written and is **no longer true**:
`GPOptionGroup.setI18Nkey` is the original's own mechanism for exactly this, and a test
pins the result in both languages.

**The legend's colour swatches do not match the hatched bands.** They are drawn solid,
and wherever the date axis speaks the band on the chart is the comparison colour hatched
over lilac. `createColorComponent` takes a `Color` and not a `Paint`, and it serves every
colour option in the program, so this is not a one-line change. The colours are still the
right ones; only their appearance in the legend is not.

**Hatching costs colour separation, and the amount is known.** A pattern with coverage
*d* stretches every colour separation to the factor *d*, whatever the ground underneath.
The one used here covers exactly half, so the distances between red, green and grey fall
from 253 / 204 / 204 units to 126 / 101 / 102. The weakest case is grey on lilac: 47
units from the unhatched ground, against 99 for red and 114 for green. Measured on
26 August 2026. Changing the lilac changes none of this — more separation would need more
coverage, not another shade.

**Appending a task to an existing baseline has no user interface.** A task created after
a baseline was taken has no entry in it and draws no band at all. The mechanism to add
one exists and is tested, but nothing in the program calls it yet: whether a supplemented
baseline should replace the old one or stand beside it under a new name is an open
question, and the code deliberately does not answer it. Until it does, this is reachable
from code only.

**A levelling run reports that it has ended, and nobody is listening.** The notification
exists so that an "out of date" mark could be cleared at the right moment; the mark, the
display and the status line are not built. Plumbing with no visible effect today.

**The levelling message is measured on its text, not through the menu item.** The block
that names tasks and people was pulled out into a function so that it could be tested at
all; that the menu item really calls it, and really hands it the model's names, is read
in the code and asserted nowhere. The other blocks of the levelling preview have no test.

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
encryption itself is only ever proven by a Windows run. The `skipped=0` in the run
reported above is this effect, not evidence that nothing was skipped.

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
reported upstream — several found here already have been, and several have since been
fixed there.
