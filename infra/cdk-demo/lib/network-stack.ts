import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface NetworkStackProps extends cdk.StackProps {
  srEnv: string;
}

export class NetworkStack extends cdk.Stack {
  // IVpc — resolved from the existing default VPC via context lookup at synth time
  public readonly vpc: ec2.IVpc;
  public readonly sgAlb: ec2.SecurityGroup;
  public readonly sgEcsTasks: ec2.SecurityGroup;
  public readonly sgRds: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: NetworkStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-network-dev');

    const { srEnv } = props;

    // Reuse the account's existing default VPC — no new VPC is created.
    // On first `cdk synth`, CDK contacts AWS to look up the VPC and caches the
    // result in cdk.context.json.  Commit that file after the first synth.
    this.vpc = ec2.Vpc.fromLookup(this, 'DefaultVpc', { isDefault: true });

    this.sgAlb = new ec2.SecurityGroup(this, 'SgAlb', {
      vpc: this.vpc, description: 'ALB - internet-facing HTTP', allowAllOutbound: true,
    });
    this.sgAlb.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP from internet');

    this.sgEcsTasks = new ec2.SecurityGroup(this, 'SgEcsTasks', {
      vpc: this.vpc, description: 'ECS tasks in public subnets', allowAllOutbound: true,
    });
    this.sgEcsTasks.addIngressRule(this.sgAlb,      ec2.Port.tcpRange(8080, 8085), 'ALB to ECS services');
    this.sgEcsTasks.addIngressRule(this.sgEcsTasks, ec2.Port.allTcp(),             'ECS service-to-service');

    this.sgRds = new ec2.SecurityGroup(this, 'SgRds', {
      vpc: this.vpc, description: 'RDS PostgreSQL', allowAllOutbound: false,
    });
    this.sgRds.addIngressRule(this.sgEcsTasks, ec2.Port.tcp(5432), 'ECS to RDS');

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/network/${name}`,
        stringValue: value,
      });

    put('vpc-id',          this.vpc.vpcId);
    put('sg-alb-id',       this.sgAlb.securityGroupId);
    put('sg-ecs-tasks-id', this.sgEcsTasks.securityGroupId);
    put('sg-rds-id',       this.sgRds.securityGroupId);
  }
}
