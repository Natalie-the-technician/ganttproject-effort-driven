@echo off
REM NEUE DATEI DIESES FORKS - im Original-GanttProject nicht vorhanden.
REM
REM Startet den frisch gebauten Stand aus dist-bin zum Ausprobieren von Hand.
REM
REM WARUM NICHT EINFACH dist-bin\ganttproject.exe:
REM Die exe erwartet laut ganttproject-launch4j.xml eine mitgelieferte Laufzeit unter .\runtime\.
REM Die legt aber nur die Aufgabe distWin an, distBin nicht. Ohne runtime\ faellt die exe auf die
REM System-Java zurueck, und die ist hier ein Microsoft-JDK OHNE JavaFX. Ergebnis: der Dialog
REM "GanttProject needs Java 21+ with JavaFX modules", noch bevor ein Fenster erscheint.
REM
REM ganttproject.bat dagegen nimmt JAVA_HOME. Diese Datei setzt es auf das JDK MIT JavaFX,
REM dasselbe, mit dem gebaut wird.

SET "JAVA_HOME=C:\Users\ofran\jdks\jdk-21.0.12-full"

IF NOT EXIST "%JAVA_HOME%\bin\java.exe" (
  echo Das JDK mit JavaFX wurde nicht gefunden: %JAVA_HOME%
  echo Pfad in dieser Datei anpassen.
  pause
  exit /b 1
)

REM ganttproject.bat setzt GP_HOME auf ".", muss also aus dist-bin heraus laufen.
SET "DISTBIN=%~dp0..\ganttproject-builder\dist-bin"

IF NOT EXIST "%DISTBIN%\ganttproject.bat" (
  echo dist-bin fehlt oder ist unvollstaendig: %DISTBIN%
  echo Erst bauen:
  echo   gradlew :ganttproject-builder:clean :ganttproject-builder:distBin
  pause
  exit /b 1
)

CD /D "%DISTBIN%"

REM Mit vollem Pfad aufrufen. Ein blosses "CALL ganttproject.bat" schlaegt hier fehl, je nachdem
REM wie der Starter selbst aufgerufen wurde -- gesehen und nachgestellt.
CALL "%DISTBIN%\ganttproject.bat" %*
