#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { NetworkStack }  from '../lib/network-stack';
import { DataStack }     from '../lib/data-stack';
import { MessagingStack } from '../lib/messaging-stack';
import { IdentityStack } from '../lib/identity-stack';
import { ComputeStack }  from '../lib/compute-stack';
import { ApiStack }      from '../lib/api-stack';
import { HostingStack }  from '../lib/hosting-stack';

const app = new cdk.App();

cdk.Tags.of(app).add('Project', 'smartretail');
cdk.Tags.of(app).add('Variant', 'prod');
cdk.Tags.of(app).add('ManagedBy', 'cdk');

const env     = process.env.SMARTRETAIL_ENV ?? 'prod';
const account = process.env.CDK_DEFAULT_ACCOUNT ?? process.env.AWS_ACCOUNT_ID;
const region  = process.env.CDK_DEFAULT_REGION  ?? 'us-east-1';
const cdkEnv  = { account, region };

const network  = new NetworkStack (app, 'Prod-NetworkStack',  { env: cdkEnv, srEnv: env });
const data     = new DataStack    (app, 'Prod-DataStack',     { env: cdkEnv, srEnv: env, network });
const messaging = new MessagingStack(app, 'Prod-MessagingStack', { env: cdkEnv, srEnv: env });
const identity  = new IdentityStack (app, 'Prod-IdentityStack',  { env: cdkEnv, srEnv: env });
const compute   = new ComputeStack  (app, 'Prod-ComputeStack',   { env: cdkEnv, srEnv: env, network, data, messaging });
new ApiStack    (app, 'Prod-ApiStack',     { env: cdkEnv, srEnv: env, network, data, compute });
new HostingStack(app, 'Prod-HostingStack', { env: cdkEnv, srEnv: env, mfeBuckets: data.mfeBuckets });

// Suppress unused variable warnings — identity exported for future JWT middleware use
void identity;

app.synth();
