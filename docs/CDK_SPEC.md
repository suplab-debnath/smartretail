# CDK Infrastructure Specification
 
All CDK stacks are in TypeScript.

- **Demo / dev stack** (`infra/cdk-min/`) — SQS-only, reuses default VPC. **This is the stack to run.** Stack names: `Min-*`.
- **Production stack** (`infra/cdk-prod/`) — Kinesis, dedicated VPC. Not wired into Makefile. Stack names: `Prod-*`.

The specifications below describe `infra/cdk-min/` (the demo stack).
Deploy in order: Network → Data → Messaging → Identity → Compute → API.
 
---

## Stack Variants

| Stack | Path | Purpose | CPU arch | Stack prefix |
|-------|------|---------|----------|-------------|
| **cdk-min** | `infra/cdk-min/` | Demo only — SQS, default VPC, ARM64, cheap | ARM64 | `Min-*` |
| **cdk-dev** | `infra/cdk-dev/` | Full dev — Kinesis, 2-AZ VPC, RDS Proxy, CloudFront, X86_64 | X86_64 | `Dev-*` |
| **cdk-prod** | `infra/cdk-prod/` | Production — Kinesis, 3-AZ VPC, Multi-AZ RDS, RDS Proxy, CloudFront | X86_64 | `Prod-*` |

**cdk-min** is the only stack wired into the Makefile (`dev-*` targets). Use it for local/demo deploys.
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
(internal + supplier), Kinesis + Lambda consumer, and RDS Proxy.

---

 
## Stack 1: NetworkStack
 
File: `infra/cdk-min/lib/network-stack.ts`
 
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
vpc.addGatewayEndpoint('DynamoDBEndpoint', { service: ec2.GatewayVpcEndpointAwsService.DYNAMODB });
 
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
 
File: `infra/cdk-min/lib/data-stack.ts`
 
Creates:
- RDS PostgreSQL Multi-AZ
- RDS Proxy
- DynamoDB idempotency table
- S3 buckets
 
### RDS PostgreSQL
 
```typescript
const rdsInstance = new rds.DatabaseInstance(this, 'SmartRetailRds', {
  engine: rds.DatabaseInstanceEngine.postgres({
    version: rds.PostgresEngineVersion.VER_15_4,
  }),
  instanceType: ec2.InstanceType.of(
    ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM
  ),
  vpc,
  vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
  securityGroups: [sgRds],
  multiAz: true,
  databaseName: 'smartretail',
  credentials: rds.Credentials.fromGeneratedSecret('smartretail_admin'),
  backupRetention: cdk.Duration.days(7),
  deletionProtection: false,  // prototype only
  removalPolicy: cdk.RemovalPolicy.DESTROY,  // prototype only
  enablePerformanceInsights: true,
  storageEncrypted: true,
});
```
 
### RDS Proxy
 
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
 
### DynamoDB
 
```typescript
const idempotencyTable = new dynamodb.Table(this, 'IdempotencyKeys', {
  tableName: `smartretail-idempotency-keys-${env}`,
  partitionKey: { name: 'event_id', type: dynamodb.AttributeType.STRING },
  billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
  timeToLiveAttribute: 'expires_at',
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
 
// MFE static assets (4 buckets)
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
/smartretail/{env}/dynamodb/idempotency-table-name
/smartretail/{env}/s3/events-bucket-name
/smartretail/{env}/s3/mfe-store-manager-bucket
/smartretail/{env}/s3/mfe-sc-planner-bucket
/smartretail/{env}/s3/mfe-executive-bucket
```
 
---
 
## Stack 3: MessagingStack
 
File: `infra/cdk-min/lib/messaging-stack.ts`
 
Creates:
- Kinesis Data Stream
- EventBridge custom bus
- SQS queues with DLQs
- EventBridge rules routing to SQS
 
### Kinesis
 
```typescript
const stream = new kinesis.Stream(this, 'SmartRetailStream', {
  streamName: `smartretail-events-${env}`,
  streamMode: kinesis.StreamMode.ON_DEMAND,
  retentionPeriod: cdk.Duration.days(7),
  encryption: kinesis.StreamEncryption.KMS,
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
  contentBasedDeduplication: false,
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
 
File: `infra/cdk-min/lib/identity-stack.ts`
 
Creates Cognito user pools.
 
```typescript
// Internal User Pool
const internalPool = new cognito.UserPool(this, 'InternalPool', {
  userPoolName: `smartretail-internal-${env}`,
  selfSignUpEnabled: false,
  signInAliases: { email: true },
  autoVerify: { email: true },
  passwordPolicy: {
    minLength: 8,
    requireLowercase: true,
    requireUppercase: true,
    requireDigits: true,
    requireSymbols: true,
  },
  accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
  removalPolicy: cdk.RemovalPolicy.DESTROY,
});
 
// Internal groups
['STORE_MANAGER', 'SC_PLANNER', 'EXECUTIVE', 'ADMIN'].forEach(group => {
  new cognito.CfnUserPoolGroup(this, `Group${group}`, {
    userPoolId: internalPool.userPoolId,
    groupName: group,
  });
});
 
// Internal App Client
const internalClient = internalPool.addClient('InternalAppClient', {
  userPoolClientName: `smartretail-internal-client-${env}`,
  authFlows: { userSrp: true },
  oAuth: {
    flows: { authorizationCodeGrant: true },
    scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL, cognito.OAuthScope.PROFILE],
    callbackUrls: [`https://${mfeInternalDomain}/callback`],
    logoutUrls: [`https://${mfeInternalDomain}/logout`],
  },
  accessTokenValidity: cdk.Duration.hours(1),
  refreshTokenValidity: cdk.Duration.hours(8),
  preventUserExistenceErrors: true,
});
 
// Supplier User Pool
const supplierPool = new cognito.UserPool(this, 'SupplierPool', {
  userPoolName: `smartretail-supplier-${env}`,
  selfSignUpEnabled: false,
  signInAliases: { email: true },
  mfa: cognito.Mfa.REQUIRED,
  mfaSecondFactor: { totp: true, otp: false },
  customAttributes: {
    supplierId: new cognito.StringAttribute({ mutable: false }),
  },
  removalPolicy: cdk.RemovalPolicy.DESTROY,
});
```
 
### Outputs
```
/smartretail/{env}/cognito/internal-pool-id
/smartretail/{env}/cognito/internal-client-id
/smartretail/{env}/cognito/supplier-pool-id
/smartretail/{env}/cognito/supplier-client-id
```
 
---
 
## Stack 5: ComputeStack
 
File: `infra/cdk-min/lib/compute-stack.ts`
 
Creates:
- ECS Cluster with Container Insights
- ECR repositories
- IAM task roles (per service)
- ECS Task Definitions (per service)
- ECS Services (per service)
- Kinesis Consumer Lambda
 
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
 
### Kinesis Consumer Lambda
 
```typescript
const kinesisConsumerFn = new lambda.Function(this, 'KinesisConsumer', {
  functionName: `smartretail-kinesis-consumer-${env}`,
  runtime: lambda.Runtime.JAVA_21,
  handler: 'com.smartretail.lambda.kinesis.KinesisConsumerHandler::handleRequest',
  code: lambda.Code.fromEcr(kinesisConsumerEcrRepo),
  vpc,
  vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
  securityGroups: [sgEcsTasks],
  timeout: cdk.Duration.seconds(300),
  memorySize: 512,
  environment: {
    SIS_ENDPOINT: `http://${sisServiceDnsName}:8080`,
    ENV: env,
  },
});
 
// Kinesis event source
kinesisConsumerFn.addEventSource(new lambdaEventSources.KinesisEventSource(kinesisStream, {
  startingPosition: lambda.StartingPosition.LATEST,
  batchSize: 100,
  bisectBatchOnError: true,
  retryAttempts: 3,
}));
```
 
---
 
## Stack 6: ApiStack
 
File: `infra/cdk-min/lib/api-stack.ts`
 
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

 
 