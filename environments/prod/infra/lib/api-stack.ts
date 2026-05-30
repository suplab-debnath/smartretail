import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as apigatewayv2 from 'aws-cdk-lib/aws-apigatewayv2';
import * as apigatewayv2Integrations from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as firehose from 'aws-cdk-lib/aws-kinesisfirehose';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';
import { DataStack } from './data-stack';
import { ComputeStack } from './compute-stack';

export interface ApiStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
  data: DataStack;
  compute: ComputeStack;
}

export class ApiStack extends cdk.Stack {
  public readonly apiEndpoint: string;
  public readonly firehoseStreamName: string;

  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-api-prod');

    const { srEnv, network, data, compute } = props;

    // Internal NLB — routes API GW VPC Link traffic to ECS services
    const nlb = new elbv2.NetworkLoadBalancer(this, 'Nlb', {
      loadBalancerName: `smartretail-nlb-${srEnv}`,
      vpc: network.vpc,
      internetFacing: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
    });

    const routes: Array<{ name: string; path: string; port: number; service: ecs.FargateService }> = [
      { name: 'sis', path: '/v1/ingest/{proxy+}',        port: 8080, service: compute.sisService },
      { name: 'ims', path: '/v1/inventory/{proxy+}',      port: 8081, service: compute.imsService },
      { name: 're',  path: '/v1/replenishment/{proxy+}',  port: 8082, service: compute.reService  },
      { name: 'ars', path: '/v1/dashboard/{proxy+}',      port: 8083, service: compute.arsService },
      { name: 'dfs', path: '/v1/forecast/{proxy+}',       port: 8084, service: compute.dfsService },
      { name: 'sup', path: '/v1/supplier/{proxy+}',       port: 8085, service: compute.supService },
      { name: 'pps', path: '/v1/promotions/{proxy+}',     port: 8086, service: compute.ppsService },
    ];

    // VPC Link — connects API GW HTTP API to the internal NLB
    const vpcLink = new apigatewayv2.VpcLink(this, 'VpcLink', {
      vpc: network.vpc,
      subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      vpcLinkName: `smartretail-vpclink-${srEnv}`,
    });

    // HTTP API
    const httpApi = new apigatewayv2.HttpApi(this, 'HttpApi', {
      apiName: `smartretail-api-${srEnv}`,
      description: 'SmartRetail internal API — routes to ECS services via NLB VPC Link',
    });

    // Per-service: NLB listener → NLB target group → ECS service → API GW route
    routes.forEach(({ name, path, port, service }) => {
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

      const integration = new apigatewayv2Integrations.HttpNlbIntegration(
        `${pascal}Integration`,
        listener,
        { vpcLink },
      );

      httpApi.addRoutes({
        path,
        methods: [apigatewayv2.HttpMethod.ANY],
        integration,
      });
    });

    this.apiEndpoint = httpApi.apiEndpoint;

    // Firehose IAM role — allowed to write S3 backup
    const firehoseRole = new iam.Role(this, 'FirehoseRole', {
      roleName: `smartretail-firehose-${srEnv}`,
      assumedBy: new iam.ServicePrincipal('firehose.amazonaws.com'),
    });
    data.eventsBucket.grantWrite(firehoseRole);

    // Firehose delivery stream — DirectPut → HTTP endpoint (API GW → SIS)
    this.firehoseStreamName = `smartretail-ingest-${srEnv}`;

    new firehose.CfnDeliveryStream(this, 'IngestStream', {
      deliveryStreamName: this.firehoseStreamName,
      deliveryStreamType: 'DirectPut',
      httpEndpointDestinationConfiguration: {
        endpointConfiguration: {
          url: `${httpApi.apiEndpoint}/v1/ingest/events`,
          name: `smartretail-ingest-${srEnv}`,
          accessKey: data.firehoseAccessKeySecret.secretValue.toString(),
        },
        bufferingHints: { sizeInMBs: 1, intervalInSeconds: 60 },
        retryOptions: { durationInSeconds: 86400 },
        s3BackupMode: 'FailedDataOnly',
        s3Configuration: {
          bucketArn: data.eventsBucket.bucketArn,
          roleArn: firehoseRole.roleArn,
          bufferingHints: { sizeInMBs: 5, intervalInSeconds: 60 },
          compressionFormat: 'GZIP',
          prefix: 'firehose/!{timestamp:yyyy/MM/dd}/',
          errorOutputPrefix: 'firehose-errors/!{firehose:error-output-type}/!{timestamp:yyyy/MM/dd}/',
        },
        roleArn: firehoseRole.roleArn,
      },
    });

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('api/endpoint',         httpApi.apiEndpoint);
    put('firehose/stream-name', this.firehoseStreamName);

    new cdk.CfnOutput(this, 'ApiEndpoint', {
      value: httpApi.apiEndpoint,
      description: 'SmartRetail API Gateway HTTPS endpoint',
    });
  }
}
