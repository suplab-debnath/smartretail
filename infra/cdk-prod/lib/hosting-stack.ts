import * as cdk from 'aws-cdk-lib';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface HostingStackProps extends cdk.StackProps {
  srEnv: string;
}

const MFE_NAMES = ['store-manager', 'sc-planner', 'executive'] as const;

function toPascal(kebab: string): string {
  return kebab.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join('');
}

export class HostingStack extends cdk.Stack {
  public readonly distributionUrls: Record<string, string> = {};

  constructor(scope: Construct, id: string, props: HostingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-hosting');

    const { srEnv } = props;
    const account = this.account;

    // MFE buckets are co-located with CloudFront in this stack.
    // S3BucketOrigin.withOriginAccessControl adds a bucket policy referencing the
    // CloudFront distribution ARN. Cross-stack (DataStack bucket + HostingStack CF)
    // would create a circular dependency, so both live here.
    for (const mfe of MFE_NAMES) {
      const bucket = new s3.Bucket(this, `${toPascal(mfe)}Bucket`, {
        bucketName: `smartretail-mfe-${srEnv}-${mfe}-${account}`,
        encryption: s3.BucketEncryption.S3_MANAGED,
        blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
        versioned: true,
        removalPolicy: cdk.RemovalPolicy.RETAIN,
      });

      const distribution = new cloudfront.Distribution(this, `${toPascal(mfe)}Distribution`, {
        comment: `SmartRetail ${mfe} MFE - ${srEnv}`,
        defaultBehavior: {
          origin: origins.S3BucketOrigin.withOriginAccessControl(bucket),
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
          cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_GET_HEAD_OPTIONS,
          compress: true,
        },
        defaultRootObject: 'index.html',
        errorResponses: [
          { httpStatus: 403, responseHttpStatus: 200, responsePagePath: '/index.html' },
          { httpStatus: 404, responseHttpStatus: 200, responsePagePath: '/index.html' },
        ],
        priceClass: cloudfront.PriceClass.PRICE_CLASS_100,
        minimumProtocolVersion: cloudfront.SecurityPolicyProtocol.TLS_V1_2_2021,
        enableLogging: false,
        webAclId: undefined,
      });

      const cfUrl = `https://${distribution.distributionDomainName}`;
      this.distributionUrls[mfe] = cfUrl;

      new ssm.StringParameter(this, `${toPascal(mfe)}DistIdParam`, {
        parameterName: `/smartretail/${srEnv}/cloudfront/${mfe}-distribution-id`,
        stringValue: distribution.distributionId,
      });
      new ssm.StringParameter(this, `${toPascal(mfe)}UrlParam`, {
        parameterName: `/smartretail/${srEnv}/cloudfront/${mfe}-url`,
        stringValue: cfUrl,
      });
      new ssm.StringParameter(this, `${toPascal(mfe)}BucketNameParam`, {
        parameterName: `/smartretail/${srEnv}/cloudfront/${mfe}-bucket-name`,
        stringValue: bucket.bucketName,
      });

      new cdk.CfnOutput(this, `${toPascal(mfe)}Url`, {
        value: cfUrl,
        description: `${mfe} MFE CloudFront URL`,
        exportName: `smartretail-${srEnv}-${mfe}-url`,
      });
    }
  }
}
