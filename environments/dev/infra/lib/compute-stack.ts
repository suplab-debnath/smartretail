import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sagemaker from 'aws-cdk-lib/aws-sagemaker';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as events from 'aws-cdk-lib/aws-events';
import * as eventsTargets from 'aws-cdk-lib/aws-events-targets';
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
  secrets?: Record<string, ecs.Secret>;
  policies: iam.PolicyStatement[];
}

export class ComputeStack extends cdk.Stack {
  public readonly cluster: ecs.Cluster;
  public readonly sisService: ecs.FargateService;
  public readonly imsService: ecs.FargateService;
  public readonly reService: ecs.FargateService;
  public readonly arsService: ecs.FargateService;
  public readonly dfsService: ecs.FargateService;
  public readonly supService: ecs.FargateService;
  public readonly ppsService: ecs.FargateService;
  public readonly batchPostProcessorFn: lambda.DockerImageFunction;

  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-compute-dev');

    const { srEnv, network, data, messaging } = props;

    this.cluster = new ecs.Cluster(this, 'Cluster', {
      clusterName: `smartretail-${srEnv}`,
      vpc: network.vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
      enableFargateCapacityProviders: true,
    });

    // CloudMap — used by Batch Post-Processor Lambda for DFS service discovery
    this.cluster.addDefaultCloudMapNamespace({ name: 'smartretail.local' });

    const ecsExecutionRole = new iam.Role(this, 'EcsExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });

    // Allow ECS task execution role to read Firehose access key from Secrets Manager
    data.firehoseAccessKeySecret.grantRead(ecsExecutionRole);

    const commonEnv = {
      SMARTRETAIL_ENV: srEnv,
      AWS_REGION: cdk.Stack.of(this).region,
      RDS_PROXY_ENDPOINT: data.dbEndpoint,
    };

    const sisConfig: ServiceConfig = {
      name: 'sis', port: 8080,
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'sales',
        DB_USERNAME: 'smartretail_admin',
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
      secrets: {
        SMARTRETAIL_FIREHOSE_ACCESSKEY: ecs.Secret.fromSecretsManager(data.firehoseAccessKeySecret),
      },
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
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    const reConfig: ServiceConfig = {
      name: 're', port: 8082,
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'replenishment',
        DB_USERNAME: 'smartretail_admin',
        RE_ALERT_QUEUE_URL: messaging.reAlertQueue.queueUrl,
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
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
      envVars: { ...commonEnv, DB_USERNAME: 'smartretail_admin' },
      policies: [
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    const dfsConfig: ServiceConfig = {
      name: 'dfs', port: 8084,
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'forecasting',
        DB_USERNAME: 'smartretail_admin',
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
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
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'supplier',
        DB_USERNAME: 'smartretail_admin',
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
      },
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

    const ppsConfig: ServiceConfig = {
      name: 'pps', port: 8086,
      envVars: {
        ...commonEnv,
        DB_SCHEMA: 'promotions',
        DB_USERNAME: 'smartretail_admin',
        EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
        PPS_INBOUND_QUEUE_URL: messaging.ppsInboundQueue.queueUrl,
      },
      policies: [
        new iam.PolicyStatement({
          actions: ['events:PutEvents'],
          resources: [messaging.eventBus.eventBusArn],
        }),
        new iam.PolicyStatement({
          actions: ['sqs:ReceiveMessage', 'sqs:DeleteMessage', 'sqs:GetQueueAttributes'],
          resources: [messaging.ppsInboundQueue.queueArn],
        }),
        new iam.PolicyStatement({
          actions: ['rds-db:connect'],
          resources: [`arn:aws:rds-db:${this.region}:${this.account}:dbuser:*/smartretail_admin`],
        }),
      ],
    };

    this.sisService = this.createFargateService(sisConfig, network, ecsExecutionRole, srEnv);
    this.imsService = this.createFargateService(imsConfig, network, ecsExecutionRole, srEnv);
    this.reService  = this.createFargateService(reConfig,  network, ecsExecutionRole, srEnv);
    this.arsService = this.createFargateService(arsConfig, network, ecsExecutionRole, srEnv);
    this.dfsService = this.createFargateService(dfsConfig, network, ecsExecutionRole, srEnv);
    this.supService = this.createFargateService(supConfig, network, ecsExecutionRole, srEnv);
    this.ppsService = this.createFargateService(ppsConfig, network, ecsExecutionRole, srEnv);

    // Batch Post-Processor Lambda — reads SageMaker transform output CSV, POSTs to DFS
    const batchPostProcessorRepo = new ecr.Repository(this, 'BatchPostProcessorRepo', {
      repositoryName: `smartretail-batch-post-processor-${srEnv}`,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    const batchPostProcessorRole = new iam.Role(this, 'BatchPostProcessorRole', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaVPCAccessExecutionRole'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
      ],
    });
    batchPostProcessorRole.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:GetObject'],
      resources: [data.sagemakerBucket.arnForObjects('sagemaker/output/*')],
    }));

    // Dedicated SG for the Batch Post-Processor Lambda
    const sgBatchProcessor = new ec2.SecurityGroup(this, 'SgBatchProcessor', {
      vpc: network.vpc,
      description: 'Batch Post-Processor Lambda',
      allowAllOutbound: true,
    });

    this.batchPostProcessorFn = new lambda.DockerImageFunction(this, 'BatchPostProcessor', {
      functionName: `smartretail-batch-post-processor-${srEnv}`,
      code: lambda.DockerImageCode.fromEcr(batchPostProcessorRepo),
      architecture: lambda.Architecture.X86_64,
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [sgBatchProcessor],
      timeout: cdk.Duration.seconds(180),
      memorySize: 512,
      role: batchPostProcessorRole,
      environment: {
        DFS_ENDPOINT: `http://smartretail-dfs-${srEnv}.smartretail.local:8084`,
        ENV: srEnv,
      },
    });

    // S3EventSource adds a BucketNotification to DataStack referencing the Lambda ARN here in
    // ComputeStack — creating DataStack → ComputeStack dependency that cycles with
    // ComputeStack → DataStack. Use EventBridge (sagemakerBucket has eventBridgeEnabled:true)
    // so the rule lives in ComputeStack with no cross-stack reference from DataStack.
    const sagemakerOutputRule = new events.Rule(this, 'SageMakerOutputCreated', {
      ruleName: `smartretail-sagemaker-output-${srEnv}`,
      eventPattern: {
        source: ['aws.s3'],
        detailType: ['Object Created'],
        detail: {
          bucket: { name: [data.sagemakerBucket.bucketName] },
          object: { key: [{ prefix: 'sagemaker/output/' }, { suffix: '.csv' }] },
        },
      },
      description: 'Fires when SageMaker transform output CSV lands in S3; triggers batch-post-processor',
    });
    sagemakerOutputRule.addTarget(new eventsTargets.LambdaFunction(this.batchPostProcessorFn));

    // ML Trigger Lambda — EventBridge scheduled rule → SageMaker StartPipelineExecution
    const mlTriggerRepo = new ecr.Repository(this, 'MlTriggerRepo', {
      repositoryName: `smartretail-ml-trigger-${srEnv}`,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    const mlTriggerRole = new iam.Role(this, 'MlTriggerRole', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaVPCAccessExecutionRole'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
      ],
    });

    const sagemakerPipelineName = `smartretail-demand-forecast-${srEnv}`;
    mlTriggerRole.addToPolicy(new iam.PolicyStatement({
      actions: ['sagemaker:StartPipelineExecution'],
      resources: [`arn:aws:sagemaker:${this.region}:${this.account}:pipeline/${sagemakerPipelineName}`],
    }));
    mlTriggerRole.addToPolicy(new iam.PolicyStatement({
      actions: ['sagemaker:DescribePipelineExecution'],
      resources: [`arn:aws:sagemaker:${this.region}:${this.account}:pipeline/${sagemakerPipelineName}/*`],
    }));
    // Read raw POS events (Firehose AllData) → write DeepAR training files
    data.eventsBucket.grantRead(mlTriggerRole);
    data.sagemakerBucket.grantWrite(mlTriggerRole);

    const sgMlTrigger = new ec2.SecurityGroup(this, 'SgMlTrigger', {
      vpc: network.vpc,
      description: 'ML Trigger Lambda',
      allowAllOutbound: true,
    });

    const mlTriggerFn = new lambda.DockerImageFunction(this, 'MlTrigger', {
      functionName: `smartretail-ml-trigger-${srEnv}`,
      code: lambda.DockerImageCode.fromEcr(mlTriggerRepo),
      architecture: lambda.Architecture.X86_64,
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [sgMlTrigger],
      timeout: cdk.Duration.seconds(300),
      memorySize: 512,
      role: mlTriggerRole,
      environment: {
        DFS_ENDPOINT:           `http://smartretail-dfs-${srEnv}.smartretail.local:8084`,
        SAGEMAKER_PIPELINE_NAME: sagemakerPipelineName,
        EVENTS_BUCKET:          data.eventsBucket.bucketName,
        SAGEMAKER_BUCKET:       data.sagemakerBucket.bucketName,
        ENV:                    srEnv,
      },
    });

    // Daily at 02:00 UTC — trigger SageMaker demand forecast pipeline
    const mlTriggerRule = new events.Rule(this, 'MlTriggerSchedule', {
      ruleName: `smartretail-ml-trigger-daily-${srEnv}`,
      schedule: events.Schedule.cron({ hour: '2', minute: '0' }),
      description: 'Daily SageMaker demand forecast pipeline trigger',
    });
    mlTriggerRule.addTarget(new eventsTargets.LambdaFunction(mlTriggerFn));

    const demandForecastPipeline = new sagemaker.CfnPipeline(this, 'DemandForecastPipeline', {
      pipelineName: sagemakerPipelineName,
      roleArn: data.sagemakerExecutionRole.roleArn,
      pipelineDefinition: {} as sagemaker.CfnPipeline.PipelineDefinitionProperty,
      tags: [
        { key: 'Environment', value: srEnv },
        { key: 'Project', value: 'SmartRetail' },
      ],
    });
    demandForecastPipeline.addPropertyOverride('PipelineDefinition', {
      PipelineDefinitionBody: JSON.stringify(buildDemandForecastPipelineDefinition(data.sagemakerBucket.bucketName)),
    });

    new ssm.StringParameter(this, 'SageMakerPipelineNameParam', {
      parameterName: `/smartretail/${srEnv}/sagemaker/pipeline-name`,
      stringValue: sagemakerPipelineName,
    });

    new ssm.StringParameter(this, 'ClusterNameParam', {
      parameterName: `/smartretail/${srEnv}/ecs/cluster-name`,
      stringValue: this.cluster.clusterName,
    });

    // Flyway migration task — one-shot Fargate run-task, not a persistent service.
    // Grant execution role access to the RDS secret so Flyway can authenticate with
    // username/password via the RDS Proxy (services use IAM auth; Flyway uses the secret).
    data.rdsInstance.secret!.grantRead(ecsExecutionRole);

    const flywayRepo = new ecr.Repository(this, 'FlywayRepo', {
      repositoryName: `smartretail-flyway-${srEnv}`,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
      lifecycleRules: [{ maxImageCount: 5 }],
    });

    const flywayLogGroup = new logs.LogGroup(this, 'FlywayLogGroup', {
      logGroupName: `/smartretail/flyway/${srEnv}`,
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const flywayTaskDef = new ecs.FargateTaskDefinition(this, 'FlywayTaskDef', {
      family: `smartretail-flyway-${srEnv}`,
      cpu: 256,
      memoryLimitMiB: 512,
      executionRole: ecsExecutionRole,
      runtimePlatform: {
        cpuArchitecture: ecs.CpuArchitecture.X86_64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
    });

    flywayTaskDef.addContainer('flywayContainer', {
      image: ecs.ContainerImage.fromEcrRepository(flywayRepo, 'latest'),
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'flyway', logGroup: flywayLogGroup }),
      environment: {
        FLYWAY_URL:       `jdbc:postgresql://${data.dbEndpoint}:5432/smartretail`,
        FLYWAY_USER:      'smartretail_admin',
        FLYWAY_SCHEMAS:   'public,sales,forecasting,inventory,replenishment,supplier,promotions',
        FLYWAY_LOCATIONS: 'filesystem:/flyway/sql',
      },
      secrets: {
        FLYWAY_PASSWORD: ecs.Secret.fromSecretsManager(data.rdsInstance.secret!, 'password'),
      },
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
      lifecycleRules: [{ maxImageCount: 5 }],
    });

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
        cpuArchitecture: ecs.CpuArchitecture.X86_64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
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
        command: ['CMD-SHELL', `curl -f http://localhost:${config.port}/actuator/health || exit 1`],
        interval: cdk.Duration.seconds(30),
        timeout: cdk.Duration.seconds(5),
        retries: 3,
        startPeriod: cdk.Duration.seconds(60),
      },
      environment: { ...config.envVars, SPRING_PROFILES_ACTIVE: 'aws' },
      secrets: config.secrets,
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
      cloudMapOptions: { name: `smartretail-${config.name}-${srEnv}` },
      capacityProviderStrategies: [
        { capacityProvider: 'FARGATE_SPOT', weight: 4 },
        { capacityProvider: 'FARGATE',      weight: 1 },
      ],
    });

    const scaling = service.autoScaleTaskCount({ minCapacity: 1, maxCapacity: 3 });
    scaling.scaleOnCpuUtilization(`${config.name}CpuScaling`, { targetUtilizationPercent: 70 });

    return service;
  }
}

function buildDemandForecastPipelineDefinition(sagemakerBucket: string) {
  return {
    Version: '2020-12-01',
    Parameters: [{ Name: 'RunId', Type: 'String' }],
    Steps: [
      {
        Name: 'TrainDeepAR',
        Type: 'Training',
        Arguments: {
          AlgorithmSpecification: {
            TrainingImage: '382416733822.dkr.ecr.us-east-1.amazonaws.com/forecasting-deepar:1',
            TrainingInputMode: 'File',
          },
          InputDataConfig: [
            {
              ChannelName: 'train',
              DataSource: { S3DataSource: { S3Uri: `s3://${sagemakerBucket}/sagemaker/training/train.jsonl`, S3DataType: 'S3Prefix' } },
              ContentType: 'application/jsonlines',
            },
            {
              ChannelName: 'test',
              DataSource: { S3DataSource: { S3Uri: `s3://${sagemakerBucket}/sagemaker/training/test.jsonl`, S3DataType: 'S3Prefix' } },
              ContentType: 'application/jsonlines',
            },
          ],
          OutputDataConfig: { S3OutputPath: `s3://${sagemakerBucket}/sagemaker/model/` },
          ResourceConfig: { InstanceType: 'ml.c5.xlarge', InstanceCount: 1, VolumeSizeInGB: 20 },
          StoppingCondition: { MaxRuntimeInSeconds: 7200 },
          HyperParameters: {
            prediction_length: '30',
            context_length: '90',
            epochs: '100',
            num_cells: '40',
            num_layers: '2',
            likelihood: 'gaussian',
          },
        },
      },
      {
        Name: 'BatchTransform',
        Type: 'Transform',
        DependsOn: ['TrainDeepAR'],
        Arguments: {
          ModelName: { 'Get': 'Steps.TrainDeepAR.ModelArtifacts.S3ModelArtifacts' },
          TransformInput: {
            DataSource: {
              S3DataSource: {
                S3Uri: { 'Std:Join': { On: '/', Values: [`s3://${sagemakerBucket}/sagemaker/transform-input`, { 'Get': 'Parameters.RunId' }] } },
                S3DataType: 'S3Prefix',
              },
            },
            ContentType: 'application/jsonlines',
            SplitType: 'Line',
          },
          TransformOutput: {
            S3OutputPath: { 'Std:Join': { On: '/', Values: [`s3://${sagemakerBucket}/sagemaker/output`, { 'Get': 'Parameters.RunId' }] } },
            AssembleWith: 'Line',
          },
          TransformResources: { InstanceType: 'ml.m5.large', InstanceCount: 1 },
        },
      },
    ],
  };
}
