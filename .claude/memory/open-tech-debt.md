# Open Tech Debt
Track shortcuts taken during prototype delivery. Payoff before prod.

| ID | Area | Description | Risk | Priority |
|---|---|---|---|---|
| TD-001 | PPS | PPS service is stub-only (port 8086). Schema exists, no endpoints. | Low (not in demo flows) | Back
| TD-002 | ARS | Reads across 5 schemas with no caching — each request hits all DBs | Medium (latency under load) |
| TD-003 | IMS | Null-safe pagination bug (RCA-003) — NPE on missing page/size params | High (demo breaking) | Next
| TD-004 | Auth | `X-Mock-User` header bypass is trivially forgeable in local mode | Low (local only) | Before AWS d
| TD-005 | DFS | SageMaker Trigger Lambda uses fixed schedule; no on-demand endpoint | Low (demo uses seed data) | Be
| TD-006 | Observability | Micrometer CloudWatch exporter not wired in all services | Medium (no metrics in demo) | S
| TD-007 | Supplier MFE | Supplier Portal (`:5177`) not yet built; write-path flows pending | Medium (demo gap) | Nex
| TD-008 | ARS | RCA-002 JWT parsing broken in demo profile | High (demo breaking) | Next sprint |
