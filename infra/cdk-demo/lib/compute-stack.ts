import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';
import { DataStack } from './data-stack';
import { MessagingStack } from './messaging-stack';
import { IdentityStack } from './identity-stack';

export interface ComputeStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
  data: DataStack;
  messaging: MessagingStack;
  identity: IdentityStack;
}

interface ServiceConfig {
  name: string;
  port: number;
  ecrRepo: ecr.IRepository;
  envVars: Record<string, string>;
  secrets?: Record<string, ecs.Secret>;
  policies: iam.PolicyStatement[];
}

/**
 * Demo compute stack — SC Planner backend only (IMS, RE, ARS, DFS, SUP).
 * SIS is intentionally absent; all sales data is pre-seeded.
 * Container Insights enabled for CloudWatch observability.
 */
export class ComputeStack extends cdk.Stack {
  public readonly cluster: ecs.Cluster;
  public readonly imsService: ecs.FargateService;
  public readonly reService: ecs.FargateService;
  public readonly arsService: ecs.FargateService;
  public readonly dfsService: ecs.FargateService;
  public readonly supService: ecs.FargateService;

  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-compute-demo');

    const { srEnv, network, data, messaging, identity } = props;

    this.cluster = new ecs.Cluster(this, 'Cluster', {
      clusterName: `smartretail-${srEnv}`,
      vpc: network.vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
      enableFargateCapacityProviders: true,
    });

    this.cluster.addDefaultCloudMapNamespace({ name: 'smartretail.local' });

    const ecsExecutionRole = new iam.Role(this, 'EcsExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });

    // Grant execution role access to the RDS secret so ECS can inject DB_PASSWORD
    data.rdsInstance.secret!.grantRead(ecsExecutionRole);

    // Cognito issuer URI — built from the pool ID via Fn.join so CloudFormation
    // resolves it correctly (SSM dynamic refs cannot be embedded in strings).
    const cognitoIssuerUri = cdk.Fn.join('', [
      'https://cognito-idp.',
      this.region,
      '.amazonaws.com/',
      identity.internalPool.userPoolId,
    ]);

    const commonEnv = {
      SMARTRETAIL_ENV:   srEnv,
      AWS_REGION:        cdk.Stack.of(this).region,
      RDS_PROXY_ENDPOINT: data.dbEndpoint,
      COGNITO_ISSUER_URI: cognitoIssuerUri,
    };

    // DB_PASSWORD injected via Secrets Manager at task launch — not a plain env var
    const commonSecrets: Record<string, ecs.Secret> = {
      DB_PASSWORD: ecs.Secret.fromSecretsManager(data.rdsInstance.secret!, 'password'),
    };

    const imsConfig: ServiceConfig = {
      name: 'ims', port: 8081,
      ecrRepo: data.ecrRepos['ims'],
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'inventory',
        DB_USERNAME: 'smartretail_admin',
        IMS_SALES_QUEUE_URL: messaging.imsSalesQueue.queueUrl,
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
      secrets: commonSecrets,
      policies: [
        new iam.PolicyStatement({
          actions: ['sqs:ReceiveMessage', 'sqs:DeleteMessage', 'sqs:GetQueueAttributes'],
          resources: [messaging.imsSalesQueue.queueArn],
        }),
        new iam.PolicyStatement({
          actions: ['events:PutEvents'],
          resources: [messaging.eventBus.eventBusArn],
        }),
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    const reConfig: ServiceConfig = {
      name: 're', port: 8082,
      ecrRepo: data.ecrRepos['re'],
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'replenishment',
        DB_USERNAME: 'smartretail_admin',
        RE_ALERT_QUEUE_URL: messaging.reAlertQueue.queueUrl,
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
      secrets: commonSecrets,
      policies: [
        new iam.PolicyStatement({
          actions: ['sqs:ReceiveMessage', 'sqs:DeleteMessage', 'sqs:GetQueueAttributes', 'sqs:ChangeMessageVisibility'],
          resources: [messaging.reAlertQueue.queueArn],
        }),
        new iam.PolicyStatement({
          actions: ['events:PutEvents'],
          resources: [messaging.eventBus.eventBusArn],
        }),
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    const arsConfig: ServiceConfig = {
      name: 'ars', port: 8083,
      ecrRepo: data.ecrRepos['ars'],
      envVars: {
        ...commonEnv,
        DB_USERNAME: 'smartretail_admin',
      },
      secrets: commonSecrets,
      policies: [
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    const dfsConfig: ServiceConfig = {
      name: 'dfs', port: 8084,
      ecrRepo: data.ecrRepos['dfs'],
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'forecasting',
        DB_USERNAME: 'smartretail_admin',
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
      secrets: commonSecrets,
      policies: [
        new iam.PolicyStatement({
          actions: ['events:PutEvents'],
          resources: [messaging.eventBus.eventBusArn],
        }),
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    const supConfig: ServiceConfig = {
      name: 'sup', port: 8085,
      ecrRepo: data.ecrRepos['sup'],
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'supplier',
        DB_USERNAME: 'smartretail_admin',
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
      secrets: commonSecrets,
      policies: [
        new iam.PolicyStatement({
          actions: ['events:PutEvents'],
          resources: [messaging.eventBus.eventBusArn],
        }),
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    this.imsService = this.createFargateService(imsConfig, network, ecsExecutionRole, srEnv);
    this.reService  = this.createFargateService(reConfig,  network, ecsExecutionRole, srEnv);
    this.arsService = this.createFargateService(arsConfig, network, ecsExecutionRole, srEnv);
    this.dfsService = this.createFargateService(dfsConfig, network, ecsExecutionRole, srEnv);
    this.supService = this.createFargateService(supConfig, network, ecsExecutionRole, srEnv);

    new ssm.StringParameter(this, 'ClusterNameParam', {
      parameterName: `/smartretail/${srEnv}/ecs/cluster-name`,
      stringValue: this.cluster.clusterName,
    });
  }

  private createFargateService(
    config: ServiceConfig,
    network: NetworkStack,
    executionRole: iam.Role,
    srEnv: string,
  ): ecs.FargateService {
    const ecrRepo = config.ecrRepo;

    const taskRole = new iam.Role(this, `${config.name}TaskRole`, {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    config.policies.forEach(p => taskRole.addToPolicy(p));

    const taskDef = new ecs.FargateTaskDefinition(this, `${config.name}TaskDef`, {
      family: `smartretail-${config.name}-${srEnv}`,
      cpu: 256,
      memoryLimitMiB: 512,
      taskRole,
      executionRole,
      runtimePlatform: {
        cpuArchitecture: ecs.CpuArchitecture.ARM64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
    });

    const logGroup = new logs.LogGroup(this, `${config.name}LogGroup`, {
      logGroupName: `/smartretail/${config.name}/${srEnv}`,
      retention: logs.RetentionDays.TWO_WEEKS,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    taskDef.addContainer(`${config.name}Container`, {
      image: ecs.ContainerImage.fromEcrRepository(ecrRepo, 'latest'),
      portMappings: [{ containerPort: config.port }],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'ecs', logGroup }),
      healthCheck: {
        // wget is available in eclipse-temurin:21-jre-alpine; curl is not
        command: ['CMD-SHELL', `wget -q --spider http://localhost:${config.port}/actuator/health || exit 1`],
        interval: cdk.Duration.seconds(30),
        timeout: cdk.Duration.seconds(5),
        retries: 3,
        startPeriod: cdk.Duration.seconds(60),
      },
      environment: { ...config.envVars, SPRING_PROFILES_ACTIVE: 'demo' },
      secrets: config.secrets,
    });

    const service = new ecs.FargateService(this, `${config.name}Service`, {
      cluster: this.cluster,
      taskDefinition: taskDef,
      serviceName: `smartretail-${config.name}-${srEnv}`,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroups: [network.sgEcsTasks],
      healthCheckGracePeriod: cdk.Duration.seconds(60),
      circuitBreaker: { rollback: true },
      cloudMapOptions: { name: `smartretail-${config.name}-${srEnv}` },
      capacityProviderStrategies: [
        { capacityProvider: 'FARGATE_SPOT', weight: 4 },
        { capacityProvider: 'FARGATE',      weight: 1 },
      ],
    });

    const scaling = service.autoScaleTaskCount({ minCapacity: 1, maxCapacity: 2 });
    scaling.scaleOnCpuUtilization(`${config.name}CpuScaling`, { targetUtilizationPercent: 70 });

    return service;
  }
}
