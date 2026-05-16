import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as lambdaEventSources from 'aws-cdk-lib/aws-lambda-event-sources';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';
import { DataStack } from './data-stack';
import { MessagingStack } from './messaging-stack';

export interface ComputeStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
  data: DataStack;
  messaging: MessagingStack;
}

interface ServiceConfig {
  name: string;
  port: number;
  envVars: Record<string, string>;
  policies: iam.PolicyStatement[];
}

export class ComputeStack extends cdk.Stack {
  public readonly cluster: ecs.Cluster;
  public readonly sisService: ecs.FargateService;
  public readonly imsService: ecs.FargateService;

  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-compute');

    const { srEnv, network, data, messaging } = props;

    // ── ECS Cluster ───────────────────────────────────────────────────────────
    this.cluster = new ecs.Cluster(this, 'SmartRetailCluster', {
      clusterName: `smartretail-${srEnv}`,
      vpc: network.vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
      enableFargateCapacityProviders: true,
    });

    this.cluster.addDefaultCloudMapNamespace({
      name: 'smartretail.local',
    });

    const ecsExecutionRole = new iam.Role(this, 'EcsExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });

    // ── Service definitions ───────────────────────────────────────────────────
    const commonEnv = {
      SMARTRETAIL_ENV: srEnv,
      AWS_REGION: cdk.Stack.of(this).region,
      RDS_PROXY_ENDPOINT: data.rdsProxy.endpoint,
    };

    const sisConfig: ServiceConfig = {
      name: 'sis', port: 8080,
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'sales',
        DB_USERNAME: 'smartretail_admin',
        IDEMPOTENCY_TABLE_NAME: data.idempotencyTable.tableName,
        EVENTS_BUCKET_NAME: data.eventsBucketName,
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
      policies: [
        new iam.PolicyStatement({
          actions: ['dynamodb:GetItem', 'dynamodb:PutItem'],
          resources: [data.idempotencyTable.tableArn],
        }),
        new iam.PolicyStatement({
          actions: ['s3:PutObject'],
          resources: [`arn:aws:s3:::${data.eventsBucketName}/*`],
        }),
        new iam.PolicyStatement({
          actions: ['events:PutEvents'],
          resources: [messaging.eventBus.eventBusArn],
        }),
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${cdk.Stack.of(this).region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    const imsConfig: ServiceConfig = {
      name: 'ims', port: 8081,
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'inventory',
        DB_USERNAME: 'smartretail_admin',
        IMS_SALES_QUEUE_URL: messaging.imsSalesQueue.queueUrl,
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
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
          resources: [`arn:aws:rds-db:${cdk.Stack.of(this).region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    this.sisService = this.createFargateService(sisConfig, network, ecsExecutionRole, srEnv);
    this.imsService = this.createFargateService(imsConfig, network, ecsExecutionRole, srEnv);

    // ── Kinesis Consumer Lambda ───────────────────────────────────────────────
    const kinesisConsumerRepo = new ecr.Repository(this, 'KinesisConsumerRepo', {
      repositoryName: `smartretail-kinesis-consumer-${srEnv}`,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    const lambdaRole = new iam.Role(this, 'KinesisConsumerRole', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaVPCAccessExecutionRole'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
      ],
    });
    lambdaRole.addToPolicy(new iam.PolicyStatement({
      actions: ['kinesis:GetRecords', 'kinesis:GetShardIterator',
        'kinesis:DescribeStream', 'kinesis:ListShards'],
      resources: [messaging.kinesisStream.streamArn],
    }));
    lambdaRole.addToPolicy(new iam.PolicyStatement({
      actions: ['dynamodb:GetItem', 'dynamodb:PutItem'],
      resources: [data.idempotencyTable.tableArn],
    }));

    const kinesisConsumerFn = new lambda.DockerImageFunction(this, 'KinesisConsumer', {
      functionName: `smartretail-kinesis-consumer-${srEnv}`,
      code: lambda.DockerImageCode.fromEcr(kinesisConsumerRepo),
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [network.sgLambda],
      timeout: cdk.Duration.seconds(300),
      memorySize: 512,
      role: lambdaRole,
      environment: {
        SIS_ENDPOINT: `http://smartretail-sis-${srEnv}.smartretail.local:8080`,
        IDEMPOTENCY_TABLE_NAME: data.idempotencyTable.tableName,
        ENV: srEnv,
      },
    });

    kinesisConsumerFn.addEventSource(new lambdaEventSources.KinesisEventSource(
      messaging.kinesisStream, {
      startingPosition: lambda.StartingPosition.LATEST,
      batchSize: 100,
      bisectBatchOnError: true,
      retryAttempts: 3,
    }
    ));

    // SSM outputs
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
    const ecrRepo = new ecr.Repository(this, `${config.name}Repo`, {
      repositoryName: `smartretail-${config.name}-${srEnv}`,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
      lifecycleRules: [
        {
          maxImageCount: 10,
        },
      ],
    });

    const taskRole = new iam.Role(this, `${config.name}TaskRole`, {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    config.policies.forEach(p => taskRole.addToPolicy(p));

    const taskDef = new ecs.FargateTaskDefinition(this, `${config.name}TaskDef`, {
      family: `smartretail-${config.name}-${srEnv}`,
      cpu: 512,
      memoryLimitMiB: 1024,
      taskRole,
      executionRole,
    });

    const logGroup = new logs.LogGroup(this, `${config.name}LogGroup`, {
      logGroupName: `/smartretail/${config.name}/${srEnv}`,
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    taskDef.addContainer(`${config.name}Container`, {
      image: ecs.ContainerImage.fromEcrRepository(ecrRepo, 'latest'),
      portMappings: [{ containerPort: config.port }],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'ecs', logGroup }),
      healthCheck: {
        command: [`CMD-SHELL`, `curl -f http://localhost:${config.port}/actuator/health || exit 1`],
        interval: cdk.Duration.seconds(30),
        timeout: cdk.Duration.seconds(5),
        retries: 3,
        startPeriod: cdk.Duration.seconds(60),
      },
      environment: {
        ...config.envVars,
        SPRING_PROFILES_ACTIVE: 'aws',
      },
    });

    const service = new ecs.FargateService(this, `${config.name}Service`, {
      cluster: this.cluster,
      taskDefinition: taskDef,
      serviceName: `smartretail-${config.name}-${srEnv}`,
      desiredCount: 1,
      assignPublicIp: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [network.sgEcsTasks],
      healthCheckGracePeriod: cdk.Duration.seconds(60),
      circuitBreaker: { rollback: true },
      cloudMapOptions: {
        name: `smartretail-${config.name}-${srEnv}`,
      },
    });

    const scaling = service.autoScaleTaskCount({
      minCapacity: 1,
      maxCapacity: 10,
    });

    scaling.scaleOnCpuUtilization(`${config.name}CpuScaling`, {
      targetUtilizationPercent: 70,
    });

    return service;
  }
}
