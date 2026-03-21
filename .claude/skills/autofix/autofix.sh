#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# autofix.sh — Cron-driven bug-fixing agent for Android template apps
#
# Monitors the configured GitHub issues repo for open issues labelled
# "autofix", dispatches Claude CLI to fix each bug autonomously, retries
# on failure, and triggers a release when done.
#
# All project-specific values are read from AppConfig.kt and build.gradle.kts
# so this script works for any app built from the Android template.
#
# Usage:
#   ./autofix.sh                  # run once (designed for cron)
#
# Cron example (every 30 minutes):
#   */30 * * * * /path/to/project/.claude/skills/autofix/autofix.sh
###############################################################################

# ── PATH setup (cron runs with minimal PATH) ────────────────────────────────
export PATH="/opt/homebrew/bin:/Users/itsik-personal/.local/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
unset CLAUDECODE  # prevent "nested session" error when run from cron

# ── Derive project root from script location ─────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# ── Read app configuration from AppConfig.kt ────────────────────────────────
APP_CONFIG_FILE="$(find "$PROJECT_DIR/app/src" -name "AppConfig.kt" | head -1)"
if [[ -z "$APP_CONFIG_FILE" ]]; then
    echo "ERROR: AppConfig.kt not found under $PROJECT_DIR/app/src" >&2
    exit 1
fi

read_appconfig_string() {
    local key="$1"
    grep "${key}" "$APP_CONFIG_FILE" | grep -oE '"[^"]+"' | head -1 | tr -d '"'
}

BUGS_REPO_OWNER="$(read_appconfig_string 'GITHUB_ISSUES_REPO_OWNER')"
BUGS_REPO_NAME="$(read_appconfig_string 'GITHUB_ISSUES_REPO_NAME')"
BUGS_REPO="${BUGS_REPO_OWNER}/${BUGS_REPO_NAME}"

# Derive a short slug from build.gradle.kts applicationId for the lock file
APP_ID="$(grep 'applicationId' "$PROJECT_DIR/app/build.gradle.kts" | grep -oE '"[^"]+"' | tr -d '"' | tr '.' '-')"
if [[ -z "$APP_ID" ]]; then
    APP_ID="android-template"
fi

# ── Configuration ────────────────────────────────────────────────────────────
LOCK_DIR="/tmp/${APP_ID}-autofix.lockdir"
LOG_DIR="$PROJECT_DIR/.autofix-logs"
PROMPT_TEMPLATE="$SCRIPT_DIR/fix-prompt.txt"
MAX_RETRIES=3
JAVA_HOME_PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ACTIVE_LABEL="claude-active"
AUTOFIX_LABEL="autofix"

# Temp directory for passing JSON between shell and Python safely
WORK_TMP=""

# ── Helpers ──────────────────────────────────────────────────────────────────
timestamp() { date "+%Y-%m-%d %H:%M:%S"; }

log() {
    echo "[$(timestamp)] $*"
}

log_error() {
    echo "[$(timestamp)] ERROR: $*" >&2
}

cleanup() {
    rm -rf "$LOCK_DIR"
    [[ -n "$WORK_TMP" ]] && rm -rf "$WORK_TMP"
    log "Lock released, exiting."
}

# ── Lock Management (atomic mkdir) ──────────────────────────────────────────
write_lock_info() {
    echo $$ > "$LOCK_DIR/pid"
    ps -p $$ -o lstart= > "$LOCK_DIR/lstart" 2>/dev/null || true
}

is_lock_holder_alive() {
    local old_pid
    old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "")
    [[ -z "$old_pid" ]] && return 1

    kill -0 "$old_pid" 2>/dev/null || return 1

    # A stopped (suspended) process — state T — will never make progress.
    # Treat it as stale so the lock can be reclaimed.
    local pstate
    pstate=$(ps -p "$old_pid" -o stat= 2>/dev/null || echo "")
    [[ "$pstate" == T* ]] && return 1

    local old_lstart current_lstart
    old_lstart=$(cat "$LOCK_DIR/lstart" 2>/dev/null || echo "")
    if [[ -n "$old_lstart" ]]; then
        current_lstart=$(ps -p "$old_pid" -o lstart= 2>/dev/null || echo "")
        if [[ "$old_lstart" != "$current_lstart" ]]; then
            return 1
        fi
    fi

    return 0
}

acquire_lock() {
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        write_lock_info
        trap cleanup EXIT
        log "Lock acquired (PID $$)."
        return
    fi

    if is_lock_holder_alive; then
        local old_pid
        old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "?")
        log_error "Another autofix instance is running (PID $old_pid). Exiting."
        exit 0
    fi

    local old_pid
    old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || echo "?")
    log "Stale lock detected (PID $old_pid no longer running). Removing."
    rm -rf "$LOCK_DIR"
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        write_lock_info
        trap cleanup EXIT
        log "Lock acquired (PID $$)."
    else
        log_error "Failed to acquire lock after stale removal. Exiting."
        exit 1
    fi
}

# ── Git State ────────────────────────────────────────────────────────────────

# Push to a remote; fall back to --force-with-lease when a prior rebase
# rewrote commit SHAs and a fast-forward push is rejected.
push_to_remote() {
    local remote="$1"
    if git push "$remote" main 2>/dev/null; then
        return 0
    fi
    log "Fast-forward push to $remote rejected — retrying with --force-with-lease..."
    git push "$remote" main --force-with-lease 2>/dev/null || \
        log_error "Could not push to $remote"
}

verify_git_state() {
    cd "$PROJECT_DIR"

    local branch
    branch=$(git rev-parse --abbrev-ref HEAD)
    if [[ "$branch" != "main" ]]; then
        log_error "Not on main branch (on '$branch'). Exiting."
        exit 1
    fi

    # Commit any uncommitted tracked changes
    if ! git diff --quiet || ! git diff --cached --quiet; then
        log "Working tree has uncommitted changes. Auto-committing..."
        git add -u
        git commit -m "autofix: auto-commit pending changes before run" || true
    fi

    # Fetch all remotes (non-fatal — network may be unavailable)
    while IFS= read -r remote; do
        git fetch "$remote" 2>/dev/null || true
    done < <(git remote)

    # Rebase onto origin/main if it has commits we don't have
    local origin_ahead
    origin_ahead=$(git rev-list HEAD..origin/main --count 2>/dev/null || echo 0)
    if [[ "$origin_ahead" -gt 0 ]]; then
        log "origin/main is $origin_ahead commit(s) ahead — rebasing..."
        git rebase origin/main 2>/dev/null || {
            git rebase --abort 2>/dev/null || true
            git merge --no-edit origin/main 2>/dev/null || true
        }
    fi

    # Push to all configured remotes
    while IFS= read -r remote; do
        push_to_remote "$remote"
    done < <(git remote)

    log "Git state verified."
}

# ── JSON helper ──────────────────────────────────────────────────────────────
run_py() {
    local json_file="$1"
    shift
    python3 -c "$*" "$json_file"
}

# ── Label Management ─────────────────────────────────────────────────────────
ensure_label_exists() {
    if ! gh label list --repo "$BUGS_REPO" --search "$ACTIVE_LABEL" --json name \
        | python3 -c "import sys,json; sys.exit(0 if any(l['name']=='$ACTIVE_LABEL' for l in json.load(sys.stdin)) else 1)" 2>/dev/null; then
        gh label create "$ACTIVE_LABEL" --repo "$BUGS_REPO" \
            --description "Autofix agent is actively working on this issue" \
            --color "F9D0C4" 2>/dev/null || true
        log "Created label '$ACTIVE_LABEL'"
    fi
    if ! gh label list --repo "$BUGS_REPO" --search "$AUTOFIX_LABEL" --json name \
        | python3 -c "import sys,json; sys.exit(0 if any(l['name']=='$AUTOFIX_LABEL' for l in json.load(sys.stdin)) else 1)" 2>/dev/null; then
        gh label create "$AUTOFIX_LABEL" --repo "$BUGS_REPO" \
            --description "Issue approved for automatic fixing by the autofix agent" \
            --color "0E8A16" 2>/dev/null || true
        log "Created label '$AUTOFIX_LABEL'"
    fi
}

label_task_active() {
    local task_file="$1"
    local issue_nums
    issue_nums=$(run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
for i in issues:
    print(i["number"])
')
    while IFS= read -r num; do
        gh issue edit "$num" --repo "$BUGS_REPO" --add-label "$ACTIVE_LABEL" 2>/dev/null || true
    done <<< "$issue_nums"
}

unlabel_task_active() {
    local task_file="$1"
    local issue_nums
    issue_nums=$(run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
for i in issues:
    print(i["number"])
')
    while IFS= read -r num; do
        gh issue edit "$num" --repo "$BUGS_REPO" --remove-label "$ACTIVE_LABEL" 2>/dev/null || true
    done <<< "$issue_nums"
}

# ── Issue Fetching & Grouping ────────────────────────────────────────────────
fetch_and_group_issues() {
    local tmp_issues="$WORK_TMP/issues.json"
    gh issue list --repo "$BUGS_REPO" --state open --label "$AUTOFIX_LABEL" --json number,title,body,labels --limit 100 > "$tmp_issues"

    local count
    count=$(run_py "$tmp_issues" '
import json, sys
with open(sys.argv[1]) as f:
    print(len(json.load(f)))
')

    if [[ "$count" == "0" ]]; then
        echo "[]"
        return
    fi

    # Group by story:* label; standalone issues become their own group.
    # Skip issues already labelled claude-active (being worked on by another instance).
    run_py "$tmp_issues" '
import json, sys

with open(sys.argv[1]) as f:
    issues = json.load(f)

ACTIVE = "claude-active"
issues = [i for i in issues if ACTIVE not in [l["name"] for l in i.get("labels", [])]]

groups = {}
standalone = []

for issue in issues:
    labels = [l["name"] for l in issue.get("labels", [])]
    story_label = None
    for l in labels:
        if l.startswith("story:"):
            story_label = l
            break
    if story_label:
        groups.setdefault(story_label, []).append(issue)
    else:
        standalone.append(issue)

tasks = list(groups.values())
for issue in standalone:
    tasks.append([issue])

print(json.dumps(tasks))
'
}

# ── Prompt Building ──────────────────────────────────────────────────────────
build_prompt() {
    local issues_desc="$1"
    local retry_context="$2"

    local prompt
    prompt=$(cat "$PROMPT_TEMPLATE")

    prompt="${prompt/\{\{ISSUES\}\}/$issues_desc}"

    if [[ -n "$retry_context" ]]; then
        local retry_block="## Previous Attempt Failed

The previous fix attempt failed with the following error. Please analyze the error and try a different approach:

\`\`\`
$retry_context
\`\`\`"
        prompt="${prompt/\{\{RETRY_CONTEXT\}\}/$retry_block}"
    else
        prompt="${prompt/\{\{RETRY_CONTEXT\}\}/}"
    fi

    echo "$prompt"
}

format_issues_for_prompt() {
    local task_file="$1"

    run_py "$task_file" '
import json, sys

with open(sys.argv[1]) as f:
    issues = json.load(f)

parts = []
for issue in issues:
    number = issue["number"]
    title = issue["title"]
    body = issue.get("body", "") or ""
    labels = ", ".join(l["name"] for l in issue.get("labels", []))
    part = f"### Issue #{number}: {title}\n"
    if labels:
        part += f"Labels: {labels}\n"
    part += f"\n{body}\n"
    parts.append(part)

print("\n---\n".join(parts))
'
}

# ── Fix Execution ────────────────────────────────────────────────────────────
# Returns: 0 = fix applied & build passed, 1 = failure, 2 = already fixed
attempt_fix() {
    local prompt="$1"
    local task_log="$2"

    # Record HEAD before calling Claude so we can detect committed changes too.
    local head_before
    head_before=$(git rev-parse HEAD 2>/dev/null || echo "")

    log "Invoking Claude CLI..."
    local claude_exit=0
    local claude_tmp
    claude_tmp=$(mktemp)
    claude --dangerously-skip-permissions -p "$prompt" 2>&1 | tee -a "$task_log" "$claude_tmp"
    claude_exit=${PIPESTATUS[0]}
    local claude_output
    claude_output=$(cat "$claude_tmp")
    rm -f "$claude_tmp"

    if [[ $claude_exit -ne 0 ]]; then
        log_error "Claude CLI exited with code $claude_exit"
        return 1
    fi

    if echo "$claude_output" | grep -q "ALREADY_FIXED:"; then
        log "Claude determined issue is already fixed."
        return 2
    fi

    local head_after
    head_after=$(git rev-parse HEAD 2>/dev/null || echo "")
    local claude_committed=false
    if [[ -n "$head_before" && "$head_before" != "$head_after" ]]; then
        claude_committed=true
    fi

    if [[ "$claude_committed" == "false" ]] && git diff --quiet && git diff --cached --quiet; then
        log_error "Claude ran but no files were modified or committed."
        return 1
    fi

    if [[ "$claude_committed" == "true" ]]; then
        # Claude committed (and likely released) the fix — build already verified by /release.
        log "Claude committed changes (HEAD moved to ${head_after:0:8}) — build verified by release."
        return 0
    fi

    log "Verifying build (assembleDebug)..."
    export JAVA_HOME="$JAVA_HOME_PATH"
    local build_output
    local build_exit=0
    build_output=$(cd "$PROJECT_DIR" && ./gradlew assembleDebug 2>&1) || build_exit=$?

    echo "$build_output" >> "$task_log"

    if [[ $build_exit -ne 0 ]]; then
        log_error "Build verification failed."
        return 1
    fi

    log "Build verification passed."
    return 0
}

try_fix_task() {
    local task_file="$1"
    local task_log="$2"

    local issues_desc
    issues_desc=$(format_issues_for_prompt "$task_file")

    local retry_context=""
    local attempt=1

    while [[ $attempt -le $MAX_RETRIES ]]; do
        log "Attempt $attempt/$MAX_RETRIES..."

        if [[ $attempt -gt 1 ]]; then
            git checkout -- . 2>/dev/null || true
            git clean -fd 2>/dev/null || true
        fi

        local prompt
        prompt=$(build_prompt "$issues_desc" "$retry_context")

        local fix_result=0
        attempt_fix "$prompt" "$task_log" || fix_result=$?

        if [[ $fix_result -eq 0 ]]; then
            local diff_summary
            diff_summary=$(git diff --stat 2>/dev/null || echo "")
            local diff_detail
            diff_detail=$(git diff --no-color 2>/dev/null | head -200 || echo "")

            git add -A
            local issue_numbers
            issue_numbers=$(run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
print(", ".join("#" + str(i["number"]) for i in issues))
')
            git commit -m "autofix: resolve $issue_numbers"
            log "Fix committed for $issue_numbers"

            echo "$diff_summary" > "$task_log.diff_summary"
            echo "$diff_detail" > "$task_log.diff_detail"

            local fix_summary
            fix_summary=$(sed -n '/FIX_SUMMARY_START/,/FIX_SUMMARY_END/{/FIX_SUMMARY_START/d;/FIX_SUMMARY_END/d;p;}' "$task_log" 2>/dev/null || echo "")
            if [[ -n "$fix_summary" ]]; then
                echo "$fix_summary" > "$task_log.fix_summary"
            fi
            return 0

        elif [[ $fix_result -eq 2 ]]; then
            local issue_nums
            issue_nums=$(run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
for i in issues:
    print(i["number"])
')
            while IFS= read -r num; do
                gh issue close "$num" --repo "$BUGS_REPO" \
                    --comment "Autofix agent verified this issue is already resolved in the current code." \
                    2>/dev/null || true
                log "Closed already-fixed issue #$num"
            done <<< "$issue_nums"
            return 0
        fi

        retry_context=$(tail -50 "$task_log" 2>/dev/null || echo "Unknown error")
        attempt=$((attempt + 1))
    done

    # All retries exhausted — reset and comment on issues
    git checkout -- . 2>/dev/null || true
    git clean -fd 2>/dev/null || true

    local issue_numbers_list
    issue_numbers_list=$(run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
for i in issues:
    print(i["number"])
')

    while IFS= read -r num; do
        gh issue comment "$num" --repo "$BUGS_REPO" \
            --body "Autofix agent failed to resolve this issue after $MAX_RETRIES attempts. Manual intervention required." \
            2>/dev/null || true
    done <<< "$issue_numbers_list"

    log_error "All $MAX_RETRIES attempts failed. Commented on issues."
    return 1
}

# ── Release Trigger ──────────────────────────────────────────────────────────
trigger_release() {
    log "Triggering release via Claude CLI..."
    cd "$PROJECT_DIR"
    claude --dangerously-skip-permissions -p "/release" </dev/null 2>&1 || {
        log_error "Release skill failed. Manual release may be needed."
    }
}

# ── Main Loop ────────────────────────────────────────────────────────────────
# Processes all open autofix issues, pushes fixes, re-checks until the queue
# is empty, then does a single release at the very end.
main() {
    mkdir -p "$LOG_DIR"
    WORK_TMP=$(mktemp -d)

    log "=== Autofix run started (project: $PROJECT_DIR, repo: $BUGS_REPO) ==="
    acquire_lock
    verify_git_state
    ensure_label_exists

    local any_fixed=false
    local any_failed=false
    local all_fixed_issues=()
    local round=1

    while true; do
        log "--- Round $round ---"

        local tasks_file="$WORK_TMP/tasks_r${round}.json"
        fetch_and_group_issues > "$tasks_file"

        local task_count
        task_count=$(run_py "$tasks_file" '
import json, sys
with open(sys.argv[1]) as f:
    print(len(json.load(f)))
')

        if [[ "$task_count" == "0" ]]; then
            log "No open issues found."
            break
        fi

        log "Found $task_count task(s) to process."

        local fixed_this_round=()
        local task_index=0

        while [[ $task_index -lt $task_count ]]; do
            local task_file="$WORK_TMP/task_r${round}_${task_index}.json"
            python3 -c "
import json, sys
with open(sys.argv[1]) as f:
    tasks = json.load(f)
print(json.dumps(tasks[$task_index]))
" "$tasks_file" > "$task_file"

            local task_label
            task_label=$(run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
nums = ", ".join("#" + str(i["number"]) for i in issues)
print(nums)
')

            log "Processing task: $task_label"
            label_task_active "$task_file"

            local task_log="$LOG_DIR/task_$(date +%Y%m%d_%H%M%S)_${task_label//[^0-9_]/_}.log"

            if try_fix_task "$task_file" "$task_log"; then
                any_fixed=true
                fixed_this_round+=("1")

                local issue_nums
                issue_nums=$(run_py "$task_file" '
import json, sys
with open(sys.argv[1]) as f:
    issues = json.load(f)
for i in issues:
    print(i["number"])
')
                local close_body=""
                if [[ -f "$task_log.fix_summary" ]]; then
                    local fix_sum
                    fix_sum=$(cat "$task_log.fix_summary")
                    close_body+="${fix_sum}\n\n"
                fi
                if [[ -f "$task_log.diff_summary" ]]; then
                    local summary
                    summary=$(cat "$task_log.diff_summary")
                    close_body+="---\n### Files Changed\n\`\`\`\n${summary}\n\`\`\`\n"
                fi
                if [[ -z "$close_body" ]]; then
                    close_body="Fixed by autofix agent."
                fi

                while IFS= read -r num; do
                    gh issue close "$num" --repo "$BUGS_REPO" \
                        --comment "$(echo -e "$close_body")" \
                        2>/dev/null || true
                    log "Closed issue #$num with report"
                    all_fixed_issues+=("$num")
                done <<< "$issue_nums"

                rm -f "$task_log.diff_summary" "$task_log.diff_detail" "$task_log.fix_summary"
            else
                any_failed=true
            fi

            unlabel_task_active "$task_file"
            task_index=$((task_index + 1))
        done

        # Push fixes after each round (all configured remotes)
        if [[ ${#fixed_this_round[@]} -gt 0 ]]; then
            while IFS= read -r remote; do
                git push "$remote" main || true
            done < <(git remote)
            log "Pushed fixes for round $round."
        else
            log "No issues fixed this round. Stopping."
            break
        fi

        round=$((round + 1))
        sleep 5
    done

    # Single release at the very end, only if we fixed anything
    if [[ "$any_fixed" == "true" ]]; then
        log "=== Post-fix: triggering release ==="
        trigger_release
        log "=== Autofix run completed: ${#all_fixed_issues[@]} issue(s) fixed ==="
    else
        log "=== Autofix run completed: no issues fixed ==="
    fi

    # Exit non-zero if there were open issues we couldn't fix — lets the wrapper
    # detect the failure and invoke Claude to diagnose/fix the script itself.
    if [[ "$any_failed" == "true" ]]; then
        return 1
    fi
}

# Use pipefail + PIPESTATUS so the wrapper sees a non-zero exit if main() fails.
set -o pipefail
main "$@" 2>&1 | tee -a "$LOG_DIR/autofix_$(date +%Y%m%d).log"
