import * as cdk from "aws-cdk-lib";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as ecr from "aws-cdk-lib/aws-ecr";
import * as iam from "aws-cdk-lib/aws-iam";
import * as rds from "aws-cdk-lib/aws-rds";
import * as s3 from "aws-cdk-lib/aws-s3";
import * as secretsmanager from "aws-cdk-lib/aws-secretsmanager";
import * as logs from "aws-cdk-lib/aws-logs";
import * as ssm from "aws-cdk-lib/aws-ssm";
import { Construct } from "constructs";
import { NetworkStack } from "./network-stack";

export interface DataStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
}

// Services whose ECR repos must exist before ComputeStack deploys ECS services.
const DEMO_SERVICES = ["sis", "ims", "re", "ars", "dfs", "sup"] as const;

export class DataStack extends cdk.Stack {
  public readonly dbEndpoint: string;
  public readonly rdsInstance: rds.DatabaseInstance;
  public readonly ecrRepos: Record<string, ecr.Repository> = {};
  public readonly eventsBucket: s3.Bucket;
  public readonly sagemakerBucket: s3.Bucket;
  public readonly sagemakerExecutionRole: iam.Role;
  public readonly firehoseAccessKeySecret: secretsmanager.Secret;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add("Name", "smartretail-data-demo");

    const { srEnv, network } = props;

    // RDS — public subnet (default VPC has no isolated subnets); access restricted to ECS SG only
    this.rdsInstance = new rds.DatabaseInstance(this, "Rds", {
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
      databaseName: "smartretail",
      credentials: rds.Credentials.fromGeneratedSecret("smartretail_admin", {
        secretName: `smartretail-rds-secret-${srEnv}`,
      }),
      backupRetention: cdk.Duration.days(0),
      autoMinorVersionUpgrade: false,
      deletionProtection: false,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      enablePerformanceInsights: false,
      storageEncrypted: true,
      cloudwatchLogsExports: ["postgresql"],
      cloudwatchLogsRetention: logs.RetentionDays.TWO_WEEKS,
    });

    this.dbEndpoint = this.rdsInstance.instanceEndpoint.hostname;

    // ECR repos — created here (DataStack) so images can be pushed before
    // ComputeStack runs, preventing the Fargate circuit-breaker on first deploy.
    for (const svc of DEMO_SERVICES) {
      this.ecrRepos[svc] = new ecr.Repository(this, `${svc}Repo`, {
        repositoryName: `smartretail-${svc}-${srEnv}`,
        removalPolicy: cdk.RemovalPolicy.DESTROY,
        emptyOnDelete: true,
        lifecycleRules: [{ maxImageCount: 5 }],
      });
    }

    // ── Firehose access key ───────────────────────────────────────────────────
    // SIS FirehoseBatchFilter validates incoming Firehose deliveries against this key.
    this.firehoseAccessKeySecret = new secretsmanager.Secret(this, "FirehoseIngestKey", {
      secretName: `/smartretail/${srEnv}/firehose/ingest-access-key`,
      generateSecretString: {
        excludePunctuation: true,
        passwordLength: 64,
      },
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // ── S3 buckets ────────────────────────────────────────────────────────────
    const account = this.account;

    this.eventsBucket = new s3.Bucket(this, "EventsBucket", {
      bucketName: `smartretail-events-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(7) }],
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });

    this.sagemakerBucket = new s3.Bucket(this, "SageMakerBucket", {
      bucketName: `smartretail-sagemaker-${srEnv}-${account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(7) }],
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
      eventBridgeEnabled: true, // routes S3 notifications via EventBridge — avoids cross-stack cycle with ApiStack Lambda ARN
    });

    // ── SageMaker execution role ───────────────────────────────────────────────
    this.sagemakerExecutionRole = new iam.Role(this, "SageMakerExecutionRole", {
      roleName: `smartretail-sagemaker-execution-${srEnv}`,
      assumedBy: new iam.ServicePrincipal("sagemaker.amazonaws.com"),
    });
    this.sagemakerExecutionRole.addToPolicy(new iam.PolicyStatement({
      sid: "SageMakerJobActions",
      actions: [
        "sagemaker:CreateTrainingJob",
        "sagemaker:DescribeTrainingJob",
        "sagemaker:StopTrainingJob",
        "sagemaker:CreateTransformJob",
        "sagemaker:DescribeTransformJob",
        "sagemaker:StopTransformJob",
      ],
      resources: [
        `arn:aws:sagemaker:${this.region}:${account}:training-job/smartretail-*`,
        `arn:aws:sagemaker:${this.region}:${account}:transform-job/smartretail-*`,
      ],
    }));
    this.sagemakerExecutionRole.addToPolicy(new iam.PolicyStatement({
      sid: "CloudWatchLogs",
      actions: ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
      resources: [`arn:aws:logs:${this.region}:${account}:log-group:/aws/sagemaker/*`],
    }));
    this.sagemakerBucket.grantReadWrite(this.sagemakerExecutionRole);
    this.eventsBucket.grantRead(this.sagemakerExecutionRole);

    // ── ECR repos for Lambda adapters ─────────────────────────────────────────
    for (const name of ["batch-post-processor", "ml-trigger"] as const) {
      this.ecrRepos[name] = new ecr.Repository(this, `${name}Repo`.replace(/-/g, ""), {
        repositoryName: `smartretail-${name}-${srEnv}`,
        removalPolicy: cdk.RemovalPolicy.DESTROY,
        emptyOnDelete: true,
        lifecycleRules: [{ maxImageCount: 5 }],
      });
    }

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ""), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put("rds/instance-endpoint", this.rdsInstance.instanceEndpoint.hostname);
    put("rds/secret-arn", this.rdsInstance.secret!.secretArn);
    put("firehose/access-key-secret-arn", this.firehoseAccessKeySecret.secretArn);
    put("s3/events-bucket-name", this.eventsBucket.bucketName);
    put("s3/sagemaker-bucket-name", this.sagemakerBucket.bucketName);
    put("sagemaker/execution-role-arn", this.sagemakerExecutionRole.roleArn);
  }
}
