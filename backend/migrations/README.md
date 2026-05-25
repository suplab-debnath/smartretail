# Migrations

Flyway-managed PostgreSQL schema migrations for the entire SmartRetail platform. All services share a single RDS instance (multi-schema, not multi-database) with strict no-cross-schema-join rules enforced at the application layer.

## Migration files

| File | Schema | Contents |
|------|--------|----------|
| `V1__create_sales_schema.sql` | `sales` | `sales_transactions`, `raw_events` archive table |
| `V2__create_forecasting_schema.sql` | `forecasting` | `forecast_data`, `mape_history` |
| `V3__create_inventory_schema.sql` | `inventory` | `inventory_positions`, `stock_alerts` — includes `version` column for optimistic locking |
| `V4__create_replenishment_schema.sql` | `replenishment` | `purchase_orders`, `po_line_items`, `replenishment_rules` — DB-backed state machine |
| `V5__create_supplier_schema.sql` | `supplier` | `suppliers`, `supplier_orders`, `shipment_metrics` |
| `V6__create_promotions_schema.sql` | `promotions` | `promotions`, `promotion_skus` — read-only reference for ARS and DFS |
| `V7__seed_data.sql` | all | Reference master data — idempotent (`ON CONFLICT DO NOTHING`) |
| `V8__seed_flow8_charts.sql` | `inventory`, `forecasting` | CRITICAL stock alerts distributed over 30 days for Executive Dashboard charts |
| `V9__seed_data_flow9.sql` | `replenishment`, `supplier` | SC Planner seed: `actual_units`, denormalised supplier PO fields |

## Running migrations

```bash
# Local (Docker Compose Postgres)
make local-migrate

# AWS (RDS via RDS Proxy — requires VPC access)
make aws-migrate
# or directly:
bash scripts/shared/run-flyway-aws.sh
```

## Flyway configuration

```
backend/migrations/
├── pom.xml                        Maven Flyway plugin config
└── src/main/resources/
    ├── application-local.properties   jdbc:postgresql://localhost:5432/smartretail
    ├── application-aws.properties     jdbc:postgresql://<rds-proxy-endpoint>:5432/smartretail
    └── db/migration/                  V*.sql files (above)
```

`SPRING_PROFILES_ACTIVE=local mvn flyway:migrate` runs against local Postgres.  
`SPRING_PROFILES_ACTIVE=aws  mvn flyway:migrate` runs against RDS via the proxy.

## Schema ownership

Each schema is owned by exactly one service. No service may write to another service's schema, and no SQL JOIN may cross schema boundaries. This rule is enforced by ArchUnit tests in each service module.

| Schema | Owning service |
|--------|---------------|
| `sales` | SIS |
| `inventory` | IMS |
| `replenishment` | RE |
| `forecasting` | DFS |
| `supplier` | SUP |
| `promotions` | (read-only reference data) |
