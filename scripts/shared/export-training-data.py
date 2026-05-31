#!/usr/bin/env python3
"""
Build DeepAR training data from raw POS events stored in S3 by Firehose.

Firehose delivers every POS event to two destinations simultaneously:
  1. HTTP endpoint → SIS (ingestion into RDS)
  2. S3 events bucket (raw JSON, prefix firehose/{yyyy/MM/dd}/)

This script reads the raw S3 events, aggregates daily demand per SKU × DC,
and writes DeepAR JSON Lines files to the SageMaker training prefix.

Requires:
  pip install boto3

Usage:
  python3 scripts/shared/export-training-data.py --env dev
  python3 scripts/shared/export-training-data.py --env dev --since 2025-01-01
  python3 scripts/shared/export-training-data.py --env dev --run-id <uuid> --predict-only
  python3 scripts/shared/export-training-data.py --env dev --dry-run

Schedule: Run nightly at 01:00 UTC (one hour before the 02:00 UTC ML Trigger Lambda).
"""

import argparse
import io
import json
from collections import defaultdict
from datetime import date, timedelta

import boto3


PREDICTION_LENGTH = 30   # days DeepAR will forecast forward
CONTEXT_LENGTH    = 90   # days of history per prediction window
TEST_HOLDOUT      = 30   # last N days reserved for evaluation

# Categorical indices — must be consistent across train/test/predict
SKU_ORDER = [
    "SKU-BEV-001", "SKU-BEV-002", "SKU-BEV-003", "SKU-BEV-004",
    "SKU-SNK-001", "SKU-SNK-002", "SKU-SNK-003", "SKU-SNK-004",
    "SKU-DRY-001", "SKU-DRY-002", "SKU-DRY-003", "SKU-DRY-004", "SKU-DRY-005",
    "SKU-CHL-001", "SKU-CHL-002", "SKU-CHL-003", "SKU-CHL-004", "SKU-CHL-005",
    "SKU-PRO-001", "SKU-PRO-002",
]
DC_ORDER = ["DC-LONDON", "DC-MANCHESTER", "DC-BIRMINGHAM"]

# Store → DC mapping (from seed data)
STORE_TO_DC: dict[str, str] = {
    "STORE-001": "DC-LONDON",
    "STORE-002": "DC-MANCHESTER",
    "STORE-003": "DC-BIRMINGHAM",
}


def get_ssm(ssm_client, env: str, key: str) -> str:
    return ssm_client.get_parameter(Name=f"/smartretail/{env}/{key}")["Parameter"]["Value"]


def list_s3_objects(s3, bucket: str, prefix: str, since: date | None) -> list[str]:
    """Return all S3 keys under prefix, optionally filtered by date (yyyy/MM/dd subfolder)."""
    paginator = s3.get_paginator("list_objects_v2")
    keys = []
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            key = obj["Key"]
            if since is not None:
                # key pattern: firehose/yyyy/MM/dd/...
                parts = key.split("/")
                if len(parts) >= 4:
                    try:
                        obj_date = date(int(parts[1]), int(parts[2]), int(parts[3]))
                        if obj_date < since:
                            continue
                    except (ValueError, IndexError):
                        pass
            keys.append(key)
    return keys


def parse_events_file(body: bytes) -> list[dict]:
    """Parse a Firehose S3 file — each line is one raw POS event JSON."""
    events = []
    for line in body.decode("utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return events


def aggregate_sales(
    s3, bucket: str, keys: list[str]
) -> dict[tuple[str, str], dict[date, int]]:
    """
    Read all Firehose S3 files and return aggregated daily demand.
    Returns: {(sku_id, dc_id): {sale_date: total_units}}
    """
    totals: dict[tuple[str, str], dict[date, int]] = defaultdict(lambda: defaultdict(int))

    for key in keys:
        resp = s3.get_object(Bucket=bucket, Key=key)
        events = parse_events_file(resp["Body"].read())
        for ev in events:
            sku_id   = ev.get("skuId")
            store_id = ev.get("storeId")
            quantity = ev.get("quantity", 0)
            ts       = ev.get("soldAt") or ev.get("timestamp")

            if not sku_id or not store_id or not ts:
                continue
            dc_id = STORE_TO_DC.get(store_id)
            if dc_id is None:
                continue

            try:
                sale_date = date.fromisoformat(ts[:10])
            except (ValueError, TypeError):
                continue

            totals[(sku_id, dc_id)][sale_date] += int(quantity)

    return totals


def build_target(daily: dict[date, int], start: date, end: date) -> list[int]:
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
    parser.add_argument("--since",        default=None,
                        help="Only read events on/after this date (YYYY-MM-DD). "
                             "Default: read all available.")
    parser.add_argument("--run-id",       default="manual-run",
                        help="RunId UUID for transform-input S3 prefix")
    parser.add_argument("--predict-only", action="store_true",
                        help="Skip train/test upload, only write predict.jsonl")
    parser.add_argument("--dry-run",      action="store_true",
                        help="Print stats without uploading")
    args = parser.parse_args()

    since = date.fromisoformat(args.since) if args.since else None

    session      = boto3.Session(profile_name=args.profile, region_name=args.region)
    ssm          = session.client("ssm")
    s3           = session.client("s3")

    events_bucket    = get_ssm(ssm, args.env, "s3/events-bucket-name")
    sagemaker_bucket = get_ssm(ssm, args.env, "s3/sagemaker-bucket-name")

    print(f"Reading POS events from s3://{events_bucket}/firehose/…")
    keys = list_s3_objects(s3, events_bucket, "firehose/", since)
    print(f"  Found {len(keys)} Firehose files{f' since {since}' if since else ''}")

    if not keys:
        raise SystemExit(
            "No Firehose event files found. "
            "Ensure Firehose s3BackupMode=AllData and Flow 1 has run at least once. "
            "For a fresh environment use generate-synthetic-training-data.py instead."
        )

    print("Aggregating daily demand…")
    daily_sales = aggregate_sales(s3, events_bucket, keys)
    print(f"  Aggregated {len(daily_sales)} SKU × DC series")

    all_dates = [d for series in daily_sales.values() for d in series]
    data_start = min(all_dates)
    data_end   = max(all_dates) + timedelta(days=1)
    total_days = (data_end - data_start).days
    train_end  = data_end - timedelta(days=TEST_HOLDOUT)

    print(f"  Date range: {data_start} → {data_end - timedelta(days=1)} ({total_days} days)")
    print(f"  Train ends: {train_end - timedelta(days=1)}  "
          f"Test holdout: last {TEST_HOLDOUT} days")

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
            train_records.append({**base, "target": build_target(daily, data_start, train_end)})
            test_records.append({**base, "target": build_target(daily, data_start, data_end)})

            ctx_start = data_end - timedelta(days=CONTEXT_LENGTH)
            predict_records.append({
                "start":   ctx_start.isoformat(),
                "target":  build_target(daily, ctx_start, data_end),
                "cat":     [sku_idx, dc_idx],
                "item_id": f"{sku_id}_{dc_id}",
            })

    print(f"\n  train  : {len(train_records)} series × {(train_end - data_start).days} days")
    print(f"  test   : {len(test_records)} series × {total_days} days")
    print(f"  predict: {len(predict_records)} series × {CONTEXT_LENGTH}-day context")

    if args.dry_run:
        print("\n[dry-run] No files uploaded.")
        return

    def upload(key: str, data: bytes) -> None:
        s3.put_object(
            Bucket=sagemaker_bucket, Key=key, Body=data,
            ContentType="application/jsonlines",
        )
        print(f"  ✅  s3://{sagemaker_bucket}/{key}  ({len(data):,} bytes)")

    print(f"\nUploading to s3://{sagemaker_bucket}/…")
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
