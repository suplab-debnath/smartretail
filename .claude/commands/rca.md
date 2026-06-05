# Root Cause Analysis
Scaffold a new RCA and add it to the tracker.

**Symptoms:** $ARGUMENTS

Steps:
1. Read `.claude/memory/rca-tracker.md` to get the next RCA-XXX number
2. Create `.claude/memory/rca-{YYYY-MM-DD}-{slug}.md`:

```markdown
# RCA-{N}: {title}
**Date**: {today}
**Status**: �� Open
**Env**: {affected environments}

## Timeline
| Time (UTC) | Event |
|---|---|
| {now} | Investigation started |

## Root Cause
{TBD}

## Fix Required
{TBD}

## Pending Action
- <input type="checkbox" class="task-list-item-checkbox" > {action}
```

3. Add a new Open entry to `.claude/memory/rca-tracker.md`
4. Suggest CloudWatch Logs Insights queries based on the symptoms in $ARGUMENTS
5. Identify which service logs to check first based on symptom keywords
