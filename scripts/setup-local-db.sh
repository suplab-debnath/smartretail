#!/usr/bin/env bash
# Sets up the local PostgreSQL database and user for SmartRetail development.
# Requires PostgreSQL to be installed and running locally (no Docker needed).
# Usage: bash scripts/setup-local-db.sh

set -euo pipefail

DB_NAME="smartretail"
DB_USER="smartretail_admin"
DB_PASS="local_dev_password"

echo "Setting up local PostgreSQL for SmartRetail..."

psql -U postgres -c "CREATE DATABASE $DB_NAME;" 2>/dev/null \
    && echo "  Created database: $DB_NAME" \
    || echo "  Database already exists: $DB_NAME"

psql -U postgres -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASS';" 2>/dev/null \
    && echo "  Created user: $DB_USER" \
    || echo "  User already exists: $DB_USER"

psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE $DB_NAME TO $DB_USER;"
psql -U postgres -d "$DB_NAME" -c "GRANT ALL ON SCHEMA public TO $DB_USER;"

echo "✅ Local database ready: postgres://localhost:5432/$DB_NAME"
