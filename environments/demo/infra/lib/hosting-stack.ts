import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface HostingStackProps extends cdk.StackProps {
  srEnv: string;
  mfeBuckets: Record<string, s3.Bucket>;
}

export class HostingStack extends cdk.Stack {
  public readonly scPlannerUrl: string;

  constructor(scope: Construct, id: string, props: HostingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-hosting-demo');

    const { srEnv, mfeBuckets } = props;

    const bucket = mfeBuckets['sc-planner'];
    if (!bucket) throw new Error("MFE bucket for 'sc-planner' not found");

    this.scPlannerUrl = bucket.bucketWebsiteUrl;

    new ssm.StringParameter(this, 'ScPlannerUrlParam', {
      parameterName: `/smartretail/${srEnv}/hosting/sc-planner-url`,
      stringValue: this.scPlannerUrl,
    });

    new ssm.StringParameter(this, 'ScPlannerBucketNameParam', {
      parameterName: `/smartretail/${srEnv}/hosting/sc-planner-bucket-name`,
      stringValue: bucket.bucketName,
    });

    new cdk.CfnOutput(this, 'ScPlannerUrl', {
      value: this.scPlannerUrl,
      description: 'SC Planner MFE S3 website URL (HTTP only)',
      exportName: `smartretail-${srEnv}-sc-planner-url`,
    });
  }
}
