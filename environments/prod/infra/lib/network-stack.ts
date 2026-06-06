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
  public readonly sgRdsProxy: ec2.SecurityGroup;
  public readonly sgRds: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: NetworkStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-network-prod');

    const { srEnv } = props;

    // 3 AZs: Public (NLB) + PrivateApp (ECS, EGRESS via NAT) + Isolated (RDS/Proxy)
    this.vpc = new ec2.Vpc(this, 'Vpc', {
      vpcName: `smartretail-prod-vpc-${srEnv}`,
      maxAzs: 3,
      natGateways: 3,
      subnetConfiguration: [
        { name: 'Public',     subnetType: ec2.SubnetType.PUBLIC,                cidrMask: 24 },
        { name: 'PrivateApp', subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,   cidrMask: 24 },
        { name: 'Isolated',   subnetType: ec2.SubnetType.PRIVATE_ISOLATED,      cidrMask: 24 },
      ],
      enableDnsHostnames: true,
      enableDnsSupport: true,
    });

    // Gateway endpoints — free, no data path through internet
    this.vpc.addGatewayEndpoint('S3Endpoint', { service: ec2.GatewayVpcEndpointAwsService.S3 });

    // Interface endpoints — allow private-subnet services to reach AWS APIs without NAT
    const ifaceEndpointSg = new ec2.SecurityGroup(this, 'SgVpcEndpoints', {
      vpc: this.vpc, description: 'VPC interface endpoints', allowAllOutbound: false,
    });
    ifaceEndpointSg.addIngressRule(ec2.Peer.ipv4(this.vpc.vpcCidrBlock), ec2.Port.tcp(443), 'HTTPS from VPC');

    const ifaceSubnets = { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS };

    [
      ec2.InterfaceVpcEndpointAwsService.ECR,
      ec2.InterfaceVpcEndpointAwsService.ECR_DOCKER,
      ec2.InterfaceVpcEndpointAwsService.SQS,
      ec2.InterfaceVpcEndpointAwsService.EVENTBRIDGE,
      ec2.InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
      ec2.InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
    ].forEach((svc, i) => {
      this.vpc.addInterfaceEndpoint(`IfaceEndpoint${i}`, {
        service: svc,
        subnets: ifaceSubnets,
        securityGroups: [ifaceEndpointSg],
        privateDnsEnabled: true,
      });
    });

    // ECS tasks — private app subnets, reached via NLB (NLB is transparent; allow from VPC CIDR)
    this.sgEcsTasks = new ec2.SecurityGroup(this, 'SgEcsTasks', {
      vpc: this.vpc, description: 'ECS tasks in private app subnets', allowAllOutbound: true,
    });
    this.sgEcsTasks.addIngressRule(
      ec2.Peer.ipv4(this.vpc.vpcCidrBlock),
      ec2.Port.tcpRange(8080, 8086),
      'NLB and VPC traffic to ECS services',
    );
    this.sgEcsTasks.addIngressRule(this.sgEcsTasks, ec2.Port.allTcp(), 'ECS service-to-service');

    // RDS Proxy — isolated subnet, only reachable from ECS tasks
    this.sgRdsProxy = new ec2.SecurityGroup(this, 'SgRdsProxy', {
      vpc: this.vpc, description: 'RDS Proxy', allowAllOutbound: true,
    });
    this.sgRdsProxy.addIngressRule(this.sgEcsTasks, ec2.Port.tcp(5432), 'ECS to RDS Proxy');

    // RDS — isolated subnet, only reachable from RDS Proxy
    this.sgRds = new ec2.SecurityGroup(this, 'SgRds', {
      vpc: this.vpc, description: 'RDS PostgreSQL', allowAllOutbound: false,
    });
    this.sgRds.addIngressRule(this.sgRdsProxy, ec2.Port.tcp(5432), 'RDS Proxy to RDS');

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/network/${name}`,
        stringValue: value,
      });

    put('vpc-id',             this.vpc.vpcId);
    put('sg-ecs-tasks-id',    this.sgEcsTasks.securityGroupId);
    put('sg-rds-proxy-id',    this.sgRdsProxy.securityGroupId);
    put('sg-rds-id',          this.sgRds.securityGroupId);
    // Used by run-flyway-ecs.sh to launch the migration run-task without IP allowlisting
    put('ecs-subnet-ids',   this.vpc.selectSubnets({ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }).subnetIds.join(','));
    put('assign-public-ip', 'DISABLED');
  }
}
