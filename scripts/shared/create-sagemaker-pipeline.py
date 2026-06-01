#!/usr/bin/env python3
"""
Create (or upsert) the SmartRetail DeepAR demand forecast SageMaker Pipeline.

The pipeline name must match the value the ML Trigger Lambda reads from SSM:
  /smartretail/{env}/sagemaker/pipeline-name  →  smartretail-demand-forecast-{env}

The pipeline expects training data to already exist at:
  s3://{bucket}/sagemaker/training/train.jsonl
  s3://{bucket}/sagemaker/training/test.jsonl

Run export-training-data.py (or generate-synthetic-training-data.py for dev/demo)
before triggering the first pipeline execution.

Usage:
  python3 scripts/shared/create-sagemaker-pipeline.py --env dev [--region us-east-1] [--profile smartretail-dev]
"""

import argparse
import json
import boto3
import sagemaker
from sagemaker.workflow.pipeline import Pipeline
from sagemaker.workflow.steps import TrainingStep, TransformStep
from sagemaker.workflow.parameters import ParameterString
from sagemaker.inputs import TrainingInput, TransformInput
from sagemaker.estimator import Estimator
from sagemaker.transformer import Transformer


def get_ssm(ssm_client, env: str, key: str) -> str:
    resp = ssm_client.get_parameter(Name=f"/smartretail/{env}/{key}")
    return resp["Parameter"]["Value"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env",     required=True, choices=["dev", "prod"])
    parser.add_argument("--region",  default="us-east-1")
    parser.add_argument("--profile", default=None)
    args = parser.parse_args()

    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    ssm     = session.client("ssm")
    sm_boto = session.client("sagemaker")

    bucket        = get_ssm(ssm, args.env, "s3/sagemaker-bucket-name")
    role_arn      = get_ssm(ssm, args.env, "sagemaker/execution-role-arn")
    pipeline_name = get_ssm(ssm, args.env, "sagemaker/pipeline-name")

    sm_session = sagemaker.Session(boto_session=session, sagemaker_client=sm_boto)

    print(f"Pipeline : {pipeline_name}")
    print(f"Bucket   : s3://{bucket}")
    print(f"Role     : {role_arn}")

    # ── Pipeline parameter ────────────────────────────────────────────────────
    # The ML Trigger Lambda passes this when calling StartPipelineExecution.
    # It namespaces S3 paths so each daily run is isolated.
    run_id = ParameterString(name="RunId", default_value="manual-run")

    # ── DeepAR training container (built-in algorithm) ────────────────────────
    # Image URI format: {account}.dkr.ecr.{region}.amazonaws.com/forecasting:{tag}
    deepar_image = sagemaker.image_uris.retrieve(
        framework="forecasting-deepar",
        region=args.region,
        version="1",
    )

    estimator = Estimator(
        image_uri=deepar_image,
        role=role_arn,
        instance_count=1,
        instance_type="ml.c5.2xlarge",
        output_path=f"s3://{bucket}/sagemaker/models",
        base_job_name="smartretail-deepar",
        sagemaker_session=sm_session,
        hyperparameters={
            # Target: daily demand units per SKU × DC
            "time_freq":         "D",
            # Days to forecast forward (must match DFS horizonDays max = 30)
            "prediction_length": "30",
            # How many days of history the model sees per prediction window
            "context_length":    "90",
            # Model capacity
            "num_cells":         "40",
            "num_layers":        "2",
            # Training duration
            "epochs":            "100",
            "mini_batch_size":   "64",
            # Negative-binomial handles integer demand with overdispersion
            "likelihood":        "negative-binomial",
            # Cardinality: 20 SKUs × 3 DCs = two categorical features
            "cardinality":       "auto",
            # Output P10/P50/P90 quantiles
            "num_eval_samples":  "100",
            "test_quantiles":    "[0.1, 0.5, 0.9]",
        },
    )

    training_step = TrainingStep(
        name="DeepArTraining",
        estimator=estimator,
        inputs={
            "train": TrainingInput(
                s3_data=f"s3://{bucket}/sagemaker/training/train.jsonl",
                content_type="application/jsonlines",
                s3_data_type="S3Prefix",
            ),
            "test": TrainingInput(
                s3_data=f"s3://{bucket}/sagemaker/training/test.jsonl",
                content_type="application/jsonlines",
                s3_data_type="S3Prefix",
            ),
        },
    )

    # ── Batch Transform ───────────────────────────────────────────────────────
    # Input: one JSON Lines file per run containing the prediction windows.
    # Output path includes RunId so the Batch Post-Processor Lambda can extract
    # it from the S3 key: sagemaker/output/{RunId}/part-*.csv
    transformer = Transformer(
        model_name=training_step.properties.ModelArtifacts.S3ModelArtifacts,
        instance_count=1,
        instance_type="ml.c5.xlarge",
        output_path=f"s3://{bucket}/sagemaker/output/{run_id}",
        accept="text/csv",
        assemble_with="Line",
        sagemaker_session=sm_session,
    )

    transform_step = TransformStep(
        name="DeepArBatchTransform",
        transformer=transformer,
        inputs=TransformInput(
            data=f"s3://{bucket}/sagemaker/transform-input/{run_id}/predict.jsonl",
            content_type="application/jsonlines",
            split_type="Line",
        ),
        depends_on=[training_step],
    )

    # ── Pipeline definition ───────────────────────────────────────────────────
    pipeline = Pipeline(
        name=pipeline_name,
        parameters=[run_id],
        steps=[training_step, transform_step],
        sagemaker_session=sm_session,
    )

    print("\nUpserting pipeline definition…")
    pipeline.upsert(role_arn=role_arn)
    print(f"✅  Pipeline '{pipeline_name}' created/updated successfully.")
    print(
        "\nNext steps:"
        f"\n  1. Upload training data:  python3 scripts/shared/export-training-data.py --env {args.env}"
        f"\n  2. Upload predict input:  python3 scripts/shared/export-training-data.py --env {args.env} --predict-only"
        f"\n  3. Test trigger:          aws lambda invoke --function-name smartretail-ml-trigger-{args.env} /tmp/out.json"
    )


if __name__ == "__main__":
    main()
