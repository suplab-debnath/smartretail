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

function toPascal(kebab: string): string {
  return kebab.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join('');
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
  public readonly websiteUrls: Record<string, string> = {};

  constructor(scope: Construct, id: string, props: HostingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-hosting-dev');

    const { srEnv, mfeBuckets } = props;

    // Default behavior: redirect root to /sc-planner/
    const defaultRedirectFn = new cloudfront.Function(this, 'DefaultRedirectFn', {
      functionName: `smartretail-${srEnv}-default-redirect`,
      code: cloudfront.FunctionCode.fromInline(
        "function handler(event) { return { statusCode: 302, statusDescription: 'Found', headers: { location: { value: '/sc-planner/' } } }; }"
      ),
      runtime: cloudfront.FunctionRuntime.JS_2_0,
    });

    const additionalBehaviors: Record<string, cloudfront.BehaviorOptions> = {};

    for (const mfe of MFE_NAMES) {
      const bucket = mfeBuckets[mfe];
      if (!bucket) throw new Error(`MFE bucket for '${mfe}' not found`);

      const oac = new cloudfront.S3OriginAccessControl(this, `${toPascal(mfe)}Oac`, {
        originAccessControlName: `smartretail-${srEnv}-${mfe}-oac`,
        signing: cloudfront.Signing.SIGV4_ALWAYS,
      });

      const rewriteFn = new cloudfront.Function(this, `${toPascal(mfe)}RewriteFn`, {
        functionName: `smartretail-${srEnv}-${mfe}-rewrite`,
        code: cloudfront.FunctionCode.fromInline(spaRewriteCode(`/${mfe}`)),
        runtime: cloudfront.FunctionRuntime.JS_2_0,
      });

      additionalBehaviors[`/${mfe}/*`] = {
        origin: origins.S3BucketOrigin.withOriginAccessControl(bucket, { originAccessControl: oac }),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
        compress: true,
        functionAssociations: [{
          function: rewriteFn,
          eventType: cloudfront.FunctionEventType.VIEWER_REQUEST,
        }],
      };
    }

    // Default behavior uses sc-planner bucket; CF Function intercepts before S3 is reached
    const defaultOac = new cloudfront.S3OriginAccessControl(this, 'DefaultOac', {
      originAccessControlName: `smartretail-${srEnv}-default-oac`,
      signing: cloudfront.Signing.SIGV4_ALWAYS,
    });

    const distribution = new cloudfront.Distribution(this, 'Distribution', {
      comment: `SmartRetail MFE portal (${srEnv})`,
      priceClass: cloudfront.PriceClass.PRICE_CLASS_100,
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(mfeBuckets['sc-planner'], { originAccessControl: defaultOac }),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
        functionAssociations: [{
          function: defaultRedirectFn,
          eventType: cloudfront.FunctionEventType.VIEWER_REQUEST,
        }],
      },
      additionalBehaviors,
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

    for (const mfe of MFE_NAMES) {
      const mfeUrl = `${this.distributionUrl}/${mfe}`;
      this.websiteUrls[mfe] = mfeUrl;

      new ssm.StringParameter(this, `${toPascal(mfe)}UrlParam`, {
        parameterName: `/smartretail/${srEnv}/hosting/${mfe}-url`,
        stringValue: mfeUrl,
      });

      new ssm.StringParameter(this, `${toPascal(mfe)}BucketNameParam`, {
        parameterName: `/smartretail/${srEnv}/hosting/${mfe}-bucket-name`,
        stringValue: mfeBuckets[mfe].bucketName,
      });
    }

    new cdk.CfnOutput(this, 'CloudFrontUrl', {
      value: this.distributionUrl,
      description: 'SmartRetail MFE portal CloudFront URL (HTTPS)',
      exportName: `smartretail-${srEnv}-cloudfront-url`,
    });
  }
}
