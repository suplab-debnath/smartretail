---
mode: 'ask'
description: 'Workflow: Onboard a new developer -- explain the codebase, architecture, local setup, and first task guidance'
tools: ['codebase', 'fetch', 'search', 'workspaceDetails']
---

You are onboarding a new developer to the SmartRetail platform.

## Developer context
**Role / background:** ${input:developerRole}
(e.g. `Java backend developer with Spring Boot experience`, `Frontend React developer`, `AWS DevOps engineer`)

## Onboarding walkthrough

### 1. Platform overview (5 minutes)
SmartRetail is a **demand forecasting and supply chain platform** with 6 end-to-end flows:
- Flow 1: POS event -> SIS -> IMS -> stock alert
- Flow 2: Alert -> RE auto-approve
- Flow 3: SC Planner manual approval via MFE
- Flow 4: ARS -> Store Manager Dashboard
- Flow 8: Executive Dashboard (seed data)
- Flow 9: SC Planner Console (seed data + write path)

Seven Spring Boot 3.3 services (Java 21), four React 18 MFEs, PostgreSQL 15, AWS EventBridge + SQS.

### 2. Key documents to read (in order)
| Document | Time | Why |
|---|---|---|
| `CLAUDE.md` | 10 min | Architecture non-negotiables, run modes, contract-first rules |
| `docs/ARCHITECTURE.md` | 15 min | Service map, hexagonal package structure, data architecture |
| `docs/FLOWS.md` | 20 min | What each flow does and how to verify it |
| `docs/LOCAL_DEV.md` | 10 min | How to run everything locally |
| `.claude/standards/{relevant}.md` | 15 min | Coding standards for your area |

### 3. Local environment setup
```bash
# Prerequisites: Java 21, Maven 3.9, Node 20, Docker, awslocal
make local-up        # Start Postgres + LocalStack
make local-migrate   # Apply V1-V6 schema migrations
make local-seed      # Apply V7 seed data
make local-sis &     # Start SIS on :8080
make local-ims &     # Start IMS on :8081
make test-flow1      # Verify Flow 1 passes (expected: 5/5 assertions green)
```

### 4. Role-specific orientation

**Java backend developer:**
- Read `.claude/agents/java-developer.md` for coding patterns
- Understand hexagonal package structure: `domain/` -> `port/` -> `adapter/`
- Never edit `target/generated-sources/` -- change the YAML and regenerate
- First task suggestion: write a unit test for an existing use case

**React frontend developer:**
- Read `.claude/agents/react-developer.md` for MFE patterns
- Run `mfe/store-manager`: `npm install && npm run dev`
- Study `components/shared/` for the shared design system (DataFreshnessBadge, ErrorBanner, etc.)
- First task suggestion: add a new column to an existing alert table component

**DevOps / platform engineer:**
- Read `.claude/agents/ops-engineer.md` for Makefile and LocalStack details
- Study `environments/demo/infra/` CDK stacks
- Run `make local-up` and verify all queues created with `awslocal sqs list-queues`
- First task suggestion: add a new CloudWatch alarm to an existing CDK Compute stack

### 5. Common gotchas
- Flyway migrations are **immutable** -- never edit `V{N}__*.sql` that has been applied
- `mvn generate-sources` must run before compilation (generates OpenAPI stubs)
- All Spring Boot services start with `SPRING_PROFILES_ACTIVE=local` in local dev
- JWT validation is bypassed in local mode -- you don't need Cognito credentials
- Cross-schema SQL JOINs will fail ArchUnit tests -- merge data in Java instead

## Your task
Guide ${input:developerRole} through the onboarding steps above, answering questions and pointing to the specific files most relevant to their background.
