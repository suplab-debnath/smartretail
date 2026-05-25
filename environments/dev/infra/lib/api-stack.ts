import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';
import { ComputeStack } from './compute-stack';

export interface ApiStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
  compute: ComputeStack;
}

export class ApiStack extends cdk.Stack {
  public readonly alb: elbv2.ApplicationLoadBalancer;

  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-api-dev');

    const { srEnv, network, compute } = props;

    // ALB replaces HTTP API v2 + VPC Link — saves ~$230/month
    this.alb = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      loadBalancerName: `smartretail-alb-${srEnv}`,
      vpc: network.vpc,
      internetFacing: true,
      securityGroup: network.sgAlb,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    const listener = this.alb.addListener('HttpListener', {
      port: 80,
      defaultAction: elbv2.ListenerAction.fixedResponse(404, {
        contentType: 'application/json',
        messageBody: '{"error":"no matching route"}',
      }),
    });

    const routes: Array<{ name: string; path: string; port: number; service: ecs.FargateService }> = [
      { name: 'sis', path: '/v1/ingest/*',        port: 8080, service: compute.sisService },
      { name: 'ims', path: '/v1/inventory/*',      port: 8081, service: compute.imsService },
      { name: 're',  path: '/v1/replenishment/*',  port: 8082, service: compute.reService  },
      { name: 'ars', path: '/v1/dashboard/*',      port: 8083, service: compute.arsService },
      { name: 'dfs', path: '/v1/forecast/*',       port: 8084, service: compute.dfsService },
      { name: 'sup', path: '/v1/supplier/*',       port: 8085, service: compute.supService },
      { name: 'pps', path: '/v1/promotions/*',     port: 8086, service: compute.ppsService },
    ];

    routes.forEach(({ name, path, port, service }, i) => {
      const pascal = name.charAt(0).toUpperCase() + name.slice(1);
      listener.addTargets(`${pascal}Targets`, {
        targetGroupName: `sr-${srEnv}-${name}`,
        port,
        protocol: elbv2.ApplicationProtocol.HTTP,
        targets: [service.loadBalancerTarget({ containerName: `${name}Container`, containerPort: port })],
        healthCheck: {
          path: '/actuator/health',
          interval: cdk.Duration.seconds(30),
          healthyThresholdCount: 2,
          unhealthyThresholdCount: 3,
          healthyHttpCodes: '200',
        },
        conditions: [elbv2.ListenerCondition.pathPatterns([path])],
        priority: (i + 1) * 10,
      });
    });

    new ssm.StringParameter(this, 'AlbEndpointParam', {
      parameterName: `/smartretail/${srEnv}/api/endpoint`,
      stringValue: `http://${this.alb.loadBalancerDnsName}`,
    });

    new cdk.CfnOutput(this, 'AlbEndpoint', {
      value: `http://${this.alb.loadBalancerDnsName}`,
      description: 'SmartRetail ALB Endpoint (HTTP)',
    });
  }
}
