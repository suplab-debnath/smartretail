import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface HostingStackProps extends cdk.StackProps {
  srEnv: string;
  mfeBuckets: Record<string, s3.Bucket>;
}

const MFE_NAMES = ['store-manager', 'sc-planner', 'executive', 'supplier'] as const;
type MfeName = typeof MFE_NAMES[number];

function toPascal(kebab: string): string {
  return kebab.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join('');
}

export class HostingStack extends cdk.Stack {
  public readonly websiteUrls: Record<string, string> = {};

  constructor(scope: Construct, id: string, props: HostingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-hosting-prod');

    const { srEnv, mfeBuckets } = props;

    for (const mfe of MFE_NAMES) {
      const bucket = mfeBuckets[mfe];
      if (!bucket) throw new Error(`MFE bucket for '${mfe}' not found`);

      // OAC — CloudFront fetches from private S3 bucket without public-read
      const oac = new cloudfront.S3OriginAccessControl(this, `${toPascal(mfe)}Oac`, {
        originAccessControlName: `smartretail-${srEnv}-${mfe}-oac`,
        signing: cloudfront.Signing.SIGV4_ALWAYS,
      });

      const distribution = new cloudfront.Distribution(this, `${toPascal(mfe)}Distribution`, {
        defaultRootObject: 'index.html',
        defaultBehavior: {
          origin: origins.S3BucketOrigin.withOriginAccessControl(bucket, { originAccessControl: oac }),
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
          cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
          compress: true,
        },
        // SPA routing — let React Router handle 403/404 from S3
        errorResponses: [
          {
            httpStatus: 403,
            responseHttpStatus: 200,
            responsePagePath: '/index.html',
            ttl: cdk.Duration.seconds(0),
          },
          {
            httpStatus: 404,
            responseHttpStatus: 200,
            responsePagePath: '/index.html',
            ttl: cdk.Duration.seconds(0),
          },
        ],
        comment: `SmartRetail ${mfe} MFE (${srEnv})`,
      });

      const url = `https://${distribution.distributionDomainName}`;
      this.websiteUrls[mfe] = url;

      new ssm.StringParameter(this, `${toPascal(mfe)}UrlParam`, {
        parameterName: `/smartretail/${srEnv}/hosting/${mfe}-url`,
        stringValue: url,
      });

      new ssm.StringParameter(this, `${toPascal(mfe)}BucketNameParam`, {
        parameterName: `/smartretail/${srEnv}/hosting/${mfe}-bucket-name`,
        stringValue: bucket.bucketName,
      });

      new ssm.StringParameter(this, `${toPascal(mfe)}DistributionIdParam`, {
        parameterName: `/smartretail/${srEnv}/hosting/${mfe}-distribution-id`,
        stringValue: distribution.distributionId,
      });

      new cdk.CfnOutput(this, `${toPascal(mfe)}Url`, {
        value: url,
        description: `${mfe} MFE CloudFront URL (HTTPS)`,
        exportName: `smartretail-${srEnv}-${mfe}-url`,
      });
    }
  }
}
