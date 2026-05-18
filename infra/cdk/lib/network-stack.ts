import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface NetworkStackProps extends cdk.StackProps {
  srEnv: string;
}

export class NetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;
  public readonly sgAlb: ec2.SecurityGroup;
  public readonly sgEcsTasks: ec2.SecurityGroup;
  public readonly sgLambda: ec2.SecurityGroup;
  public readonly sgRds: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: NetworkStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-network-demo');

    const { srEnv } = props;

    // Public subnets for ECS/ALB + isolated for RDS only — no NAT, no VPC endpoints
    this.vpc = new ec2.Vpc(this, 'Vpc', {
      vpcName: `smartretail-demo-vpc-${srEnv}`,
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        { name: 'Public',   subnetType: ec2.SubnetType.PUBLIC,           cidrMask: 24 },
        { name: 'Isolated', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
      enableDnsHostnames: true,
      enableDnsSupport: true,
    });

    // Gateway endpoints are free — keep them for S3/DynamoDB access without internet round-trip
    this.vpc.addGatewayEndpoint('S3Endpoint',       { service: ec2.GatewayVpcEndpointAwsService.S3 });
    this.vpc.addGatewayEndpoint('DynamoDBEndpoint', { service: ec2.GatewayVpcEndpointAwsService.DYNAMODB });

    // ALB — internet-facing HTTP
    this.sgAlb = new ec2.SecurityGroup(this, 'SgAlb', {
      vpc: this.vpc, description: 'ALB — internet-facing HTTP', allowAllOutbound: true,
    });
    this.sgAlb.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP from internet');

    // ECS tasks — public subnets, reached via ALB
    this.sgEcsTasks = new ec2.SecurityGroup(this, 'SgEcsTasks', {
      vpc: this.vpc, description: 'ECS tasks in public subnets', allowAllOutbound: true,
    });
    this.sgEcsTasks.addIngressRule(this.sgAlb,      ec2.Port.tcpRange(8080, 8085), 'ALB to ECS services');
    this.sgEcsTasks.addIngressRule(this.sgEcsTasks, ec2.Port.allTcp(),             'ECS service-to-service');

    // Lambda — Kinesis consumer, calls SIS via CloudMap
    this.sgLambda = new ec2.SecurityGroup(this, 'SgLambda', {
      vpc: this.vpc, description: 'Kinesis consumer Lambda', allowAllOutbound: true,
    });
    this.sgEcsTasks.addIngressRule(this.sgLambda, ec2.Port.tcp(8080), 'Lambda to SIS');

    // RDS — isolated subnet, only reachable from ECS/Lambda
    this.sgRds = new ec2.SecurityGroup(this, 'SgRds', {
      vpc: this.vpc, description: 'RDS PostgreSQL', allowAllOutbound: false,
    });
    this.sgRds.addIngressRule(this.sgEcsTasks, ec2.Port.tcp(5432), 'ECS to RDS');
    this.sgRds.addIngressRule(this.sgLambda,   ec2.Port.tcp(5432), 'Lambda to RDS');

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
