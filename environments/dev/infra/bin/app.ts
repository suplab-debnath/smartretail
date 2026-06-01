#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { NetworkStack }    from '../lib/network-stack';
import { DataStack }       from '../lib/data-stack';
import { MessagingStack }  from '../lib/messaging-stack';
import { HostingStack }    from '../lib/hosting-stack';
import { IdentityStack }   from '../lib/identity-stack';
import { ComputeStack }    from '../lib/compute-stack';
import { ApiStack }        from '../lib/api-stack';
import { MonitoringStack } from '../lib/monitoring-stack';

const app = new cdk.App();

cdk.Tags.of(app).add('Project',     'smartretail');
cdk.Tags.of(app).add('Variant',     'dev');
cdk.Tags.of(app).add('ManagedBy',   'cdk');

const env     = process.env.SMARTRETAIL_ENV ?? 'dev';
const account = process.env.CDK_DEFAULT_ACCOUNT ?? process.env.AWS_ACCOUNT_ID;
const region  = process.env.CDK_DEFAULT_REGION  ?? 'us-east-1';
const cdkEnv  = { account, region };

cdk.Tags.of(app).add('Environment', env);

const network   = new NetworkStack  (app, 'Dev-NetworkStack',   { env: cdkEnv, srEnv: env });
const data      = new DataStack     (app, 'Dev-DataStack',      { env: cdkEnv, srEnv: env, network });
const messaging = new MessagingStack(app, 'Dev-MessagingStack', { env: cdkEnv, srEnv: env });
// Hosting before Identity — Identity needs the CloudFront URL for Cognito callback URLs
const hosting   = new HostingStack  (app, 'Dev-HostingStack',   { env: cdkEnv, srEnv: env, mfeBuckets: data.mfeBuckets });
const identity  = new IdentityStack (app, 'Dev-IdentityStack',  { env: cdkEnv, srEnv: env, mfeBaseUrl: hosting.distributionUrl });
const compute   = new ComputeStack  (app, 'Dev-ComputeStack',   { env: cdkEnv, srEnv: env, network, data, messaging });
const api       = new ApiStack      (app, 'Dev-ApiStack',       { env: cdkEnv, srEnv: env, network, data, messaging, compute });
new MonitoringStack(app, 'Dev-MonitoringStack', {
  env: cdkEnv, srEnv: env,
  compute, messaging, data, api,
  alertEmail: app.node.tryGetContext('alertEmail'),
});

void identity;

app.synth();
