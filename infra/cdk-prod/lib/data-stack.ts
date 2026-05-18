import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as s3 from 'aws-cdk-lib/aws-s3';   // used for events bucket
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';

export interface DataStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
}

export class DataStack extends cdk.Stack {
  public readonly dbEndpoint: string;
  public readonly sgRdsProxy: ec2.SecurityGroup;
  public readonly idempotencyTable: dynamodb.Table;
  public readonly eventsBucketName: string;
  // MFE buckets live in HostingStack — co-locating with CloudFront avoids OAC cross-stack cycles

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-data-prod');

    const { srEnv, network } = props;
    const account = this.account;

    // RDS security groups live here (not NetworkStack) to avoid cross-stack cycles:
    // DatabaseProxy auto-adds ingress rules using the dynamic RDS Endpoint.Port attribute.
    // If sgRds were in NetworkStack, that would create NetworkStack → DataStack, cycling with
    // DataStack → NetworkStack (for vpc/subnets).
    this.sgRdsProxy = new ec2.SecurityGroup(this, 'SgRdsProxy', {
      vpc: network.vpc, description: 'RDS Proxy security group', allowAllOutbound: true,
    });
    const sgRds = new ec2.SecurityGroup(this, 'SgRds', {
      vpc: network.vpc, description: 'RDS instance security group', allowAllOutbound: false,
    });

    // Allow ECS tasks and Lambda (via their NetworkStack SGs) to reach the proxy
    this.sgRdsProxy.addIngressRule(network.sgEcsTasks, ec2.Port.tcp(5432), 'ECS tasks to RDS Proxy');
    this.sgRdsProxy.addIngressRule(network.sgLambda,   ec2.Port.tcp(5432), 'Lambda to RDS Proxy');

    // RDS — t3.medium (x86), Multi-AZ, 100 GB gp3 for production workload
    const rdsInstance = new rds.DatabaseInstance(this, 'SmartRetailRds', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_16_4,
      }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM),
      allocatedStorage: 100,
      storageType: rds.StorageType.GP3,
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [sgRds],
      multiAz: true,
      databaseName: 'smartretail',
      credentials: rds.Credentials.fromGeneratedSecret('smartretail_admin'),
      backupRetention: cdk.Duration.days(7),
      deletionProtection: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      enablePerformanceInsights: true,
      performanceInsightRetention: rds.PerformanceInsightRetention.DEFAULT,
      monitoringInterval: cdk.Duration.seconds(60),
      storageEncrypted: true,
    });

    // RDS Proxy — connection pooling for Fargate scale-out
    const rdsProxy = new rds.DatabaseProxy(this, 'RdsProxy', {
      proxyTarget: rds.ProxyTarget.fromInstance(rdsInstance),
      secrets: [rdsInstance.secret!],
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [this.sgRdsProxy],
      dbProxyName: `smartretail-${srEnv}-proxy`,
      idleClientTimeout: cdk.Duration.minutes(30),
      maxConnectionsPercent: 80,
      requireTLS: true,
    });

    // Proxy endpoint is what ECS services connect to
    this.dbEndpoint = rdsProxy.endpoint;

    // DynamoDB — on-demand, TTL for idempotency
    this.idempotencyTable = new dynamodb.Table(this, 'IdempotencyKeys', {
      tableName: `smartretail-idempotency-keys-${srEnv}`,
      partitionKey: { name: 'event_id', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      timeToLiveAttribute: 'expires_at',
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // Events S3 bucket
    const eventsBucket = new s3.Bucket(this, 'EventsBucket', {
      bucketName: `smartretail-events-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(365 * 7) }],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    this.eventsBucketName = eventsBucket.bucketName;

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('rds/instance-endpoint',           rdsInstance.instanceEndpoint.hostname);
    put('rds/proxy-endpoint',              rdsProxy.endpoint);
    put('rds/secret-arn',                  rdsInstance.secret!.secretArn);
    put('dynamodb/idempotency-table-name', this.idempotencyTable.tableName);
    put('s3/events-bucket-name',           eventsBucket.bucketName);
  }
}
