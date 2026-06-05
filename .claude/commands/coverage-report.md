# Coverage Report
Run JaCoCo and summarise results per service.

**Service (optional):** $ARGUMENTS

If a service is provided:

```bash
mvn verify -pl backend/services/$ARGUMENTS --no-transfer-progress
```

Read: `backend/services/$ARGUMENTS/target/site/jacoco/index.html`

If no argument (aggregate):

```bash
mvn verify -pl backend/coverage --no-transfer-progress
```

Read: `backend/coverage/target/site/jacoco-aggregate/index.html`

Report a table:

| Service | Instruction % | Branch % | Meets 85% threshold? |
|---|---|---|---|
|...| | | |

Flag services below the thresholds from `CLAUDE.md`. After the run,
update `.claude/memory/test-coverage-baseline.md` if any numbers improved
