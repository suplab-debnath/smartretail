# Sync Docs
Use the `project-tracker` agent to identify stale or out-of-sync documentation.

This command triggers a full project health check focused on documentation:

1. Use the `project-tracker` agent with this prompt:
"Run a full documentation sync check. Compare the root `CLAUDE.md` service status table
against actual code. Check docs/ for stale TODO/TBD content. Check if `API_CONTRACTS.md`
matches the OpenAPI YAML files. Report what needs updating and suggest the specific
changes to make each doc accurate."

2. For each stale doc identified:
- Show the current (stale) content
- Show the proposed accurate replacement
- Ask: "Update this? (YES / NO)"

3. After all checks, report a summary:
- Files updated
- Files skipped
- Items that require code changes before docs can be updated

**Remember:** `CLAUDE.md` and `docs/ `are living documents. Update them as flows are implemented,
bugs are fixed, and decisions are made. A stale doc is a bug.
