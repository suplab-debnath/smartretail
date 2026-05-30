import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as logs from 'aws-cdk-lib/aws-logs';
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
  public readonly rdsInstance: rds.DatabaseInstance;
  public readonly posArchiveBucket: s3.Bucket;
  public readonly sagemakerBucket: s3.Bucket;
  public readonly mfeBuckets: Record<string, s3.Bucket> = {};

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-data-dev');

    const { srEnv, network } = props;
    const account = this.account;

    // RDS — t4g.small, single-AZ (dev sizing), private isolated subnets
    this.rdsInstance = new rds.DatabaseInstance(this, 'Rds', {
      instanceIdentifier: `smartretail-rds-${srEnv}`,
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_16_13,
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
      cloudwatchLogsExports: ['postgresql'],
      cloudwatchLogsRetention: logs.RetentionDays.ONE_MONTH,
    });

    // RDS Proxy — same pattern as prod
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

    // POS archive bucket — Firehose delivers raw POS events here (transit buffer, not long-term store)
    // SIS writes to Firehose; Firehose writes here. Key prefix: pos-events/{yyyy}/{MM}/{dd}/{HH}/
    this.posArchiveBucket = new s3.Bucket(this, 'PosArchiveBucket', {
      bucketName: `smartretail-pos-archive-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(30) }],
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });

    // SageMaker S3 bucket — training data, model artefacts, transform output
    // Key prefix convention: sagemaker/output/{run_id}/part-*.csv  (read by Batch Post-Processor Lambda)
    this.sagemakerBucket = new s3.Bucket(this, 'SageMakerBucket', {
      bucketName: `smartretail-sagemaker-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(365) }],
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });

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

    put('rds/proxy-endpoint',          proxy.endpoint);
    put('rds/secret-arn',              this.rdsInstance.secret!.secretArn);
    put('s3/pos-archive-bucket-name',  this.posArchiveBucket.bucketName);
    put('s3/sagemaker-bucket-name',    this.sagemakerBucket.bucketName);
  }
}
