import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface HostingStackProps extends cdk.StackProps {
  srEnv: string;
  mfeBuckets: Record<string, s3.Bucket>;
}

const MFE_NAMES = ['store-manager', 'sc-planner', 'executive'] as const;
type MfeName = typeof MFE_NAMES[number];

function toPascal(kebab: string): string {
  return kebab.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join('');
}

export class HostingStack extends cdk.Stack {
  public readonly websiteUrls: Record<string, string> = {};

  constructor(scope: Construct, id: string, props: HostingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-hosting-demo');

    const { srEnv, mfeBuckets } = props;

    for (const mfe of MFE_NAMES) {
      const bucket = mfeBuckets[mfe];
      if (!bucket) throw new Error(`MFE bucket for '${mfe}' not found`);

      const url = bucket.bucketWebsiteUrl;
      this.websiteUrls[mfe] = url;

      new ssm.StringParameter(this, `${toPascal(mfe)}UrlParam`, {
        parameterName: `/smartretail/${srEnv}/hosting/${mfe}-url`,
        stringValue: url,
      });

      new ssm.StringParameter(this, `${toPascal(mfe)}BucketNameParam`, {
        parameterName: `/smartretail/${srEnv}/hosting/${mfe}-bucket-name`,
        stringValue: bucket.bucketName,
      });

      new cdk.CfnOutput(this, `${toPascal(mfe)}Url`, {
        value: url,
        description: `${mfe} MFE S3 website URL (HTTP only)`,
        exportName: `smartretail-${srEnv}-${mfe}-url`,
      });
    }
  }
}
