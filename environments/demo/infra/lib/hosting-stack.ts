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

// Strips /{prefix} from URI; rewrites extensionless paths to /index.html for SPA routing.
function spaRewriteCode(prefix: string): string {
  const len = prefix.length;
  return [
    'function handler(event) {',
    '  var req = event.request;',
    '  var uri = req.uri;',
    `  uri = uri.substring(${len}) || '/';`,
    "  var seg = uri.slice(uri.lastIndexOf('/') + 1);",
    "  if (!seg || !seg.includes('.')) uri = '/index.html';",
    '  req.uri = uri;',
    '  return req;',
    '}',
  ].join('\n');
}

export class HostingStack extends cdk.Stack {
  public readonly distributionUrl: string;

  constructor(scope: Construct, id: string, props: HostingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-hosting-demo');

    const { srEnv, mfeBuckets } = props;

    const bucket = mfeBuckets['sc-planner'];
    if (!bucket) throw new Error("MFE bucket for 'sc-planner' not found");

    // Default behavior: redirect root to /sc-planner/
    const defaultRedirectFn = new cloudfront.Function(this, 'DefaultRedirectFn', {
      functionName: `smartretail-${srEnv}-default-redirect`,
      code: cloudfront.FunctionCode.fromInline(
        "function handler(event) { return { statusCode: 302, statusDescription: 'Found', headers: { location: { value: '/sc-planner/' } } }; }"
      ),
      runtime: cloudfront.FunctionRuntime.JS_2_0,
    });

    // /sc-planner/* behavior: OAC + SPA rewrite function
    const oac = new cloudfront.S3OriginAccessControl(this, 'ScPlannerOac', {
      originAccessControlName: `smartretail-${srEnv}-sc-planner-oac`,
      signing: cloudfront.Signing.SIGV4_ALWAYS,
    });

    const rewriteFn = new cloudfront.Function(this, 'ScPlannerRewriteFn', {
      functionName: `smartretail-${srEnv}-sc-planner-rewrite`,
      code: cloudfront.FunctionCode.fromInline(spaRewriteCode('/sc-planner')),
      runtime: cloudfront.FunctionRuntime.JS_2_0,
    });

    const scPlannerOrigin = origins.S3BucketOrigin.withOriginAccessControl(bucket, { originAccessControl: oac });

    const distribution = new cloudfront.Distribution(this, 'Distribution', {
      comment: `SmartRetail SC Planner MFE (${srEnv})`,
      priceClass: cloudfront.PriceClass.PRICE_CLASS_100,
      defaultBehavior: {
        origin: scPlannerOrigin,
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
        functionAssociations: [{
          function: defaultRedirectFn,
          eventType: cloudfront.FunctionEventType.VIEWER_REQUEST,
        }],
      },
      additionalBehaviors: {
        '/sc-planner/*': {
          origin: scPlannerOrigin,
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
          cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
          compress: true,
          functionAssociations: [{
            function: rewriteFn,
            eventType: cloudfront.FunctionEventType.VIEWER_REQUEST,
          }],
        },
      },
    });

    this.distributionUrl = `https://${distribution.distributionDomainName}`;

    new ssm.StringParameter(this, 'CloudFrontUrlParam', {
      parameterName: `/smartretail/${srEnv}/hosting/cloudfront-url`,
      stringValue: this.distributionUrl,
    });

    new ssm.StringParameter(this, 'DistributionIdParam', {
      parameterName: `/smartretail/${srEnv}/hosting/cloudfront-distribution-id`,
      stringValue: distribution.distributionId,
    });

    new ssm.StringParameter(this, 'ScPlannerUrlParam', {
      parameterName: `/smartretail/${srEnv}/hosting/sc-planner-url`,
      stringValue: `${this.distributionUrl}/sc-planner`,
    });

    new ssm.StringParameter(this, 'ScPlannerBucketNameParam', {
      parameterName: `/smartretail/${srEnv}/hosting/sc-planner-bucket-name`,
      stringValue: bucket.bucketName,
    });

    new cdk.CfnOutput(this, 'CloudFrontUrl', {
      value: this.distributionUrl,
      description: 'SC Planner MFE CloudFront URL (HTTPS)',
      exportName: `smartretail-${srEnv}-cloudfront-url`,
    });
  }
}
