#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { NetworkStack }    from '../lib/network-stack';
import { DataStack }       from '../lib/data-stack';
import { MessagingStack }  from '../lib/messaging-stack';
import { IdentityStack }   from '../lib/identity-stack';
import { ComputeStack }    from '../lib/compute-stack';
import { ApiStack }        from '../lib/api-stack';
import { HostingStack }    from '../lib/hosting-stack';
import { MonitoringStack } from '../lib/monitoring-stack';

const app = new cdk.App();

const env     = process.env.SMARTRETAIL_ENV ?? 'demo';
const account = process.env.CDK_DEFAULT_ACCOUNT ?? process.env.AWS_ACCOUNT_ID;
const region  = process.env.CDK_DEFAULT_REGION  ?? 'us-east-1';
const cdkEnv  = { account, region };

// Common tags applied to every resource in every stack — use these to filter/destroy demo resources
cdk.Tags.of(app).add('Project',     'smartretail');
cdk.Tags.of(app).add('Variant',     'min');
cdk.Tags.of(app).add('ManagedBy',   'cdk');
cdk.Tags.of(app).add('Environment', env);
cdk.Tags.of(app).add('Lifecycle',   'ephemeral');  // easy filter for demo teardown

const network   = new NetworkStack  (app, 'Min-NetworkStack',   { env: cdkEnv, srEnv: env });
const data      = new DataStack     (app, 'Min-DataStack',      { env: cdkEnv, srEnv: env, network });
const messaging = new MessagingStack(app, 'Min-MessagingStack', { env: cdkEnv, srEnv: env });
const identity  = new IdentityStack (app, 'Min-IdentityStack',  { env: cdkEnv, srEnv: env });
const compute   = new ComputeStack  (app, 'Min-ComputeStack',   { env: cdkEnv, srEnv: env, network, data, messaging, identity });
const api       = new ApiStack      (app, 'Min-ApiStack',       { env: cdkEnv, srEnv: env, network, compute });
new HostingStack   (app, 'Min-HostingStack',    { env: cdkEnv, srEnv: env, mfeBuckets: data.mfeBuckets });
new MonitoringStack(app, 'Min-MonitoringStack', {
  env: cdkEnv, srEnv: env,
  compute, messaging, data, api,
  alertEmail: app.node.tryGetContext('alertEmail'),
});

app.synth();
