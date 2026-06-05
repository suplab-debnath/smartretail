# Architecture Decision Record
Scaffold a new ADR.

**Decision topic:** $ARGUMENTS

1. Check `docs/decisions/` for existing ADRs and get the next ADR number.
If the directory does not exist, create it.

2. Create `docs/decisions/ADR-{NNN}-{slug}.md`:

```markdown
# ADR-{NNN}: $ARGUMENTS

**Date**: {today}
**Status**: Proposed
**Deciders**: (fill in)

## Context
{What situation forced this decision?}

## Decision
{What was decided?}

## Consequences
### Positive
- {benefit}

### Negative / Trade-offs
- {cost}

## Alternatives Considered
## Decision
{What was decided?}

## Consequences
### Positive
- {benefit}

### Negative / Trade-offs
- {cost}

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
```

3. Append a one-line summary to `docs/ARCHITECTURE.md` :

| {today} | ADR-{NNN} | $ARGUMENTS | Proposed |

Reference: `docs/ARCHITECTURE.md` § Key Principles — "Record decisions"
