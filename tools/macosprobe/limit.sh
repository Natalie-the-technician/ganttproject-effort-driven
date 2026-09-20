#!/bin/sh
# Runs a command with a hard wall-clock limit and says so when it hits it.
#
# WHY THIS EXISTS: macOS has no timeout(1). GNU coreutils ships gtimeout and the GitHub runner
# image happens to have it, but "happens to have" is what this whole workflow is about not relying
# on. Everything here is POSIX sh.
#
# WHY IT MATTERS HERE: on 20.09.2026 a run of this workflow sat in the keychain step for TWO HOURS
# until it was cancelled by hand. `security` is the prime suspect -- when securityd wants a decision
# from a person and there is nobody to ask, the command does not fail, it waits. A waiting command
# is indistinguishable from a slow one from the outside, and that is precisely what has to stop:
# this turns "it hung" into a measurement with an exit code (124) and a line in the log.
#
# NOT a watchdog around the whole step -- `timeout-minutes` in the workflow is that, and it kills
# the step without saying which command was the one waiting. This says which.
#
# usage: limit.sh <seconds> <command> [args...]
# exit:  the command's own exit code, or 124 if the limit was reached.

limit="$1"
shift

"$@" &
child=$!

# A second child as the alarm clock. The obvious loop -- poll `kill -0` once a second -- does not
# work: a finished child is a zombie until it is waited for, and `kill -0` on a zombie SUCCEEDS, so
# the loop would run its full length every single time.
#
# `>/dev/null 2>&1` ON THE ALARM, and it is not cosmetic. MEASURED on 20.09.2026, in run
# 35521476830: the keychain step took 135 seconds although every `security` call in it returned
# rc=0 in no time -- three of those calls sat inside a command substitution, at 45 seconds each.
#
# The reason: `kill "$alarm"` below ends the SUBSHELL, but not the `sleep` running inside it. That
# orphaned `sleep` keeps the file descriptors it inherited, and when this script runs inside
# `x="$(limit.sh 45 something-quick)"` one of those is the very pipe the command substitution is
# waiting for EOF on. So `$( )` waits out the full limit even though the command finished at once.
# Giving the alarm its own /dev/null means it holds nothing anybody is waiting for.
( sleep "$limit"; kill -9 "$child" 2>/dev/null ) >/dev/null 2>&1 &
alarm=$!

wait "$child"
rc=$?

kill "$alarm" 2>/dev/null
wait "$alarm" 2>/dev/null

# 137 is 128+9, i.e. killed by SIGKILL, and the alarm above is the only thing sending one here.
if [ "$rc" -eq 137 ]; then
  echo "LIMIT: >${limit}s, killed: $*" >&2
  exit 124
fi
exit "$rc"
