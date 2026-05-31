#!/usr/bin/env python3
"""
Export real sales history from RDS to S3 as DeepAR JSON Lines training data.

Reads from sales.sales_transactions (daily units per SKU × DC × date),
zero-fills missing days, and writes:
  s3://{bucket}/sagemaker/training/train.jsonl
  s3://{bucket}/sagemaker/training/test.jsonl
  s3://{bucket}/sagemaker/transform-input/{run_id}/predict.jsonl

Requires:
  pip install boto3 psycopg2-binary

Usage:
  python3 scripts/shared/export-training-data.py --env dev
  python3 scripts/shared/export-training-data.py --env dev --run-id <uuid> --predict-only
  python3 scripts/shared/export-training-data.py --env dev --dry-run

Schedule: Run nightly at 01:00 UTC (one hour before the 02:00 UTC ML Trigger).
"""

import argparse
import io
import json
from collections import defaultdict
from datetime import date, timedelta

import boto3
import psycopg2


PREDICTION_LENGTH = 30   # days forward DeepAR will forecast
CONTEXT_LENGTH    = 90   # days of history per prediction window
TEST_HOLDOUT      = 30   # last N days reserved for evaluation

# Categorical indices (must be consistent across train/test/predict)
SKU_ORDER = [
    "SKU-BEV-001", "SKU-BEV-002", "SKU-BEV-003", "SKU-BEV-004",
    "SKU-SNK-001", "SKU-SNK-002", "SKU-SNK-003", "SKU-SNK-004",
    "SKU-DRY-001", "SKU-DRY-002", "SKU-DRY-003", "SKU-DRY-004", "SKU-DRY-005",
    "SKU-CHL-001", "SKU-CHL-002", "SKU-CHL-003", "SKU-CHL-004", "SKU-CHL-005",
    "SKU-PRO-001", "SKU-PRO-002",
]
DC_ORDER = ["DC-LONDON", "DC-MANCHESTER", "DC-BIRMINGHAM"]


def get_ssm(ssm_client, env: str, key: str) -> str:
    return ssm_client.get_parameter(Name=f"/smartretail/{env}/{key}")["Parameter"]["Value"]


def get_secret_json(sm_client, secret_arn: str) -> dict:
    resp = sm_client.get_secret_value(SecretId=secret_arn)
    return json.loads(resp["SecretString"])


def connect_rds(secret: dict, endpoint: str) -> psycopg2.extensions.connection:
    return psycopg2.connect(
        host=endpoint,
        port=5432,
        dbname="smartretail",
        user=secret["username"],
        password=secret["password"],
    )


def fetch_daily_sales(conn) -> dict[tuple[str, str], dict[date, int]]:
    """Returns {(sku_id, dc_id): {sale_date: units}} from sales history."""
    sql = """
        SELECT sku_id, dc_id, DATE(sold_at) AS sale_date, SUM(quantity)::int AS units
        FROM sales.sales_transactions
        GROUP BY sku_id, dc_id, DATE(sold_at)
        ORDER BY sku_id, dc_id, sale_date
    """
    series: dict[tuple[str, str], dict[date, int]] = defaultdict(dict)
    with conn.cursor() as cur:
        cur.execute(sql)
        for sku_id, dc_id, sale_date, units in cur.fetchall():
            series[(sku_id, dc_id)][sale_date] = units
    return series


def build_target(daily: dict[date, int], start: date, end: date) -> list[int]:
    """Return contiguous daily integer list from start (inclusive) to end (exclusive), zero-fill gaps."""
    result = []
    d = start
    while d < end:
        result.append(daily.get(d, 0))
        d += timedelta(days=1)
    return result


def to_jsonlines(records: list[dict]) -> bytes:
    buf = io.BytesIO()
    for rec in records:
        buf.write(json.dumps(rec).encode())
        buf.write(b"\n")
    return buf.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env",          required=True, choices=["dev", "prod"])
    parser.add_argument("--region",       default="us-east-1")
    parser.add_argument("--profile",      default=None)
    parser.add_argument("--run-id",       default="manual-run",
                        help="RunId UUID for transform-input S3 prefix")
    parser.add_argument("--predict-only", action="store_true",
                        help="Skip train/test upload, only write predict.jsonl")
    parser.add_argument("--dry-run",      action="store_true",
                        help="Print stats without uploading")
    args = parser.parse_args()

    session    = boto3.Session(profile_name=args.profile, region_name=args.region)
    ssm        = session.client("ssm")
    sm_client  = session.client("secretsmanager")
    s3         = session.client("s3")

    bucket     = get_ssm(ssm, args.env, "s3/sagemaker-bucket-name")
    secret_arn = get_ssm(ssm, args.env, "rds/secret-arn")
    endpoint   = get_ssm(ssm, args.env, "rds/proxy-endpoint")

    print(f"Connecting to RDS via {endpoint}…")
    secret = get_secret_json(sm_client, secret_arn)
    conn   = connect_rds(secret, endpoint)

    print("Fetching daily sales from sales.sales_transactions…")
    daily_sales = fetch_daily_sales(conn)
    conn.close()

    if not daily_sales:
        raise SystemExit("No sales data found — run export after Flow 1 is active.")

    # Determine date range from actual data
    all_dates = [d for dates in daily_sales.values() for d in dates]
    data_start = min(all_dates)
    data_end   = max(all_dates) + timedelta(days=1)  # exclusive
    total_days = (data_end - data_start).days

    print(f"  Data range : {data_start} → {data_end - timedelta(days=1)} ({total_days} days)")
    print(f"  Series     : {len(daily_sales)}")

    # Train cutoff: exclude last TEST_HOLDOUT days for evaluation
    train_end = data_end - timedelta(days=TEST_HOLDOUT)

    train_records:   list[dict] = []
    test_records:    list[dict] = []
    predict_records: list[dict] = []

    for sku_id in SKU_ORDER:
        for dc_id in DC_ORDER:
            sku_idx = SKU_ORDER.index(sku_id)
            dc_idx  = DC_ORDER.index(dc_id)
            daily   = daily_sales.get((sku_id, dc_id), {})

            base = {
                "start":   data_start.isoformat(),
                "cat":     [sku_idx, dc_idx],
                "item_id": f"{sku_id}_{dc_id}",
            }

            train_records.append({
                **base,
                "target": build_target(daily, data_start, train_end),
            })
            test_records.append({
                **base,
                "target": build_target(daily, data_start, data_end),
            })

            # Predict window: last context_length days of history
            ctx_start = data_end - timedelta(days=CONTEXT_LENGTH)
            predict_records.append({
                "start":   ctx_start.isoformat(),
                "target":  build_target(daily, ctx_start, data_end),
                "cat":     [sku_idx, dc_idx],
                "item_id": f"{sku_id}_{dc_id}",
            })

    print(f"\n  train records  : {len(train_records)} × {(train_end - data_start).days} days")
    print(f"  test  records  : {len(test_records)} × {total_days} days")
    print(f"  predict records: {len(predict_records)} × {CONTEXT_LENGTH}-day context")

    if args.dry_run:
        print("\n[dry-run] No files uploaded.")
        return

    def upload(key: str, data: bytes) -> None:
        s3.put_object(Bucket=bucket, Key=key, Body=data, ContentType="application/jsonlines")
        print(f"  ✅  s3://{bucket}/{key}  ({len(data):,} bytes)")

    print(f"\nUploading to s3://{bucket}/…")

    if not args.predict_only:
        upload("sagemaker/training/train.jsonl", to_jsonlines(train_records))
        upload("sagemaker/training/test.jsonl",  to_jsonlines(test_records))

    upload(
        f"sagemaker/transform-input/{args.run_id}/predict.jsonl",
        to_jsonlines(predict_records),
    )

    print("\n✅  Training data export complete.")


if __name__ == "__main__":
    main()
