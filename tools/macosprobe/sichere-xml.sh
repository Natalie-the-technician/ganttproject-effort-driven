#!/bin/sh
# Legt den Ergebnis-XML-Stand EINES Laufs beiseite, unter seinem Namen.
#
# WARUM: carry.sh packt `test-results` so ein, wie es am Ende dasteht -- also den Stand des
# LETZTEN Laufs. Nach dem ersten Durchgang am 20.09.2026 (Lauf 35521476830) war die volle
# Fehlermeldung der Gegenprobe aus Run 2 deshalb nicht mehr nachlesbar; im Protokoll steht nur
# die erste Zeile. Wer nachrechnen soll, braucht das XML jedes Laufs, nicht das letzte.
set -eu
label="$1"
ziel="$RUNNER_TEMP/ergebnis/xml-$label"
rm -rf "$ziel"
mkdir -p "$ziel"
cp -R ganttproject-tester/build/test-results/test/. "$ziel"/ 2>/dev/null || echo "kein XML fuer $label"
ls "$ziel" | sed "s/^/  xml-$label: /"
