#!/usr/bin/env bash
# Stop hook: appends a brief session entry to the session log.
# Runs automatically when Claude finishes a task.
LOG_FILE=".claude/memory/session-log.md"
TIMESTAMP=$(date -u '+%Y-%m-%d %H:%M UTC')

# Ensure log file exists with header
if [ ! -f "$LOG_FILE" ]; then
  printf '# Session Log\n\nAuto-updated by on-stop.sh. Most recent at top.\n\n' > "$LOG_FILE"
fi

# Capture last 5 changed files as session context
CHANGED=$(git status --short 2>/dev/null | head -5 | sed 's/^/ /')
[ -z "$CHANGED" ] && CHANGED=" (no git changes this session)"

# Prepend new entry (newest at top)
TMP=$(mktemp)
{
  printf '## %s\n```\n%s\n```\n\n' "$TIMESTAMP" "$CHANGED"
  cat "$LOG_FILE"
} > "$TMP"

mv "$TMP" "$LOG_FILE"
exit 0
