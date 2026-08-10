# Custom column values are silently lost after "Project → New" (no error, project becomes unusable)

## Summary

After creating a new project via **Project → New**, the in-memory H2 mirror of the project
never receives columns for custom properties again. Every change to the custom property
definitions is silently discarded — no exception, no log entry.

The first attempt to store a value of a custom property then fails with
`Column "<id>" not found`, the transaction is rolled back, and from that point on **every**
further task write fails too, so no new task can be created until GanttProject is restarted.

The failure is silent to the user until it is too late: `MutatorImpl.commit()` catches
`ProjectDatabaseException` and only logs it, so the UI carries on as if the edit had worked.

## Version

GanttProject 3.4.3394 (built from source, `master`), Windows 10, BellSoft Liberica Full JDK 21.

## Steps to reproduce

1. Start GanttProject.
2. **Project → New**, click through the wizard with the defaults.
3. Create a task.
4. Open the task properties → tab *Custom columns* → **Manage columns…** → add a new custom
   column (e.g. text column `Note`).
5. Enter any value for that column on the task and press **OK**.
6. Try to create another task.

### Expected

The value is stored; further tasks can be created.

### Actual

- Step 5: no visible error dialog, only the small red error indicator in the status bar.
- Step 6: error dialog "Something went wrong", and no task is created.
- `ganttproject.log`:

```
ERROR ProjectDatabase - Failed to execute or log txnId=-1
 UPDATE Task SET tpc0='...' WHERE uid='...';
...
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Column "tpc0" not found; SQL statement:
UPDATE Task SET tpc0='...' WHERE uid='...' [42122-232]
```

Restarting GanttProject and opening a *saved* project works fine — the problem only appears
after **Project → New** within a running instance.

## Cause

`ProjectUIFacadeImpl.createProject()` closes the old project and then announces the new one:

```kotlin
override fun createProject(project: IGanttProject) {
  ensureProjectSaved(project).await { result ->
    if (result) {
      createNewProject(project, myWorkbenchFacade).await { projectData ->
        project.close()                    // (1)
        ...
        projectImpl.fireProjectCreated()   // (2)
```

* (1) `project.close()` → `fireProjectClosed()` → `ProjectEventListenerImpl.projectClosed()`
  sets `isProjectOpen = false`.
* (2) `fireProjectCreated()` → `ProjectEventListener.projectCreated()`.

`ProjectEventListenerImpl` does **not** override `projectCreated()`, and
`ProjectEventListener.Stub` inherits it as an empty method. So `isProjectOpen` stays `false`
for the whole lifetime of the new project.

`LazyProjectDatabaseProxy` guards on exactly that flag:

```kotlin
override fun onCustomColumnChange(customPropertyManager: CustomPropertyManager) {
  if (isProjectOpen) {
    getDatabase().onCustomColumnChange(customPropertyManager)
  }
}
```

With the flag `false`, the `ALTER TABLE Task ADD COLUMN …` statements produced by
`SqlCustomPropertyStorageManager` are never executed, while the SQL builder
(`createUpdateCustomValuesStatement`) keeps generating `UPDATE Task SET <id>=…` for every
defined custom property. The two views of the schema drift apart, and every subsequent write
of custom properties fails.

The flag is only set back to `true` in `initProjectDatabase()`, which runs from
`whenTablesInitialized()` — i.e. only when a project is *opened*, not when one is *created*.

## Suggested fix

Handle `projectCreated` the same way `projectRestoring` is already handled, in
`ProjectEventListenerImpl`:

```kotlin
override fun projectCreated() = withLogger({ "Failed to initialize the database for a new project" }) {
  projectDatabase.shutdown()
  initProjectDatabase()
}
```

This drops the stale mirror of the previous project and rebuilds it for the new one, which also
removes the rows of the closed project that currently stay behind in the `Task` table.

## Verification

Reproduced and fixed in a fork, with a regression test that drives the real wiring
(`LazyProjectDatabaseProxy` plus the custom property listener that `GanttProjectBase`
registers) and asserts on the **stored value**, not on an exception:

```kotlin
projectListener.projectClosed()
projectListener.projectCreated()          // what "Project → New" does

val task = taskManager.newTaskBuilder().withName("t").build()
projectDatabase.insertTask(task)
val def = customPropertyManager.createDefinition(CustomPropertyClass.TEXT, "Note", null)
val edited = task.customValues.copyOf().also { it.setValue(def, "hello") }
task.createMutator().also { it.setCustomProperties(edited) }.commit()

assertEquals("hello", readColumnFromH2(def.id))
```

Without the `projectCreated` override this test fails with `Column "tpc0" not found`; with it,
it passes. Checking the stored value rather than catching an exception matters here, because
`MutatorImpl.commit()` swallows `ProjectDatabaseException`.

## Side note, possibly worth a separate issue

`MutatorImpl.commit()` (`TaskImpl.kt`) catches `ProjectDatabaseException` and only logs it:

```kotlin
if (taskUpdateBuilder != null) {
  try {
    taskUpdateBuilder.commit()
  } catch (e: ProjectDatabaseException) {
    GPLogger.log(e)
  }
}
```

This is what turns the defect above from an immediate, visible failure into silent data loss:
the dialog closes as if everything had been saved. It also makes such problems hard to catch in
tests, since a test that only checks for an exception passes even when nothing was written.
