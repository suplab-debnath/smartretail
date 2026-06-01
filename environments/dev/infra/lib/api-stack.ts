import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as apigw from 'aws-cdk-lib/aws-apigateway';
import * as firehose from 'aws-cdk-lib/aws-kinesisfirehose';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';
import { DataStack } from './data-stack';
import { MessagingStack } from './messaging-stack';
import { ComputeStack } from './compute-stack';

export interface ApiStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
  data: DataStack;
  messaging: MessagingStack;
  compute: ComputeStack;
}

export class ApiStack extends cdk.Stack {
  public readonly apiEndpoint: string;
  public readonly firehoseStreamName: string;

  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-api-dev');

    const { srEnv, network, data, messaging, compute } = props;

    // ── Internal NLB ─────────────────────────────────────────────────────────
    // REST API VPC Link is backed by NLB. All HTTP_PROXY route groups share it.
    const nlb = new elbv2.NetworkLoadBalancer(this, 'Nlb', {
      loadBalancerName: `smartretail-nlb-${srEnv}`,
      vpc: network.vpc,
      internetFacing: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
    });

    // ── VPC Link (REST API type — backed by NLB) ──────────────────────────────
    const vpcLink = new apigw.VpcLink(this, 'VpcLink', {
      targets: [nlb],
      vpcLinkName: `smartretail-vpclink-${srEnv}`,
    });

    // ── Per-service NLB listeners + target groups ─────────────────────────────
    // Returns NLB listener so its loadBalancer.loadBalancerDnsName is available
    // for the HTTP_PROXY integration URI.
    const addNlbRoute = (
      name: string,
      port: number,
      service: ecs.FargateService,
    ): elbv2.NetworkListener => {
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
      return listener;
    };

    addNlbRoute('sis', 8080, compute.sisService);
    addNlbRoute('ims', 8081, compute.imsService);
    addNlbRoute('re',  8082, compute.reService);
    addNlbRoute('ars', 8083, compute.arsService);
    addNlbRoute('dfs', 8084, compute.dfsService);
    addNlbRoute('sup', 8085, compute.supService);
    addNlbRoute('pps', 8086, compute.ppsService);

    // ── HTTP_PROXY integration helper ─────────────────────────────────────────
    // URI: http://{nlb-dns}:{port}/{proxy} — NLB listener routes to correct ECS TG
    const nlbProxyIntegration = (port: number) =>
      new apigw.Integration({
        type: apigw.IntegrationType.HTTP_PROXY,
        integrationHttpMethod: 'ANY',
        uri: `http://${nlb.loadBalancerDnsName}:${port}/{proxy}`,
        options: {
          connectionType: apigw.ConnectionType.VPC_LINK,
          vpcLink,
          requestParameters: {
            'integration.request.path.proxy': 'method.request.path.proxy',
          },
        },
      });

    // ── REST API ──────────────────────────────────────────────────────────────
    const restApi = new apigw.RestApi(this, 'RestApi', {
      restApiName: `smartretail-api-${srEnv}`,
      description: 'SmartRetail REST API — VPC Link + EventBridge service integrations',
      endpointTypes: [apigw.EndpointType.REGIONAL],
      deployOptions: { stageName: 'internal' },
      defaultCorsPreflightOptions: {
        allowOrigins: ['https://*.smartretail.com'],
        allowMethods: apigw.Cors.ALL_METHODS,
        allowHeaders: ['Authorization', 'Content-Type', 'X-Correlation-ID'],
        maxAge: cdk.Duration.hours(1),
      },
    });

    // Helper: add a {proxy+} resource under a path prefix with ANY → NLB integration
    const addProxyResource = (
      parent: apigw.IResource,
      pathPart: string,
      port: number,
    ) => {
      const resource = parent.addResource(pathPart);
      const proxy = resource.addProxy({
        defaultIntegration: nlbProxyIntegration(port),
        anyMethod: true,
      });
      return proxy;
    };

    // Staff APIs — internal stage
    const v1 = restApi.root.addResource('v1');
    addProxyResource(v1, 'dashboard',      8083); // ARS
    addProxyResource(v1, 'inventory',      8081); // IMS
    addProxyResource(v1, 'forecast',       8084); // DFS
    addProxyResource(v1, 'replenishment',  8082); // RE
    addProxyResource(v1, 'supplier',       8085); // SUP
    addProxyResource(v1, 'ingest',         8080); // SIS (also serves Firehose ingest)
    addProxyResource(v1, 'promotions',     8086); // PPS (read-only queries)

    // ── System stage — EventBridge AWS service integration ────────────────────
    // POST /system/v1/events/promotions → EventBridge PutEvents (no VPC Link, no Lambda)
    // Auth: API key (Usage Plan) — x-api-key header
    const apiGwEventBridgeRole = new iam.Role(this, 'ApiGwEventBridgeRole', {
      assumedBy: new iam.ServicePrincipal('apigateway.amazonaws.com'),
      inlinePolicies: {
        PutEvents: new iam.PolicyDocument({
          statements: [new iam.PolicyStatement({
            actions: ['events:PutEvents'],
            resources: [messaging.eventBus.eventBusArn],
          })],
        }),
      },
    });

    const systemResource = restApi.root
      .addResource('system')
      .addResource('v1')
      .addResource('events')
      .addResource('promotions');

    const eventBusArn = messaging.eventBus.eventBusArn;

    systemResource.addMethod('POST',
      new apigw.AwsIntegration({
        service: 'events',
        action: 'PutEvents',
        options: {
          credentialsRole: apiGwEventBridgeRole,
          requestTemplates: {
            'application/json': `{
  "Entries": [{
    "Source": "external.campaign-management",
    "DetailType": "PromotionActivated",
    "Detail": "$util.escapeJavaScript($input.body).replaceAll("\\'","'")",
    "EventBusName": "${eventBusArn}"
  }]
}`,
          },
          integrationResponses: [
            {
              statusCode: '200',
              responseTemplates: {
                'application/json': `#set($r=$input.path('$'))
#if($r.FailedEntryCount==0)
{"status":"accepted","eventId":"$r.Entries[0].EventId"}
#else
#set($context.responseOverride.status=500)
{"status":"error","message":"EventBridge rejected the entry"}
#end`,
              },
            },
          ],
        },
      }),
      {
        apiKeyRequired: true,
        methodResponses: [{ statusCode: '200' }],
      },
    );

    const systemApiKey = restApi.addApiKey('SystemApiKey', {
      apiKeyName: `smartretail-system-events-${srEnv}`,
      description: 'Campaign Management System — external event ingestion',
    });
    const systemUsagePlan = restApi.addUsagePlan('SystemUsagePlan', {
      name: `smartretail-system-usage-${srEnv}`,
      throttle: { rateLimit: 50, burstLimit: 100 },
      quota: { limit: 10000, period: apigw.Period.DAY },
    });
    systemUsagePlan.addApiKey(systemApiKey);
    systemUsagePlan.addApiStage({ stage: restApi.deploymentStage });

    this.apiEndpoint = restApi.url;

    // ── Firehose delivery stream ───────────────────────────────────────────────
    const firehoseRole = new iam.Role(this, 'FirehoseRole', {
      roleName: `smartretail-firehose-${srEnv}`,
      assumedBy: new iam.ServicePrincipal('firehose.amazonaws.com'),
    });
    data.eventsBucket.grantWrite(firehoseRole);

    this.firehoseStreamName = `smartretail-ingest-${srEnv}`;

    new firehose.CfnDeliveryStream(this, 'IngestStream', {
      deliveryStreamName: this.firehoseStreamName,
      deliveryStreamType: 'DirectPut',
      httpEndpointDestinationConfiguration: {
        endpointConfiguration: {
          // REST API URL: https://{apiId}.execute-api.{region}.amazonaws.com/internal/v1/ingest/events
          url: `${restApi.url}v1/ingest/events`,
          name: `smartretail-ingest-${srEnv}`,
          accessKey: data.firehoseAccessKeySecret.secretValue.toString(),
        },
        bufferingHints: { sizeInMBs: 1, intervalInSeconds: 60 },
        retryOptions: { durationInSeconds: 86400 },
        s3BackupMode: 'AllData',
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

    // ── SSM outputs ───────────────────────────────────────────────────────────
    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('api/endpoint',           restApi.url);
    put('firehose/stream-name',   this.firehoseStreamName);
    put('apigw/system-endpoint',  `${restApi.url}system/v1/events`);

    new cdk.CfnOutput(this, 'ApiEndpoint', {
      value: restApi.url,
      description: 'SmartRetail REST API endpoint (internal stage)',
    });
    new cdk.CfnOutput(this, 'SystemStageEndpoint', {
      value: `${restApi.url}system/v1/events/promotions`,
      description: 'Campaign Management endpoint (x-api-key required)',
    });
  }
}
