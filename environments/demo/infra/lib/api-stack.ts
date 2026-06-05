import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as apigw from 'aws-cdk-lib/aws-apigateway';
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
  public readonly apiEndpoint: string;
  /** REST API name — used by MonitoringStack for CloudWatch metric dimensions (ApiName + Stage) */
  public readonly restApiName: string;

  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-api-demo');

    const { srEnv, network, compute } = props;

    // ── Internal NLB ─────────────────────────────────────────────────────────
    // Demo uses the default VPC which has only public subnets — place NLB there.
    // REST API VpcLink is backed by NLB; all HTTP_PROXY routes share one link.
    const nlb = new elbv2.NetworkLoadBalancer(this, 'Nlb', {
      loadBalancerName: `smartretail-nlb-${srEnv}`,
      vpc: network.vpc,
      internetFacing: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    // ── VPC Link (REST API type — backed by NLB) ──────────────────────────────
    const vpcLink = new apigw.VpcLink(this, 'VpcLink', {
      targets: [nlb],
      vpcLinkName: `smartretail-vpclink-${srEnv}`,
    });

    // ── NLB listeners + ECS target groups ────────────────────────────────────
    const addNlbListener = (
      name: string,
      port: number,
      service: ecs.FargateService,
    ): void => {
      const pascal = name.charAt(0).toUpperCase() + name.slice(1);
      const listener = nlb.addListener(`${pascal}Listener`, {
        port,
        protocol: elbv2.Protocol.TCP,
      });
      listener.addTargets(`${pascal}Tg`, {
        port,
        protocol: elbv2.Protocol.TCP,
        targets: [service.loadBalancerTarget({ containerName: `${name}Container`, containerPort: port })],
        healthCheck: {
          enabled: true,
          protocol: elbv2.Protocol.HTTP,
          path: '/actuator/health',
          port: String(port),
          interval: cdk.Duration.seconds(30),
          healthyThresholdCount: 2,
          unhealthyThresholdCount: 3,
        },
        deregistrationDelay: cdk.Duration.seconds(30),
      });
    };

    addNlbListener('ims', 8081, compute.imsService);
    addNlbListener('re',  8082, compute.reService);
    addNlbListener('ars', 8083, compute.arsService);
    addNlbListener('dfs', 8084, compute.dfsService);
    addNlbListener('sup', 8085, compute.supService);

    // ── HTTP_PROXY integration helper ─────────────────────────────────────────
    // URI: http://{nlb-dns}:{port}{pathPrefix}/{proxy} — NLB routes to correct ECS TG.
    // pathPrefix is the static path that the backend controller expects before the
    // proxy variable, e.g. "/v1/dashboard". API Gateway captures only the suffix
    // after the resource path in {proxy}, so we must prepend the prefix in the
    // integration URI to reconstruct the full path the service listens on.
    const nlbProxyIntegration = (port: number, pathPrefix: string) =>
      new apigw.Integration({
        type: apigw.IntegrationType.HTTP_PROXY,
        integrationHttpMethod: 'ANY',
        uri: `http://${nlb.loadBalancerDnsName}:${port}${pathPrefix}/{proxy}`,
        options: {
          connectionType: apigw.ConnectionType.VPC_LINK,
          vpcLink,
          requestParameters: {
            'integration.request.path.proxy': 'method.request.path.proxy',
          },
        },
      });

    // ── REST API ──────────────────────────────────────────────────────────────
    const apiName = `smartretail-api-${srEnv}`;
    const restApi = new apigw.RestApi(this, 'RestApi', {
      restApiName: apiName,
      description: 'SmartRetail Demo REST API — NLB VPC Link to ECS services',
      endpointTypes: [apigw.EndpointType.REGIONAL],
      deployOptions: { stageName: 'internal' },
      defaultCorsPreflightOptions: {
        allowOrigins: apigw.Cors.ALL_ORIGINS,
        allowMethods: apigw.Cors.ALL_METHODS,
        allowHeaders: ['Authorization', 'Content-Type', 'X-Correlation-ID'],
        maxAge: cdk.Duration.hours(1),
      },
    });

    // Helper: add /v1/{pathPart}/{proxy+} with ANY → NLB HTTP_PROXY.
    // pathPrefix must match the path the backend controller listens on, e.g.
    // "/v1/dashboard". It is prepended in the integration URI so the full path
    // reaches the service (API Gateway's {proxy} captures only the suffix).
    const addProxyResource = (
      parent: apigw.IResource,
      pathPart: string,
      port: number,
      pathPrefix: string,
    ): void => {
      parent.addResource(pathPart).addProxy({
        defaultIntegration: nlbProxyIntegration(port, pathPrefix),
        anyMethod: true,
        defaultMethodOptions: {
          requestParameters: { 'method.request.path.proxy': true },
        },
      });
    };

    // Staff APIs — five services, one proxy resource each
    const v1 = restApi.root.addResource('v1');
    addProxyResource(v1, 'dashboard',     8083, '/v1/dashboard');     // ARS
    addProxyResource(v1, 'inventory',     8081, '/v1/inventory');     // IMS
    addProxyResource(v1, 'forecast',      8084, '/v1/forecast');      // DFS
    addProxyResource(v1, 'replenishment', 8082, '/v1/replenishment'); // RE
    addProxyResource(v1, 'supplier',      8085, '/v1/supplier');      // SUP

    this.apiEndpoint = restApi.url;
    this.restApiName = restApi.restApiName;

    // ── SSM outputs ───────────────────────────────────────────────────────────
    new ssm.StringParameter(this, 'ApiEndpointParam', {
      parameterName: `/smartretail/${srEnv}/api/endpoint`,
      stringValue: restApi.url,
    });

    new cdk.CfnOutput(this, 'ApiEndpoint', {
      value: restApi.url,
      description: 'SmartRetail Demo REST API endpoint (internal stage)',
    });
  }
}
