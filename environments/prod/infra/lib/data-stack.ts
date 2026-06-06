import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';

export interface DataStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
}

export class DataStack extends cdk.Stack {
  public readonly dbEndpoint: string;
  public readonly rdsInstance: rds.DatabaseInstance;
  public readonly eventsBucket: s3.Bucket;
  public readonly eventsBucketName: string;
  public readonly sagemakerBucket: s3.Bucket;
  public readonly sagemakerExecutionRole: iam.Role;
  public readonly firehoseAccessKeySecret: secretsmanager.Secret;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-data-prod');

    const { srEnv, network } = props;
    const account = this.account;

    // RDS — r6g.large, Multi-AZ, 7-day backup, performance insights
    this.rdsInstance = new rds.DatabaseInstance(this, 'Rds', {
      instanceIdentifier: `smartretail-rds-${srEnv}`,
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_16_13,
      }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.R6G, ec2.InstanceSize.LARGE),
      allocatedStorage: 100,
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [network.sgRds],
      multiAz: true,
      databaseName: 'smartretail',
      credentials: rds.Credentials.fromGeneratedSecret('smartretail_admin'),
      backupRetention: cdk.Duration.days(7),
      deletionProtection: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      enablePerformanceInsights: true,
      storageEncrypted: true,
    });

    // RDS Proxy — Secrets Manager auth, isolated subnet
    const proxy = this.rdsInstance.addProxy('RdsProxy', {
      dbProxyName: `smartretail-rds-proxy-${srEnv}`,
      secrets: [this.rdsInstance.secret!],
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [network.sgRdsProxy],
      requireTLS: false,
      iamAuth: false,
    });

    this.dbEndpoint = proxy.endpoint;

    // Firehose ingest access key — used by SIS FirehoseBatchFilter to validate Firehose deliveries
    this.firehoseAccessKeySecret = new secretsmanager.Secret(this, 'FirehoseIngestKey', {
      secretName: `/smartretail/${srEnv}/firehose/ingest-access-key`,
      generateSecretString: {
        excludePunctuation: true,
        passwordLength: 64,
      },
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // Events S3 bucket — Firehose S3 backup destination (versioned in prod)
    this.eventsBucket = new s3.Bucket(this, 'EventsBucket', {
      bucketName: `smartretail-events-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: true,
      lifecycleRules: [{ expiration: cdk.Duration.days(365 * 7) }],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    this.eventsBucketName = this.eventsBucket.bucketName;

    // SageMaker S3 bucket — training data, model artefacts, transform output
    this.sagemakerBucket = new s3.Bucket(this, 'SageMakerBucket', {
      bucketName: `smartretail-sagemaker-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: true,
      lifecycleRules: [{ expiration: cdk.Duration.days(365 * 3) }],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // SageMaker execution role — assumed by training and batch transform jobs.
    // Scoped to minimum required actions; AmazonSageMakerFullAccess is intentionally
    // avoided to limit blast radius.
    this.sagemakerExecutionRole = new iam.Role(this, 'SageMakerExecutionRole', {
      roleName: `smartretail-sagemaker-execution-${srEnv}`,
      assumedBy: new iam.ServicePrincipal('sagemaker.amazonaws.com'),
    });
    this.sagemakerExecutionRole.addToPolicy(new iam.PolicyStatement({
      sid: 'SageMakerJobActions',
      actions: [
        'sagemaker:CreateTrainingJob',
        'sagemaker:DescribeTrainingJob',
        'sagemaker:StopTrainingJob',
        'sagemaker:CreateTransformJob',
        'sagemaker:DescribeTransformJob',
        'sagemaker:StopTransformJob',
      ],
      resources: [
        `arn:aws:sagemaker:${this.region}:${account}:training-job/smartretail-*`,
        `arn:aws:sagemaker:${this.region}:${account}:transform-job/smartretail-*`,
      ],
    }));
    this.sagemakerExecutionRole.addToPolicy(new iam.PolicyStatement({
      sid: 'CloudWatchLogs',
      actions: [
        'logs:CreateLogGroup',
        'logs:CreateLogStream',
        'logs:PutLogEvents',
      ],
      resources: [`arn:aws:logs:${this.region}:${account}:log-group:/aws/sagemaker/*`],
    }));
    this.sagemakerBucket.grantReadWrite(this.sagemakerExecutionRole);

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('rds/proxy-endpoint',            proxy.endpoint);
    put('rds/secret-arn',                this.rdsInstance.secret!.secretArn);
    put('firehose/access-key-secret-arn', this.firehoseAccessKeySecret.secretArn);
    put('s3/events-bucket-name',         this.eventsBucket.bucketName);
    put('s3/sagemaker-bucket-name',      this.sagemakerBucket.bucketName);
    put('sagemaker/execution-role-arn',  this.sagemakerExecutionRole.roleArn);
  }
}
