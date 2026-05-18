import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface NetworkStackProps extends cdk.StackProps {
  srEnv: string;
}

export class NetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;
  public readonly sgEcsTasks: ec2.SecurityGroup;
  public readonly sgLambda: ec2.SecurityGroup;
  public readonly sgApiGatewayLink: ec2.SecurityGroup;
  public readonly sgVpcEndpoints: ec2.SecurityGroup;
  // sgRds and sgRdsProxy live in DataStack to avoid cross-stack cycles
  // (CDK's DatabaseProxy auto-adds ingress rules using dynamic RDS port attributes)

  constructor(scope: Construct, id: string, props: NetworkStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-network-prod');

    const { srEnv } = props;

    this.vpc = new ec2.Vpc(this, 'SmartRetailVpc', {
      vpcName: `smartretail-prod-vpc-${srEnv}`,
      maxAzs: 2,
      natGateways: 2,   // One per AZ for HA — eliminates cross-AZ traffic and AZ dependency
      subnetConfiguration: [
        { name: 'Public',      subnetType: ec2.SubnetType.PUBLIC,            cidrMask: 24 },
        { name: 'PrivateApp',  subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 24 },
        { name: 'PrivateData', subnetType: ec2.SubnetType.PRIVATE_ISOLATED,  cidrMask: 24 },
      ],
      enableDnsHostnames: true,
      enableDnsSupport: true,
    });

    this.sgApiGatewayLink = new ec2.SecurityGroup(this, 'SgApiGatewayLink', {
      vpc: this.vpc, description: 'VPC Link from API Gateway to ECS', allowAllOutbound: true,
    });
    this.sgApiGatewayLink.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(8080), 'Allow from API GW');

    this.sgEcsTasks = new ec2.SecurityGroup(this, 'SgEcsTasks', {
      vpc: this.vpc, description: 'ECS task security group', allowAllOutbound: true,
    });

    this.sgLambda = new ec2.SecurityGroup(this, 'SgLambda', {
      vpc: this.vpc, description: 'Lambda security group', allowAllOutbound: true,
    });

    this.sgVpcEndpoints = new ec2.SecurityGroup(this, 'SgVpcEndpoints', {
      vpc: this.vpc, description: 'VPC Interface Endpoints', allowAllOutbound: false,
    });

    this.sgEcsTasks.addIngressRule(this.sgApiGatewayLink, ec2.Port.tcpRange(8080, 8085), 'API GW VPC Link to ECS');
    this.sgEcsTasks.addIngressRule(this.sgLambda,         ec2.Port.tcp(8080),            'Lambda to SIS');

    // ECS tasks and Lambda have allowAllOutbound=true, so no explicit egress rules needed.
    // Ingress rules on sgRdsProxy are set in DataStack to avoid cross-stack circular dependencies.

    this.sgVpcEndpoints.addIngressRule(this.sgEcsTasks, ec2.Port.tcp(443));
    this.sgVpcEndpoints.addIngressRule(this.sgLambda,   ec2.Port.tcp(443));

    this.vpc.addGatewayEndpoint('S3Endpoint',       { service: ec2.GatewayVpcEndpointAwsService.S3 });
    this.vpc.addGatewayEndpoint('DynamoDBEndpoint', { service: ec2.GatewayVpcEndpointAwsService.DYNAMODB });

    const interfaceServices = [
      ec2.InterfaceVpcEndpointAwsService.SQS,
      ec2.InterfaceVpcEndpointAwsService.EVENTBRIDGE,
      ec2.InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
      ec2.InterfaceVpcEndpointAwsService.ECR,
      ec2.InterfaceVpcEndpointAwsService.ECR_DOCKER,
      ec2.InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
      ec2.InterfaceVpcEndpointAwsService.STS,
    ];
    interfaceServices.forEach((svc, i) => {
      this.vpc.addInterfaceEndpoint(`InterfaceEndpoint${i}`, {
        service: svc,
        securityGroups: [this.sgVpcEndpoints],
        subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      });
    });

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/\//g, ''), {
        parameterName: `/smartretail/${srEnv}/network/${name}`,
        stringValue: value,
      });

    put('vpc-id',                  this.vpc.vpcId);
    put('private-app-subnet-ids',  this.vpc.privateSubnets.map(s => s.subnetId).join(','));
    put('private-data-subnet-ids', this.vpc.isolatedSubnets.map(s => s.subnetId).join(','));
    put('sg-ecs-tasks-id',         this.sgEcsTasks.securityGroupId);
    put('sg-api-gateway-link-id',  this.sgApiGatewayLink.securityGroupId);
  }
}
