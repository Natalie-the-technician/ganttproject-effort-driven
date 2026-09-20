#!/bin/sh
# Pushes whatever has been measured so far onto a branch of its own.
#
# WHY AT ALL: without a GitHub token on the machine at home, the job log and the artifacts are both
# unreachable -- both REST endpoints want authentication even for a public repository. What is
# readable without a token is the per-step conclusion, "green" or "red", and this workflow exists to
# produce counts and test names. So the runner carries its own output out, as a commit.
#
# WHY TWICE, AND NOT ONLY AT THE END: a job that dies on `timeout-minutes` does not run its later
# steps, not even the ones marked `if: always()`. The first run of this workflow, on 20.09.2026, was
# cancelled by hand after two hours in the keychain step -- and everything measured up to that point
# was lost with it. The F10 numbers are therefore sent home as soon as they exist, before F27 is
# even started.
#
# An orphan commit on a branch named after the run and the stage: nothing is merged, nothing is
# overwritten, no force is needed, and a second run cannot collide with a first.
#
# usage: carry.sh <stage-name>   -- with GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID in the
#                                   environment and the files in $RUNNER_TEMP/ergebnis
set -eu

stage="$1"
out="$RUNNER_TEMP/ergebnis"
mkdir -p "$out"

# Best effort, every one of them: a file that is not there means the step that writes it did not get
# that far, which is itself a result and must not stop the rest from travelling.
cp ganttproject-tester/build/secretstore-probe.txt "$out/" 2>/dev/null || true
cp build/secretstore-probe.txt "$out/" 2>/dev/null || true
cp "$RUNNER_TEMP"/handler*.log "$out/" 2>/dev/null || true
# The result XML itself, not only the counts, so that whoever reads the report can recount.
tar czf "$out/test-results.tgz" -C ganttproject-tester/build test-results 2>/dev/null || true
ls -l "$out"

work="$RUNNER_TEMP/carry-$stage"
rm -rf "$work"
mkdir -p "$work"
cp -R "$out"/. "$work"/
cd "$work"
git init -q
git config user.name "github-actions"
git config user.email "github-actions@users.noreply.github.com"
git add -- .
git commit -q -m "macOS probe, stage $stage, run $GITHUB_RUN_ID, commit $GITHUB_SHA"
git push -q "https://x-access-token:$GH_TOKEN@github.com/$GITHUB_REPOSITORY.git" \
  "HEAD:refs/heads/ergebnis/macos-$GITHUB_RUN_ID-$stage"
echo "results are on branch ergebnis/macos-$GITHUB_RUN_ID-$stage"
