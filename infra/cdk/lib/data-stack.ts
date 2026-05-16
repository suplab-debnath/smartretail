import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';

export interface DataStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
}

export class DataStack extends cdk.Stack {
  public readonly dbEndpoint: string;
  public readonly idempotencyTable: dynamodb.Table;
  public readonly eventsBucketName: string;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-data');

    const { srEnv, network } = props;
    const account = this.account;

    // ── RDS PostgreSQL ─────────────────────────────────────────────────
    const rdsInstance = new rds.DatabaseInstance(this, 'SmartRetailRds', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_18_3,
      }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      allocatedStorage: 20,
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [network.sgRds],
      multiAz: srEnv === 'prod',
      databaseName: 'smartretail',
      credentials: rds.Credentials.fromGeneratedSecret('smartretail_admin'),
      backupRetention: cdk.Duration.days(7),
      deletionProtection: false,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      enablePerformanceInsights: true,
      storageEncrypted: true,
    });

    this.dbEndpoint = rdsInstance.instanceEndpoint.hostname;

    // ── DynamoDB ──────────────────────────────────────────────────────────────
    this.idempotencyTable = new dynamodb.Table(this, 'IdempotencyKeys', {
      tableName: `smartretail-idempotency-keys-${srEnv}`,
      partitionKey: { name: 'event_id', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PROVISIONED,
      readCapacity: 1,
      writeCapacity: 1,
      timeToLiveAttribute: 'expires_at',
      removalPolicy: srEnv === 'prod' ? cdk.RemovalPolicy.RETAIN : cdk.RemovalPolicy.DESTROY,
    });

    // ── S3 Buckets ────────────────────────────────────────────────────────────
    const eventsBucket = new s3.Bucket(this, 'EventsBucket', {
      bucketName: `smartretail-events-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: true,
      lifecycleRules: [{ expiration: cdk.Duration.days(365 * 7) }],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    this.eventsBucketName = eventsBucket.bucketName;

    ['store-manager', 'sc-planner', 'executive'].forEach(mfe => {
      new s3.Bucket(this, `MfeBucket${mfe}`, {
        bucketName: `smartretail-mfe-${srEnv}-${mfe}-${account}`,
        encryption: s3.BucketEncryption.S3_MANAGED,
        blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
        versioned: true,
        removalPolicy: cdk.RemovalPolicy.DESTROY,
      });
    });

    // ── SSM Outputs ───────────────────────────────────────────────────────────
    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('rds/instance-endpoint', rdsInstance.instanceEndpoint.hostname);
    put('rds/secret-arn', rdsInstance.secret!.secretArn);
    put('dynamodb/idempotency-table-name', this.idempotencyTable.tableName);
    put('s3/events-bucket-name', eventsBucket.bucketName);
  }
}
