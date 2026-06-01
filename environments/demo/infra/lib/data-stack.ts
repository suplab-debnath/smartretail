import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';

export interface DataStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
}

// Services whose ECR repos must exist before ComputeStack deploys ECS services.
const DEMO_SERVICES = ['ims', 're', 'ars', 'dfs', 'sup'] as const;

export class DataStack extends cdk.Stack {
  public readonly dbEndpoint: string;
  public readonly rdsInstance: rds.DatabaseInstance;
  public readonly ecrRepos: Record<string, ecr.Repository> = {};

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-data-demo');

    const { srEnv, network } = props;

    // RDS — public subnet (default VPC has no isolated subnets); access restricted to ECS SG only
    this.rdsInstance = new rds.DatabaseInstance(this, 'Rds', {
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
      databaseName: 'smartretail',
      credentials: rds.Credentials.fromGeneratedSecret('smartretail_admin', {
        secretName: `smartretail-rds-secret-${srEnv}`,
      }),
      backupRetention: cdk.Duration.days(0),
      autoMinorVersionUpgrade: false,
      deletionProtection: false,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      enablePerformanceInsights: false,
      storageEncrypted: true,
      cloudwatchLogsExports: ['postgresql'],
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

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('rds/instance-endpoint', this.rdsInstance.instanceEndpoint.hostname);
    put('rds/secret-arn', this.rdsInstance.secret!.secretArn);
  }
}
