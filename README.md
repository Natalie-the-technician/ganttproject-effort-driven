GanttProject
============

GanttProject is a free project management app for desktops. It comes with:

* Task hierarchy and dependencies, milestones, baselines.
* Gantt chart with an option to generate PERT chart.
* Resource load chart.
* Task cost calculation.
* Export to PDF, HTML, PNG.
* Interoperability with MS Project, Excel and other spreadsheet apps.
* Project collaboration using WebDAV and a commercial collaboration service [GanttProject Cloud](https://ganttproject.cloud).

Visit http://ganttproject.biz to learn more.


## Android app — [`android/`](android/)

This fork adds **GanttProject Mobile**, an Android app that reads and edits the
same `.gan` files: view the chart, set progress, record planned and actual
hours, adjust who works how many hours per day, spot overloaded resources, and
import time entries from Toggl Track.

It is a self-contained Gradle project and does not affect the desktop build.
Files are edited in place, so a project can move between phone and desktop
without losing anything the app does not know about.

See [`android/README.md`](android/README.md) for what it does, what it
deliberately does not do, and how to get an APK.

```bash
cd android && ./gradlew :gantt-core:test     # core tests, no Android SDK needed
cd android && ./gradlew :app:assembleDebug   # the app, needs an SDK
```


## License
GanttProject is free and open-source software, distributed under GNU General Public License v3.

## Check out, build and run

Clone the repository using `git clone https://github.com/bardsoftware/ganttproject.git` and checkout the submodules
with `git submodule update` from the repository root.

You can build and run the core part of GanttProject, with no export/import features, using `gradle run`.

If you want to build the complete app, use `gradle runapp` or `gradle distbin && cd ganttproject-builder/dist-bin && ./ganttproject` (on Linux and macOS)