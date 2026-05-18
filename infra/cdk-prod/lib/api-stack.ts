import * as cdk from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigatewayv2';
import * as apiIntegrations from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as apiAuthorizers from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { NetworkStack } from './network-stack';
import { ComputeStack } from './compute-stack';
import { IdentityStack } from './identity-stack';

export interface ApiStackProps extends cdk.StackProps {
  srEnv: string;
  network: NetworkStack;
  compute: ComputeStack;
  identity: IdentityStack;
}

export class ApiStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-api');

    const { srEnv, network, compute, identity } = props;

    const vpcLink = new apigateway.VpcLink(this, 'VpcLink', {
      vpc: network.vpc,
      securityGroups: [network.sgApiGatewayLink],
      subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
    });

    const httpApi = new apigateway.HttpApi(this, 'SmartRetailApi', {
      apiName: `smartretail-api-${srEnv}`,
      description: 'SmartRetail backend API',
      corsPreflight: {
        allowHeaders: ['Content-Type', 'Authorization'],
        allowMethods: [
          apigateway.CorsHttpMethod.GET,
          apigateway.CorsHttpMethod.POST,
          apigateway.CorsHttpMethod.PUT,
          apigateway.CorsHttpMethod.PATCH,
        ],
        allowOrigins: ['*'],
        maxAge: cdk.Duration.seconds(300),
      },
    });

    const issuerUrl = `https://cognito-idp.us-east-1.amazonaws.com/${identity.internalPool.userPoolId}`;
    const jwtAuthorizer = new apiAuthorizers.HttpJwtAuthorizer(
      'CognitoJwtAuthorizer', issuerUrl, {
        jwtAudience: [identity.internalClient.userPoolClientId],
      },
    );

    const addRoutes = (
      path: string,
      methods: apigateway.HttpMethod[],
      service: ecs.FargateService,
    ) => {
      const integration = new apiIntegrations.HttpServiceDiscoveryIntegration(
        `${path.replace(/\//g, '')}Integration`,
        service.cloudMapService!,
        { vpcLink },
      );
      httpApi.addRoutes({ path, methods, integration, authorizer: jwtAuthorizer });
    };

    addRoutes('/v1/ingest/{proxy+}',        [apigateway.HttpMethod.POST, apigateway.HttpMethod.GET], compute.sisService);
    addRoutes('/v1/inventory/{proxy+}',     [apigateway.HttpMethod.GET,  apigateway.HttpMethod.POST], compute.imsService);
    addRoutes('/v1/replenishment/{proxy+}', [apigateway.HttpMethod.GET,  apigateway.HttpMethod.POST], compute.reService);
    addRoutes('/v1/dashboard/{proxy+}',     [apigateway.HttpMethod.GET],                              compute.arsService);
    addRoutes('/v1/forecast/{proxy+}',      [apigateway.HttpMethod.GET,  apigateway.HttpMethod.POST], compute.dfsService);
    addRoutes('/v1/supplier/{proxy+}',      [apigateway.HttpMethod.GET,  apigateway.HttpMethod.POST], compute.supService);

    new ssm.StringParameter(this, 'ApiEndpointParam', {
      parameterName: `/smartretail/${srEnv}/api/endpoint`,
      stringValue: httpApi.apiEndpoint,
    });

    new cdk.CfnOutput(this, 'ApiEndpoint', {
      value: httpApi.apiEndpoint,
      description: 'SmartRetail API Endpoint',
    });
    new cdk.CfnOutput(this, 'ApiId', { value: httpApi.apiId });
  }
}
