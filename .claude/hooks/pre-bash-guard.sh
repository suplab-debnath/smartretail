#!/usr/bin/env bash
# Pre-tool hook: guards against destructive Bash commands.
# Receives tool input JSON on stdin. Exits 2 to block, 0 to allow.

INPUT=$(cat)

# Extract command from JSON (supports flat or nested tool_input structure)
COMMAND=$(python3 -c "
import sys, json
data = json.load(sys.stdin)
cmd = data.get('command') or data.get('tool_input', {}).get('command', '')
print(cmd)
" <<< "$INPUT" 2>/dev/null) || COMMAND=""

if [ -z "$COMMAND" ]; then
  exit 0
fi

# Block: force push to protected branches
if echo "$COMMAND" | grep -qE 'git push.*(--force|-f\b)'; then
  if echo "$COMMAND" | grep -qE '(main|master|develop)'; then
    echo "BLOCKED: Force-pushing to a protected branch (main/master/develop) is not allowed."
    echo "Use a feature branch and open a pull request instead."
    exit 2
  fi
fi

# Block: hard reset (data loss risk)
if echo "$COMMAND" | grep -qE 'git reset --hard'; then
  echo "BLOCKED: git reset --hard can destroy uncommitted work."
  echo "If you are certain, run this command manually in the terminal."
  exit 2
fi

# Block: recursive removal of root or home directories
if echo "$COMMAND" | grep -qE "rm\s+-[rRfF]{1,4}\s+(\/\s|\/home\s|\/usr\s|\/etc\s|~\s|~$|\.\.\/)"; then
  echo "BLOCKED: Recursive removal of a critical system path is not allowed."
  exit 2
fi

# Block: destructive SQL against live databases
if echo "$COMMAND" | grep -qiE "(DROP\s+DATABASE|DROP\s+SCHEMA)\s+\w"; then
  echo "BLOCKED: DROP DATABASE/SCHEMA requires explicit user action in the terminal."
  exit 2
fi

# Warn: git clean (does not block, but informs)
if echo "$COMMAND" | grep -qE 'git clean -[fdxX]'; then
  echo "WARNING: git clean will permanently delete untracked files. Proceeding as allowed."
fi

exit 0
