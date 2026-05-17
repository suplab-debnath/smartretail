import * as cdk from 'aws-cdk-lib';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface HostingStackProps extends cdk.StackProps {
  srEnv: string;
  mfeBuckets: Record<string, s3.Bucket>;
}

const MFE_NAMES = ['store-manager', 'sc-planner', 'executive', 'demo'] as const;
type MfeName = typeof MFE_NAMES[number];

export class HostingStack extends cdk.Stack {
  public readonly distributionUrls: Record<string, string> = {};

  constructor(scope: Construct, id: string, props: HostingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-hosting');

    const { srEnv, mfeBuckets } = props;

    for (const mfe of MFE_NAMES) {
      const bucket = mfeBuckets[mfe];
      if (!bucket) {
        throw new Error(`MFE bucket for '${mfe}' not found in mfeBuckets`);
      }

      const distribution = new cloudfront.Distribution(this, `${toPascal(mfe)}Distribution`, {
        comment: `SmartRetail ${mfe} MFE - ${srEnv}`,
        defaultBehavior: {
          origin: origins.S3BucketOrigin.withOriginAccessControl(bucket),
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
          cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_GET_HEAD_OPTIONS,
          compress: true,                    // Enable gzip/brotli
        },
        defaultRootObject: 'index.html',
        errorResponses: [
          { httpStatus: 403, responseHttpStatus: 200, responsePagePath: '/index.html' },
          { httpStatus: 404, responseHttpStatus: 200, responsePagePath: '/index.html' },
        ],
        priceClass: cloudfront.PriceClass.PRICE_CLASS_100,  // Cheapest
        minimumProtocolVersion: cloudfront.SecurityPolicyProtocol.TLS_V1_2_2021,
        enableLogging: false,   // Disabled for POC (saves cost)
        webAclId: undefined,    // No WAF for POC
      });

      const cfUrl = `https://${distribution.distributionDomainName}`;
      this.distributionUrls[mfe] = cfUrl;

      // SSM Parameters
      new ssm.StringParameter(this, `${toPascal(mfe)}DistId`, {
        parameterName: `/smartretail/${srEnv}/cloudfront/${mfe}-distribution-id`,
        stringValue: distribution.distributionId,
      });
      new ssm.StringParameter(this, `${toPascal(mfe)}Url`, {
        parameterName: `/smartretail/${srEnv}/cloudfront/${mfe}-url`,
        stringValue: cfUrl,
      });
      new ssm.StringParameter(this, `${toPascal(mfe)}BucketName`, {
        parameterName: `/smartretail/${srEnv}/cloudfront/${mfe}-bucket-name`,
        stringValue: bucket.bucketName,
      });

      // CloudFormation Output
      new cdk.CfnOutput(this, `${toPascal(mfe)}Url`, {
        value: cfUrl,
        description: `${mfe} MFE CloudFront URL`,
        exportName: `smartretail-${srEnv}-${mfe}-url`,
      });
    }
  }
}

function toPascal(kebab: string): string {
  return kebab.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join('');
}
