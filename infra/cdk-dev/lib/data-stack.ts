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
  public readonly mfeBuckets: Record<string, s3.Bucket> = {};

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-data-dev');

    const { srEnv, network } = props;
    const account = this.account;

    // RDS — t4g.small, single-AZ (dev sizing)
    const rdsInstance = new rds.DatabaseInstance(this, 'Rds', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_16_4,
      }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.SMALL),
      allocatedStorage: 20,
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [network.sgRds],
      multiAz: false,
      databaseName: 'smartretail',
      credentials: rds.Credentials.fromGeneratedSecret('smartretail_admin'),
      backupRetention: cdk.Duration.days(1),
      deletionProtection: false,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      enablePerformanceInsights: false,
      storageEncrypted: true,
    });

    // RDS Proxy — same pattern as prod
    const proxy = rdsInstance.addProxy('RdsProxy', {
      proxyName: `smartretail-rds-proxy-${srEnv}`,
      secrets: [rdsInstance.secret!],
      vpc: network.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [network.sgRdsProxy],
      requireTLS: false,
      iamAuth: false,
    });

    this.dbEndpoint = proxy.endpoint;

    // DynamoDB — on-demand, TTL for idempotency
    this.idempotencyTable = new dynamodb.Table(this, 'IdempotencyKeys', {
      tableName: `smartretail-idempotency-keys-${srEnv}`,
      partitionKey: { name: 'event_id', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      timeToLiveAttribute: 'expires_at',
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // Events S3 bucket
    const eventsBucket = new s3.Bucket(this, 'EventsBucket', {
      bucketName: `smartretail-events-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(365 * 7) }],
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });
    this.eventsBucketName = eventsBucket.bucketName;

    // MFE S3 buckets — private, served via CloudFront (same pattern as prod)
    ['store-manager', 'sc-planner', 'executive', 'supplier'].forEach(mfe => {
      const id = mfe.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join('');
      this.mfeBuckets[mfe] = new s3.Bucket(this, `MfeBucket${id}`, {
        bucketName: `smartretail-mfe-${srEnv}-${mfe}-${account}`,
        blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
        removalPolicy: cdk.RemovalPolicy.DESTROY,
        autoDeleteObjects: true,
      });
    });

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('rds/proxy-endpoint',              proxy.endpoint);
    put('rds/secret-arn',                  rdsInstance.secret!.secretArn);
    put('dynamodb/idempotency-table-name', this.idempotencyTable.tableName);
    put('s3/events-bucket-name',           eventsBucket.bucketName);
  }
}
