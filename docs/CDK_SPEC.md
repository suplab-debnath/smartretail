# CDK Infrastructure Specification
 
All CDK stacks are in TypeScript.

- **Demo / dev stack** (`environments/demo/infra/`) — SQS-only, reuses default VPC. **This is the stack to run.** Stack names: `Min-*`.
- **Production stack** (`environments/prod/infra/`) — Firehose, dedicated VPC. Not wired into Makefile. Stack names: `Prod-*`.

The specifications below describe `environments/demo/infra/` (the demo stack).
Deploy in order: Network → Data → Messaging → Identity → Compute → API.
 
---

## Stack Variants

| Stack | Path | Purpose | CPU arch | Stack prefix |
|-------|------|---------|----------|-------------|
| **cdk-demo** | `environments/demo/infra/` | Demo only — SQS, default VPC, ARM64, cheap | ARM64 | `Min-*` |
| **cdk-dev** | `environments/dev/infra/` | Full dev — Firehose, 2-AZ VPC, RDS Proxy, CloudFront, X86_64 | X86_64 | `Dev-*` |
| **cdk-prod** | `environments/prod/infra/` | Production — Firehose, 3-AZ VPC, Multi-AZ RDS, RDS Proxy, CloudFront | X86_64 | `Prod-*` |

**cdk-demo** is the only stack wired into the Makefile (`dev-*` targets). Use it for local/demo deploys.
**cdk-dev** and **cdk-prod** are deployed manually from their respective directories.

### Dev vs Prod Sizing Comparison

`cdk-dev` and `cdk-prod` use the same services and AWS config patterns. Only sizing differs:

| Dimension | cdk-dev | cdk-prod |
|-----------|---------|---------|
| VPC AZs | 2 | 3 |
| NAT Gateways | 1 | 3 |
| RDS instance | t4g.small | r6g.large |
| RDS Multi-AZ | No | Yes |
| RDS backup retention | 1 day | 7 days |
| ECS task CPU/mem | 256 / 512 MB | 512 / 1024 MB |
| ECS desired count | 1 | 2 |
| ECS autoscale max | 3 | 6 |
| Container insights | Disabled | Enabled |
| Log retention | 1 week | 1 month |

Both stacks deploy 7 ECS services (SIS, IMS, RE, ARS, DFS, SUP, PPS), 4 MFE CloudFront
distributions (store-manager, sc-planner, executive, supplier), 2 Cognito pools
(internal + supplier), Firehose delivery stream, and RDS Proxy.

---

 
## Stack 1: NetworkStack
 
File: `environments/demo/infra/lib/network-stack.ts`
 
Creates:
- VPC with 3 AZs, DNS hostnames enabled
- Public subnets (1 per AZ) — NAT Gateways only
- Private app subnets (1 per AZ) — ECS tasks
- Private data subnets (1 per AZ) — RDS
- Internet Gateway
- 3 NAT Gateways (one per AZ)
- Security Groups (see below)
- VPC Endpoints (see below)
 
### VPC Configuration
```typescript
const vpc = new ec2.Vpc(this, 'SmartRetailVpc', {
  vpcName: `smartretail-vpc-${env}`,
  maxAzs: 3,
  natGateways: 3,
  subnetConfiguration: [
    {
      name: 'Public',
      subnetType: ec2.SubnetType.PUBLIC,
      cidrMask: 24,
    },
    {
      name: 'PrivateApp',
      subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
      cidrMask: 24,
    },
    {
      name: 'PrivateData',
      subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
      cidrMask: 24,
    },
  ],
  enableDnsHostnames: true,
  enableDnsSupport: true,
});
```
 
### Security Groups
 
```
sg-api-gateway-link:
  Description: VPC Link from API Gateway to ECS
  Inbound: TCP 8080 from 0.0.0.0/0 (API Gateway managed)
  Outbound: all
 
sg-ecs-tasks:
  Description: ECS task security group
  Inbound: TCP 8080 from sg-api-gateway-link only
  Outbound: TCP 5432 to sg-rds-proxy (RDS Proxy)
            TCP 443 to VPC Endpoints
            TCP 443 to 0.0.0.0/0 via NAT (for AWS SDK calls not covered by endpoints)
 
sg-rds-proxy:
  Description: RDS Proxy security group
  Inbound: TCP 5432 from sg-ecs-tasks only
  Outbound: TCP 5432 to sg-rds
 
sg-rds:
  Description: RDS instance security group
  Inbound: TCP 5432 from sg-rds-proxy only
  Outbound: none
 
sg-vpc-endpoints:
  Description: VPC Interface Endpoints
  Inbound: TCP 443 from sg-ecs-tasks only
  Outbound: none
```
 
### VPC Endpoints
 
```typescript
// Gateway endpoints (free)
vpc.addGatewayEndpoint('S3Endpoint', { service: ec2.GatewayVpcEndpointAwsService.S3 });
// DynamoDB Gateway endpoint removed — DynamoDB not provisioned
 
// Interface endpoints (charged per hour)
const endpointServices = [
  ec2.InterfaceVpcEndpointAwsService.SQS,
  ec2.InterfaceVpcEndpointAwsService.EVENTBRIDGE,
  ec2.InterfaceVpcEndpointAwsService.KMS,
  ec2.InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
  ec2.InterfaceVpcEndpointAwsService.ECR,
  ec2.InterfaceVpcEndpointAwsService.ECR_DOCKER,
  ec2.InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
];
// Add each with sg-vpc-endpoints and private subnets
```
 
### Outputs (stored in Parameter Store)
```
/smartretail/{env}/network/vpc-id
/smartretail/{env}/network/private-app-subnet-ids
/smartretail/{env}/network/private-data-subnet-ids
/smartretail/{env}/network/sg-ecs-tasks-id
/smartretail/{env}/network/sg-rds-proxy-id
/smartretail/{env}/network/sg-rds-id
/smartretail/{env}/network/sg-api-gateway-link-id
```
 
---
 
## Stack 2: DataStack
 
File: `environments/demo/infra/lib/data-stack.ts`
 
Creates:
- RDS PostgreSQL (single-AZ in demo; Multi-AZ in prod)
- S3 buckets
- Secrets Manager secret for Firehose ingest access key
> Note: DynamoDB idempotency table removed — idempotency uses `sales.idempotency_keys` in RDS (Flyway V1).
> Note: demo stack has no RDS Proxy — ECS tasks connect directly to the RDS instance endpoint. RDS Proxy is only in cdk-dev and cdk-prod.
 
### RDS PostgreSQL
 
```typescript
// Demo stack: t4g.micro, single-AZ, public subnet (default VPC has no isolated subnets)
// cdk-dev: t4g.small; cdk-prod: r6g.large, multiAz: true, PRIVATE_ISOLATED subnet
const rdsInstance = new rds.DatabaseInstance(this, 'Rds', {
  instanceIdentifier: `smartretail-rds-${srEnv}`,
  engine: rds.DatabaseInstanceEngine.postgres({
    version: rds.PostgresEngineVersion.VER_16_13,
  }),
  instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
  allocatedStorage: 20,
  vpc: network.vpc,
  vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
  securityGroups: [network.sgRds],
  multiAz: false,
  databaseName: 'smartretail',
  credentials: rds.Credentials.fromGeneratedSecret('smartretail_admin', {
    secretName: `smartretail-rds-secret-${srEnv}`,
  }),
  backupRetention: cdk.Duration.days(1),
  deletionProtection: false,
  removalPolicy: cdk.RemovalPolicy.DESTROY,
  enablePerformanceInsights: false,
  storageEncrypted: true,
  cloudwatchLogsExports: ['postgresql'],
  cloudwatchLogsRetention: logs.RetentionDays.TWO_WEEKS,
});
```
 
### RDS Proxy (cdk-dev and cdk-prod only)
 
The demo stack connects directly to the RDS instance endpoint. cdk-dev and cdk-prod
deploy an RDS Proxy so ECS tasks use IAM auth and connection pooling.
 
```typescript
const rdsProxy = new rds.DatabaseProxy(this, 'SmartRetailRdsProxy', {
  proxyTarget: rds.ProxyTarget.fromInstance(rdsInstance),
  vpc,
  vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
  securityGroups: [sgRdsProxy],
  iamAuth: true,
  requireTLS: true,
  idleClientTimeout: cdk.Duration.minutes(30),
  dbProxyName: `smartretail-proxy-${env}`,
});
```
 
### Firehose Ingest Access Key (Secrets Manager)

```typescript
const firehoseAccessKey = new secretsmanager.Secret(this, 'FirehoseIngestKey', {
  secretName: `/smartretail/${env}/firehose/ingest-access-key`,
  generateSecretString: { excludePunctuation: true, passwordLength: 64 },
  removalPolicy: cdk.RemovalPolicy.DESTROY,
});
```

### S3 Buckets
 
```typescript
// Events archive
new s3.Bucket(this, 'EventsBucket', {
  bucketName: `smartretail-events-${env}-${account}`,
  encryption: s3.BucketEncryption.S3_MANAGED,
  blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
  versioned: true,
  lifecycleRules: [{ expiration: cdk.Duration.days(365 * 7) }],
  removalPolicy: cdk.RemovalPolicy.RETAIN,
});
 
// SageMaker output bucket — transform output CSV triggers Batch Post-Processor Lambda
// Key prefix convention: sagemaker/output/{run_id}/part-*.csv
new s3.Bucket(this, 'SageMakerBucket', {
  bucketName: `smartretail-sagemaker-${env}-${account}`,
  encryption: s3.BucketEncryption.S3_MANAGED,
  blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
  versioned: true,
  lifecycleRules: [{ expiration: cdk.Duration.days(365 * 3) }],
  removalPolicy: cdk.RemovalPolicy.RETAIN,
});
 
// MFE static assets
// Demo stack: sc-planner bucket only (HostingStack serves it via CloudFront)
// cdk-dev / cdk-prod: all 4 buckets (store-manager, sc-planner, executive, supplier-portal)
['store-manager', 'sc-planner', 'executive', 'supplier-portal'].forEach(mfe => {
  new s3.Bucket(this, `MfeBucket${mfe}`, {
    bucketName: `smartretail-mfe-${env}-${mfe}-${account}`,
    encryption: s3.BucketEncryption.S3_MANAGED,
    blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
    versioned: true,
    removalPolicy: cdk.RemovalPolicy.DESTROY,
  });
});
```
 
### Outputs
```
/smartretail/{env}/rds/proxy-endpoint
/smartretail/{env}/rds/instance-endpoint
/smartretail/{env}/rds/secret-arn
/smartretail/{env}/firehose/ingest-access-key (Secrets Manager ARN)
/smartretail/{env}/s3/events-bucket-name
/smartretail/{env}/s3/sagemaker-bucket-name
/smartretail/{env}/s3/mfe-store-manager-bucket
/smartretail/{env}/s3/mfe-sc-planner-bucket
/smartretail/{env}/s3/mfe-executive-bucket
```
 
---
 
## Stack 3: MessagingStack
 
File: `environments/demo/infra/lib/messaging-stack.ts`
 
Creates:
- Amazon Data Firehose delivery stream
- EventBridge custom bus
- SQS queues with DLQs
- EventBridge rules routing to SQS
 
### Amazon Data Firehose

```typescript
const deliveryStream = new firehose.CfnDeliveryStream(this, 'SmartRetailIngest', {
  deliveryStreamName: `smartretail-ingest-${env}`,
  deliveryStreamType: 'DirectPut',
  httpEndpointDestinationConfiguration: {
    endpointConfiguration: {
      url: `https://${apiGatewayDomain}/ingest/v1/ingest/events`,
      accessKey: firehoseAccessKey.secretValue.unsafeUnwrap(),
    },
    retryOptions: { durationInSeconds: 86400 },
    s3BackupMode: 'AllData',
    roleArn: firehoseDeliveryRole.roleArn,
  },
  extendedS3DestinationConfiguration: {
    bucketArn: eventsBucket.bucketArn,
    prefix: 'events/!{timestamp:yyyy/MM/dd}/',
    errorOutputPrefix: 'firehose-backup/',
    bufferingHints: { intervalInSeconds: 60, sizeInMBs: 5 },
    roleArn: firehoseDeliveryRole.roleArn,
  },
});
```

### EventBridge
 
```typescript
const bus = new events.EventBus(this, 'SmartRetailBus', {
  eventBusName: `smartretail-events-${env}`,
});
```
 
### SQS Queues
 
```typescript
// IMS sales events queue
const imsSalesDlq = new sqs.Queue(this, 'ImsSalesDlq', {
  queueName: `smartretail-ims-sales-${env}-dlq`,
  retentionPeriod: cdk.Duration.days(14),
});
const imsSalesQueue = new sqs.Queue(this, 'ImsSalesQueue', {
  queueName: `smartretail-ims-sales-${env}`,
  deadLetterQueue: { queue: imsSalesDlq, maxReceiveCount: 3 },
  visibilityTimeout: cdk.Duration.seconds(120),
});
 
// RE alert FIFO queue
const reAlertDlq = new sqs.Queue(this, 'ReAlertDlq', {
  queueName: `smartretail-re-alert-${env}-dlq.fifo`,
  fifo: true,
});
const reAlertQueue = new sqs.Queue(this, 'ReAlertQueue', {
  queueName: `smartretail-re-alert-${env}.fifo`,
  fifo: true,
  contentBasedDeduplication: true,
  deadLetterQueue: { queue: reAlertDlq, maxReceiveCount: 3 },
  visibilityTimeout: cdk.Duration.seconds(120),
});
 
// ARS updates queue
const arsUpdatesDlq = new sqs.Queue(this, 'ArsUpdatesDlq', {
  queueName: `smartretail-ars-updates-${env}-dlq`,
});
const arsUpdatesQueue = new sqs.Queue(this, 'ArsUpdatesQueue', {
  queueName: `smartretail-ars-updates-${env}`,
  deadLetterQueue: { queue: arsUpdatesDlq, maxReceiveCount: 3 },
});
```
 
### EventBridge Rules
 
```typescript
// Sales transaction → IMS queue
new events.Rule(this, 'SalesTransactionToIms', {
  eventBus: bus,
  ruleName: `smartretail-sales-to-ims-${env}`,
  eventPattern: {
    source: ['smartretail.sis'],
    detailType: ['SalesTransactionEvent'],
  },
  targets: [new eventsTargets.SqsQueue(imsSalesQueue)],
});
 
// Inventory alert → RE FIFO queue
// MessageGroupId must be set from event detail
new events.Rule(this, 'InventoryAlertToRe', {
  eventBus: bus,
  ruleName: `smartretail-alert-to-re-${env}`,
  eventPattern: {
    source: ['smartretail.ims'],
    detailType: ['InventoryAlertEvent'],
  },
  targets: [new eventsTargets.SqsQueue(reAlertQueue, {
    messageGroupId: events.EventField.fromPath('$.detail.dcId'),
  })],
});
 
// All domain events → ARS queue
new events.Rule(this, 'AllEventsToArs', {
  eventBus: bus,
  ruleName: `smartretail-all-to-ars-${env}`,
  eventPattern: {
    source: ['smartretail.sis', 'smartretail.ims', 'smartretail.re'],
  },
  targets: [new eventsTargets.SqsQueue(arsUpdatesQueue)],
});
```
 
---
 
## Stack 4: IdentityStack

File: `environments/demo/infra/lib/identity-stack.ts`

**Deploy order: after HostingStack** — receives `mfeBaseUrl` (CloudFront URL) to register Cognito callback URLs.

Creates Cognito user pools with hosted UI domains and PKCE OAuth app clients.

```typescript
// Props — mfeBaseUrl comes from HostingStack.distributionUrl
// { srEnv: string; mfeBaseUrl: string }

// Hosted UI domain (free AWS subdomain, required for PKCE redirect)
new cognito.UserPoolDomain(this, 'InternalDomain', {
  userPool: internalPool,
  cognitoDomain: { domainPrefix: `smartretail-${env}-internal` },
});

// Internal App Client — demo: sc-planner only; dev/prod: all three internal MFEs
internalPool.addClient('InternalAppClient', {
  authFlows: { userSrp: true },
  oAuth: {
    flows: { authorizationCodeGrant: true },
    scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL, cognito.OAuthScope.PROFILE],
    callbackUrls: [
      `${mfeBaseUrl}/sc-planner/callback`,
      `${mfeBaseUrl}/store-manager/callback`,   // dev/prod only
      `${mfeBaseUrl}/executive/callback`,        // dev/prod only
    ],
    logoutUrls: [
      `${mfeBaseUrl}/sc-planner/logout`,
      `${mfeBaseUrl}/store-manager/logout`,      // dev/prod only
      `${mfeBaseUrl}/executive/logout`,           // dev/prod only
    ],
  },
  generateSecret: false,
});

// Supplier pool domain + client (dev/prod only)
new cognito.UserPoolDomain(this, 'SupplierDomain', {
  userPool: supplierPool,
  cognitoDomain: { domainPrefix: `smartretail-${env}-supplier` },
});
supplierPool.addClient('SupplierAppClient', {
  oAuth: {
    flows: { authorizationCodeGrant: true },
    callbackUrls: [`${mfeBaseUrl}/supplier/callback`],
    logoutUrls:   [`${mfeBaseUrl}/supplier/logout`],
  },
  generateSecret: false,
});
```

### Outputs
```
/smartretail/{env}/cognito/internal-pool-id
/smartretail/{env}/cognito/internal-client-id
/smartretail/{env}/cognito/internal-domain     (e.g. smartretail-dev-internal.auth.us-east-1.amazoncognito.com)
/smartretail/{env}/cognito/supplier-pool-id
/smartretail/{env}/cognito/supplier-client-id
/smartretail/{env}/cognito/supplier-domain
```
---
 
## Stack 5: ComputeStack
 
File: `environments/demo/infra/lib/compute-stack.ts`
 
Creates:
- ECS Cluster with Container Insights
- ECR repositories
- IAM task roles (per service)
- ECS Task Definitions (per service)
- ECS Services (per service)
> Kinesis Consumer Lambda removed — Firehose delivers directly to SIS via API Gateway
 
### ECS Cluster
 
```typescript
const cluster = new ecs.Cluster(this, 'SmartRetailCluster', {
  clusterName: `smartretail-${env}`,
  vpc,
  containerInsights: true,
  enableFargateCapacityProviders: true,
});
```
 
### IAM Task Roles
 
Create separate task roles for SIS, IMS, RE, ARS.
Each role only has permissions for its own schema user and its own queues.
See ARCHITECTURE.md Security section for exact permissions.
 
### ECS Task Definition Template
 
```typescript
const taskDef = new ecs.FargateTaskDefinition(this, `${service}TaskDef`, {
  family: `smartretail-${service}-${env}`,
  cpu: 512,
  memoryLimitMiB: 1024,
  taskRole: serviceTaskRole,
  executionRole: ecsExecutionRole,
});
 
taskDef.addContainer(`${service}Container`, {
  image: ecs.ContainerImage.fromEcrRepository(ecrRepo, 'latest'),
  portMappings: [{ containerPort: 8080 }],
  logging: ecs.LogDrivers.awsLogs({
    streamPrefix: 'ecs',
    logGroup: new logs.LogGroup(this, `${service}LogGroup`, {
      logGroupName: `/smartretail/${service}/${env}`,
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    }),
  }),
  healthCheck: {
    command: ['CMD-SHELL', 'curl -f http://localhost:8080/actuator/health || exit 1'],
    interval: cdk.Duration.seconds(30),
    timeout: cdk.Duration.seconds(5),
    retries: 3,
    startPeriod: cdk.Duration.seconds(60),
  },
  environment: {
    SERVICE_NAME: service,
    AWS_REGION: 'us-east-1',
    ENV: env,
  },
});
```
 
### ECS Services
 
```typescript
const service = new ecs.FargateService(this, `${serviceName}Service`, {
  cluster,
  taskDefinition: taskDef,
  serviceName: `smartretail-${serviceName}-${env}`,
  desiredCount: 1,
  assignPublicIp: false,
  vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
  securityGroups: [sgEcsTasks],
  healthCheckGracePeriod: cdk.Duration.seconds(60),
  circuitBreaker: { rollback: true },
});
```
 
### Kinesis Consumer Lambda — Removed

> Eliminated. Firehose now delivers directly to SIS via API Gateway HTTP endpoint + VPC Link.
> The `backend/adapters/kinesis-consumer/` directory and its ECR repository are removed.

### Batch Post-Processor Lambda
 
```typescript
const batchPostProcessorRepo = new ecr.Repository(this, 'BatchPostProcessorRepo', {
  repositoryName: `smartretail-batch-post-processor-${env}`,
  removalPolicy: cdk.RemovalPolicy.DESTROY,
  emptyOnDelete: true,
});
 
const batchPostProcessorRole = new iam.Role(this, 'BatchPostProcessorRole', {
  assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
  managedPolicies: [iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaVPCAccessExecutionRole')],
});
batchPostProcessorRole.addToPolicy(new iam.PolicyStatement({
  actions: ['s3:GetObject'],
  resources: [sagemakerBucket.bucketArn + '/sagemaker/output/*'],
}));
 
const batchPostProcessorFn = new lambda.DockerImageFunction(this, 'BatchPostProcessor', {
  functionName: `smartretail-batch-post-processor-${env}`,
  code: lambda.DockerImageCode.fromEcr(batchPostProcessorRepo),
  architecture: lambda.Architecture.X86_64,
  vpc,
  vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
  securityGroups: [sgEcsTasks],
  timeout: cdk.Duration.seconds(180),
  memorySize: 512,
  role: batchPostProcessorRole,
  environment: {
    DFS_ENDPOINT: `http://smartretail-dfs-${env}.smartretail.local:8084`,
    ENV: env,
  },
});
 
// S3 event source — fires on SageMaker output CSVs
batchPostProcessorFn.addEventSource(new lambdaEventSources.S3EventSource(sagemakerBucket, {
  events: [s3.EventType.OBJECT_CREATED],
  filters: [{ prefix: 'sagemaker/output/', suffix: '.csv' }],
}));
```
 
---
 
## Stack 6: ApiStack
 
File: `environments/demo/infra/lib/api-stack.ts`
 
Creates:
- API Gateway REST API
- VPC Link
- JWT Authoriser (Cognito)
- Resource + method definitions
 
### VPC Link
 
```typescript
const vpcLink = new apigateway.VpcLink(this, 'SmartRetailVpcLink', {
  vpcLinkName: `smartretail-vpclink-${env}`,
  targets: [nlb],  // Network Load Balancer fronting ECS services
});
```
 
### REST API
 
```typescript
const api = new apigateway.RestApi(this, 'SmartRetailApi', {
  restApiName: `smartretail-api-${env}`,
  deployOptions: {
    stageName: 'internal',
    throttlingRateLimit: 1000,
    throttlingBurstLimit: 2000,
    tracingEnabled: true,
  },
  defaultCorsPreflightOptions: {
    allowOrigins: apigateway.Cors.ALL_ORIGINS,
    allowMethods: apigateway.Cors.ALL_METHODS,
    allowHeaders: ['Authorization', 'Content-Type', 'X-Idempotency-Key'],
  },
});
 
// JWT Authoriser
const authoriser = new apigateway.CognitoUserPoolsAuthorizer(this, 'InternalAuthoriser', {
  cognitoUserPools: [internalPool],
  authorizerName: `smartretail-internal-auth-${env}`,
  identitySource: 'method.request.header.Authorization',
});
```
 
### Outputs
```
/smartretail/{env}/api-gateway/endpoint
/smartretail/{env}/api-gateway/id
```

---

## Stack 7: HostingStack

File: `environments/demo/infra/lib/hosting-stack.ts`

**Deploy order: after DataStack, before IdentityStack** — IdentityStack needs the CloudFront URL for Cognito callback URLs.

Creates:
- **Single CloudFront distribution** fronting all deployed MFE S3 buckets via path-based routing
- **OAC** (Origin Access Control) per MFE bucket — buckets are private, CloudFront authenticates with SigV4
- **CloudFront Function** per MFE behavior — strips `/{mfe}` prefix, rewrites extensionless paths to `/index.html` (SPA routing)
- **Default behavior** — CloudFront Function returns 302 redirect to `/sc-planner/`
- **Price class**: PRICE_CLASS_100 (US/Europe, lowest cost)

**Demo** serves `sc-planner` only. **Dev/prod** serve all four MFEs:

| Path | MFE | Cognito pool |
|------|-----|-------------|
| `/sc-planner/*` | SC Planner Console | Internal |
| `/store-manager/*` | Store Manager Dashboard | Internal |
| `/executive/*` | Executive Dashboard | Internal |
| `/supplier/*` | Supplier Portal | Supplier |

### Outputs
```
/smartretail/{env}/hosting/cloudfront-url
/smartretail/{env}/hosting/cloudfront-distribution-id
/smartretail/{env}/hosting/{mfe}-url          (e.g. https://xyz.cloudfront.net/sc-planner)
/smartretail/{env}/hosting/{mfe}-bucket-name
```

---

## Stack 8: MonitoringStack

File: `environments/demo/infra/lib/monitoring-stack.ts`

Creates:
- SNS topic for operational alerts (`smartretail-alerts-{env}`)
- CloudWatch alarms on ECS service CPU/memory, SQS DLQ depth, and RDS CPU
- Log metric filters for ERROR-level log lines from each service
- Optional email subscription via `alertEmail` stack prop

Deploy last — depends on ComputeStack, MessagingStack, DataStack, and ApiStack.

### Outputs
```
/smartretail/{env}/monitoring/alerts-topic-arn
```
