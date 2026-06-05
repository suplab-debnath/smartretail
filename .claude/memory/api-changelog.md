# API Changelog
Track all breaking and non-breaking API changes per service.
Breaking changes require a version bump (/v1 → /v2) and a new URL prefix.

## Versioning Policy Reminder
- Non-breaking additions (new optional fields, new endpoints): no version bump
- Breaking changes: bump `info.version` AND URL prefix
- Deprecate old version with `deprecated: true`; give 2 sprints before removal
-
---

| Service | Date | Version | Change | Breaking? |
|---|---|---|---|---|
| SIS | — | v1 | Initial contract | — |
| IMS | — | v1 | Initial contract | — |
| RE | — | v1 | Initial contract | — |
| ARS | — | v1 | Initial contract | — |
| DFS | — | v1 | Initial contract | — |
| SUP | — | v1 | Initial contract | — |
| PPS | — | v1 | Stub only | — |

_Update this table whenever a {service}-api.yaml is changed._
