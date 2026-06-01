#!/usr/bin/env python3
"""
Generate synthetic DeepAR training data and upload to S3.

Use this for first-time dev/demo setup when there is no real sales history yet.
For production, use export-training-data.py which reads from RDS.

The script generates 365 days of daily demand for 20 SKUs × 3 DCs = 60 time series.
Each series has a weekly seasonality pattern plus random noise. Values are integers
representing units sold per day.

Output (S3):
  s3://{bucket}/sagemaker/training/train.jsonl  — first 335 days per series
  s3://{bucket}/sagemaker/training/test.jsonl   — all 365 days (with last 30 as holdout)
  s3://{bucket}/sagemaker/transform-input/manual-run/predict.jsonl  — prediction windows

Usage:
  python3 scripts/shared/generate-synthetic-training-data.py --env dev [--region us-east-1] [--profile smartretail-dev]
  python3 scripts/shared/generate-synthetic-training-data.py --env dev --dry-run
"""

import argparse
import io
import json
import math
import random
from datetime import date, timedelta

import boto3


# ── SKU / DC catalogue (matches V7__seed_data.sql) ────────────────────────────
SKUS = [
    ("SKU-BEV-001", 130, 0), ("SKU-BEV-002", 115, 1), ("SKU-BEV-003",  90, 2),
    ("SKU-BEV-004",  75, 3), ("SKU-SNK-001",  95, 4), ("SKU-SNK-002",  80, 5),
    ("SKU-SNK-003",  65, 6), ("SKU-SNK-004",  55, 7), ("SKU-DRY-001", 110, 8),
    ("SKU-DRY-002",  85, 9), ("SKU-DRY-003",  70,10), ("SKU-DRY-004",  60,11),
    ("SKU-DRY-005",  50,12), ("SKU-CHL-001",  45,13), ("SKU-CHL-002",  40,14),
    ("SKU-CHL-003",  35,15), ("SKU-CHL-004",  30,16), ("SKU-CHL-005",  25,17),
    ("SKU-PRO-001",  20,18), ("SKU-PRO-002",  15,19),
]  # (sku_id, base_daily_demand, sku_index)

DCS = [
    ("DC-LONDON",     0, 1.0),
    ("DC-MANCHESTER", 1, 0.85),
    ("DC-BIRMINGHAM", 2, 0.75),
]  # (dc_id, dc_index, demand_multiplier)

# Training period
TOTAL_DAYS  = 365
TRAIN_DAYS  = TOTAL_DAYS - 30   # last 30 days held out for test evaluation
START_DATE  = date(2025, 1, 1)


def daily_demand(base: float, dc_mult: float, day_offset: int, rng: random.Random) -> int:
    # Weekly seasonality: higher Fri/Sat, lower Sun/Mon
    weekday    = (START_DATE + timedelta(days=day_offset)).weekday()
    seasonality = [0.85, 0.90, 1.00, 1.05, 1.20, 1.25, 0.75][weekday]
    # Slow growth trend
    trend = 1.0 + day_offset * 0.0003
    noise = rng.gauss(1.0, 0.12)
    raw   = base * dc_mult * seasonality * trend * noise
    return max(0, round(raw))


def build_series(sku_id: str, sku_idx: int, dc_id: str, dc_idx: int, dc_mult: float) -> dict:
    rng = random.Random(sku_idx * 100 + dc_idx)  # deterministic per series
    base = SKUS[sku_idx][1]
    target = [daily_demand(base, dc_mult, d, rng) for d in range(TOTAL_DAYS)]
    return {
        "start":  START_DATE.isoformat(),
        "target": target,
        "cat":    [sku_idx, dc_idx],
        "item_id": f"{sku_id}_{dc_id}",
    }


def to_jsonlines(records: list[dict]) -> bytes:
    buf = io.BytesIO()
    for rec in records:
        buf.write(json.dumps(rec).encode())
        buf.write(b"\n")
    return buf.getvalue()


def prediction_window(series: dict) -> dict:
    """Trim the series to `context_length` days for batch transform input."""
    context_days = 90
    return {
        "start":   series["start"],
        "target":  series["target"][-context_days:],
        "cat":     series["cat"],
        "item_id": series["item_id"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env",      required=True, choices=["dev", "prod", "demo"])
    parser.add_argument("--region",   default="us-east-1")
    parser.add_argument("--profile",  default=None)
    parser.add_argument("--dry-run",  action="store_true", help="Print stats without uploading")
    parser.add_argument("--run-id",   default="manual-run",
                        help="RunId to use for predict-input S3 prefix")
    args = parser.parse_args()

    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    ssm     = session.client("ssm")
    s3      = session.client("s3")

    bucket = ssm.get_parameter(Name=f"/smartretail/{args.env}/s3/sagemaker-bucket-name")[
        "Parameter"]["Value"]

    print(f"Generating synthetic training data for {len(SKUS)} SKUs × {len(DCS)} DCs…")

    all_series = []
    for sku_id, base, sku_idx in SKUS:
        for dc_id, dc_idx, dc_mult in DCS:
            all_series.append(build_series(sku_id, sku_idx, dc_id, dc_idx, dc_mult))

    # Train split: first TRAIN_DAYS of each series
    train_records = [
        {**s, "target": s["target"][:TRAIN_DAYS]} for s in all_series
    ]
    # Test split: full series (DeepAR evaluates on the last prediction_length days)
    test_records = all_series

    # Predict windows: last context_length days → DeepAR predicts next 30
    predict_records = [prediction_window(s) for s in all_series]

    print(f"  train series : {len(train_records)} × {TRAIN_DAYS} days")
    print(f"  test  series : {len(test_records)} × {TOTAL_DAYS} days")
    print(f"  predict wins : {len(predict_records)} × 90-day context")

    if args.dry_run:
        sample = train_records[0]
        print(f"\nSample series (first): item_id={sample['item_id']}")
        print(f"  start={sample['start']}  cat={sample['cat']}")
        print(f"  target[:7]={sample['target'][:7]}  … target[-3:]={sample['target'][-3:]}")
        print("\n[dry-run] No files uploaded.")
        return

    def upload(key: str, data: bytes) -> None:
        s3.put_object(Bucket=bucket, Key=key, Body=data, ContentType="application/jsonlines")
        print(f"  ✅  s3://{bucket}/{key}  ({len(data):,} bytes)")

    print(f"\nUploading to s3://{bucket}/…")
    upload("sagemaker/training/train.jsonl", to_jsonlines(train_records))
    upload("sagemaker/training/test.jsonl",  to_jsonlines(test_records))
    upload(
        f"sagemaker/transform-input/{args.run_id}/predict.jsonl",
        to_jsonlines(predict_records),
    )

    print("\n✅  Synthetic training data ready.")
    print(f"   Run the pipeline next:")
    print(f"   python3 scripts/shared/create-sagemaker-pipeline.py --env {args.env}")


if __name__ == "__main__":
    main()
