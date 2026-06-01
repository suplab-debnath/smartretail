# Build Service

Build and test a specific SmartRetail backend service.

---

**Service name:** $ARGUMENTS

Valid service names: `sis`, `ims`, `re`, `ars`, `dfs`, `sup`, `pps`

---

Build and verify the `$ARGUMENTS` service:

1. Run `mvn clean verify -pl backend/services/$ARGUMENTS --no-transfer-progress` to compile, run tests, and generate the JaCoCo coverage report.
2. If the build fails, read the Maven output carefully and diagnose:
   - Compilation errors → check for missing imports, generated-source issues (run `mvn generate-sources` first)
   - Test failures → read the Surefire report in `backend/services/$ARGUMENTS/target/surefire-reports/`
   - ArchUnit failures → a hexagonal architecture rule was violated; fix the package placement
3. After a green build, report:
   - ✅ Build status
   - Test count: X passed / Y failed / Z skipped
   - JaCoCo coverage: instruction % and branch % from `target/site/jacoco/index.html`

Before building, read `docs/SERVICE_SPECS.md` for the `$ARGUMENTS` service to understand its domain model and port structure.

Do NOT modify generated sources under `target/generated-sources/`. If stubs are missing, run `mvn generate-sources -pl backend/services/$ARGUMENTS` first.
