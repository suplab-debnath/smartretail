import * as cdk from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigatewayv2';
import * as apiIntegrations from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as apiAuthorizers from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
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

    // ── VPC Link ──────────────────────────────────────────────────────────────
    const vpcLink = new apigateway.VpcLink(this, 'VpcLink', {
      vpc: network.vpc,
      securityGroups: [network.sgApiGatewayLink],
      subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
    });

    // ── HTTP API ──────────────────────────────────────────────────────────────
    const httpApi = new apigateway.HttpApi(this, 'SmartRetailApi', {
      apiName: `smartretail-api-${srEnv}`,
      description: 'SmartRetail backend API',
      corsPreflight: {
        allowHeaders: ['Content-Type', 'Authorization'],
        allowMethods: [apigateway.CorsHttpMethod.GET, apigateway.CorsHttpMethod.POST,
        apigateway.CorsHttpMethod.PUT, apigateway.CorsHttpMethod.PATCH],
        allowOrigins: ['*'],
        maxAge: cdk.Duration.seconds(300),
      },
    });

    // ── JWT Authorizer (Cognito) ───────────────────────────────────────────────
    const issuerUrl = `https://cognito-idp.us-east-1.amazonaws.com/${identity.internalPool.userPoolId}`;
    const jwtAuthorizer = new apiAuthorizers.HttpJwtAuthorizer(
      'CognitoJwtAuthorizer', issuerUrl, {
      jwtAudience: [identity.internalClient.userPoolClientId],
    }
    );

    // ── Routes — SIS ─────────────────────────────────────────────────────────
    const sisIntegration = new apiIntegrations.HttpServiceDiscoveryIntegration(
      'SisIntegration',
      compute.sisService.cloudMapService!,
      { vpcLink }
    );
    httpApi.addRoutes({
      path: '/v1/ingest/{proxy+}',
      methods: [apigateway.HttpMethod.POST],
      integration: sisIntegration,
      authorizer: jwtAuthorizer,
    });

    // ── Routes — IMS ─────────────────────────────────────────────────────────
    const imsIntegration = new apiIntegrations.HttpServiceDiscoveryIntegration(
      'ImsIntegration',
      compute.imsService.cloudMapService!,
      { vpcLink }
    );
    httpApi.addRoutes({
      path: '/v1/inventory/{proxy+}',
      methods: [apigateway.HttpMethod.GET],
      integration: imsIntegration,
      authorizer: jwtAuthorizer,
    });

    // ── SSM Outputs ───────────────────────────────────────────────────────────
    new ssm.StringParameter(this, 'ApiEndpointParam', {
      parameterName: `/smartretail/${srEnv}/api/endpoint`,
      stringValue: httpApi.apiEndpoint,
    });

    new cdk.CfnOutput(this, 'ApiEndpoint', { value: httpApi.apiEndpoint });
  }
}
