import * as cdk from 'aws-cdk-lib';
import * as ec2  from 'aws-cdk-lib/aws-ec2';
import * as ssm  from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface NetworkStackProps extends cdk.StackProps {
  srEnv: string;
}

export class NetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;
  public readonly sgEcsTasks:       ec2.SecurityGroup;
  public readonly sgRdsProxy:       ec2.SecurityGroup;
  public readonly sgRds:            ec2.SecurityGroup;
  public readonly sgApiGatewayLink: ec2.SecurityGroup;
  public readonly sgVpcEndpoints:   ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: NetworkStackProps) {
    super(scope, id, props);
    const { srEnv } = props;

    // ── VPC ──────────────────────────────────────────────────────────────────
    this.vpc = new ec2.Vpc(this, 'SmartRetailVpc', {
      vpcName: `smartretail-vpc-${srEnv}`,
      maxAzs: 3,
      natGateways: 3,
      subnetConfiguration: [
        { name: 'Public',      subnetType: ec2.SubnetType.PUBLIC,            cidrMask: 24 },
        { name: 'PrivateApp',  subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 24 },
        { name: 'PrivateData', subnetType: ec2.SubnetType.PRIVATE_ISOLATED,  cidrMask: 24 },
      ],
      enableDnsHostnames: true,
      enableDnsSupport: true,
    });

    // ── Security Groups ───────────────────────────────────────────────────────
    this.sgApiGatewayLink = new ec2.SecurityGroup(this, 'SgApiGatewayLink', {
      vpc: this.vpc, description: 'VPC Link from API Gateway to ECS', allowAllOutbound: true,
    });
    this.sgApiGatewayLink.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(8080));

    this.sgEcsTasks = new ec2.SecurityGroup(this, 'SgEcsTasks', {
      vpc: this.vpc, description: 'ECS task security group', allowAllOutbound: false,
    });
    this.sgEcsTasks.addIngressRule(this.sgApiGatewayLink, ec2.Port.tcp(8080));

    this.sgRdsProxy = new ec2.SecurityGroup(this, 'SgRdsProxy', {
      vpc: this.vpc, description: 'RDS Proxy security group', allowAllOutbound: false,
    });
    this.sgRdsProxy.addIngressRule(this.sgEcsTasks, ec2.Port.tcp(5432));

    this.sgRds = new ec2.SecurityGroup(this, 'SgRds', {
      vpc: this.vpc, description: 'RDS instance security group', allowAllOutbound: false,
    });
    this.sgRds.addIngressRule(this.sgRdsProxy, ec2.Port.tcp(5432));

    this.sgVpcEndpoints = new ec2.SecurityGroup(this, 'SgVpcEndpoints', {
      vpc: this.vpc, description: 'VPC Interface Endpoints', allowAllOutbound: false,
    });
    this.sgVpcEndpoints.addIngressRule(this.sgEcsTasks, ec2.Port.tcp(443));

    // Allow ECS tasks to reach VPC endpoints and egress via NAT
    this.sgEcsTasks.addEgressRule(this.sgRdsProxy,     ec2.Port.tcp(5432));
    this.sgEcsTasks.addEgressRule(this.sgVpcEndpoints, ec2.Port.tcp(443));
    this.sgEcsTasks.addEgressRule(ec2.Peer.anyIpv4(),  ec2.Port.tcp(443)); // for SDK calls via NAT
    this.sgRdsProxy.addEgressRule(this.sgRds,          ec2.Port.tcp(5432));

    // ── VPC Endpoints ─────────────────────────────────────────────────────────
    this.vpc.addGatewayEndpoint('S3Endpoint',       { service: ec2.GatewayVpcEndpointAwsService.S3 });
    this.vpc.addGatewayEndpoint('DynamoDBEndpoint', { service: ec2.GatewayVpcEndpointAwsService.DYNAMODB });

    const interfaceServices = [
      ec2.InterfaceVpcEndpointAwsService.SQS,
      ec2.InterfaceVpcEndpointAwsService.EVENTBRIDGE,
      ec2.InterfaceVpcEndpointAwsService.KMS,
      ec2.InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
      ec2.InterfaceVpcEndpointAwsService.ECR,
      ec2.InterfaceVpcEndpointAwsService.ECR_DOCKER,
      ec2.InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
    ];
    interfaceServices.forEach((svc, i) => {
      this.vpc.addInterfaceEndpoint(`InterfaceEndpoint${i}`, {
        service: svc,
        securityGroups: [this.sgVpcEndpoints],
        subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      });
    });

    // ── SSM Outputs ───────────────────────────────────────────────────────────
    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/\//g, ''), {
        parameterName: `/smartretail/${srEnv}/network/${name}`,
        stringValue: value,
      });

    put('vpc-id',                    this.vpc.vpcId);
    put('private-app-subnet-ids',    this.vpc.privateSubnets.map(s => s.subnetId).join(','));
    put('private-data-subnet-ids',   this.vpc.isolatedSubnets.map(s => s.subnetId).join(','));
    put('sg-ecs-tasks-id',           this.sgEcsTasks.securityGroupId);
    put('sg-rds-proxy-id',           this.sgRdsProxy.securityGroupId);
    put('sg-rds-id',                 this.sgRds.securityGroupId);
    put('sg-api-gateway-link-id',    this.sgApiGatewayLink.securityGroupId);
  }
}
