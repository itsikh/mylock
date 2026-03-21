#!/usr/bin/env bash
set -uo pipefail

###############################################################################
# autofix-wrapper.sh — Meta-wrapper for autofix.sh
#
# Runs autofix.sh. If it exits non-zero, asks Claude to diagnose and fix the
# failure (stale lock, git divergence, script bug, etc.), then retries.
#
# The cron job should call THIS file, not autofix.sh directly.
#
# Cron example (every 2 minutes):
#   */2 * * * * /path/to/project/.claude/skills/autofix/autofix-wrapper.sh >> /path/to/.autofix-logs/cron.log 2>&1
###############################################################################

export PATH="/opt/homebrew/bin:/Users/itsik-personal/.local/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
unset CLAUDECODE  # prevent "nested session" error when run from cron

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
AUTOFIX_SCRIPT="$SCRIPT_DIR/autofix.sh"
LOG_DIR="$PROJECT_DIR/.autofix-logs"
MAX_META_RETRIES=2
MAX_RUN_SECONDS=3600   # 1-hour hard limit per run

timestamp() { date "+%Y-%m-%d %H:%M:%S"; }
log()        { echo "[$(timestamp)] [wrapper] $*"; }
log_err()    { echo "[$(timestamp)] [wrapper] ERROR: $*" >&2; }

mkdir -p "$LOG_DIR"

# Derive lock name the same way autofix.sh does (for error messages)
APP_ID="$(grep 'applicationId\s*=' "$PROJECT_DIR/app/build.gradle.kts" 2>/dev/null | grep -oP '"[^"]+"' | tr -d '"' | tr '.' '-' || echo "android-template")"
LOCK_DIR="/tmp/${APP_ID}-autofix.lockdir"

attempt=1
while [[ $attempt -le $((MAX_META_RETRIES + 1)) ]]; do
    log "=== Run attempt $attempt / $((MAX_META_RETRIES + 1)) ==="

    run_log="$LOG_DIR/wrapper_run_$(date +%Y%m%d_%H%M%S).log"

    # Run autofix.sh with a hard timeout; kill the entire process group so
    # any Claude subprocesses spawned by autofix.sh are also terminated.
    bash "$AUTOFIX_SCRIPT" > >(tee "$run_log") 2>&1 &
    autofix_pid=$!
    ( sleep "$MAX_RUN_SECONDS" && kill -- -"$autofix_pid" 2>/dev/null; kill "$autofix_pid" 2>/dev/null ) &
    killer_pid=$!
    wait "$autofix_pid"
    exit_code=$?
    kill "$killer_pid" 2>/dev/null
    wait "$killer_pid" 2>/dev/null || true
    if [[ $exit_code -eq 143 ]]; then
        log_err "autofix.sh exceeded ${MAX_RUN_SECONDS}s hard limit — killed by timeout."
        exit_code=124
    fi

    if [[ $exit_code -eq 0 ]]; then
        log "autofix.sh succeeded on attempt $attempt."
        exit 0
    fi

    if [[ $attempt -gt $MAX_META_RETRIES ]]; then
        log_err "All $MAX_META_RETRIES meta-retries exhausted. Giving up."
        exit 1
    fi

    log "autofix.sh exited $exit_code on attempt $attempt — invoking Claude to fix..."

    error_context=$(tail -80 "$run_log" 2>/dev/null || echo "(no output captured)")

    fix_prompt="The autofix.sh script for the Android project at $PROJECT_DIR failed. Diagnose and fix the root cause so the next run succeeds.

Script: $AUTOFIX_SCRIPT
Project: $PROJECT_DIR

--- Last 80 lines of output ---
$error_context
--- end of output ---

Common issues and how to fix them:

1. Stale lock at $LOCK_DIR
   - Check the PID: cat $LOCK_DIR/pid
   - Only remove the lock if that PID is dead or in stopped state (T):
     ps -p <pid> -o stat=
   - If stale: rm -rf $LOCK_DIR

2. Git diverged branches
   - Fetch all remotes: git fetch --all
   - Rebase or merge to align local with remotes
   - Push: git push origin main (and any other remotes)
   - Use --force-with-lease if a rebase rewrote SHAs

3. Uncommitted changes left by a crashed run
   - Commit: git add -u && git commit -m 'fix: clean up after crash'
   - Or discard: git checkout -- .

4. Bug in $AUTOFIX_SCRIPT
   - Read the script carefully and fix the bash logic
   - Known past bugs to watch for:
     a. \"Claude ran but no files were modified or committed\" — the git diff check
        missed changes that Claude committed internally (e.g. via /release skill).
        Fix: track HEAD before/after Claude runs and treat a moved HEAD as success.
     b. attempt_fix() returning 1 even when Claude succeeded — check return code logic.
     c. main() not returning non-zero on failure — the wrapper only retries on exit 1.
     d. Pipeline swallowing exit codes (cmd | tee) — use set -o pipefail or PIPESTATUS.

5. AppConfig.kt not found
   - Ensure the file exists under app/src/ in the project root

Constraints: minimal changes only. Do not remove a live lock. All strings in English."

    log "Calling Claude to diagnose and fix..."
    claude --dangerously-skip-permissions -p "$fix_prompt" </dev/null 2>&1
    claude_exit=$?

    if [[ $claude_exit -ne 0 ]]; then
        log_err "Claude exited $claude_exit — retrying autofix.sh anyway..."
    else
        log "Claude fix complete. Retrying autofix.sh..."
    fi

    sleep 3
    attempt=$((attempt + 1))
done
