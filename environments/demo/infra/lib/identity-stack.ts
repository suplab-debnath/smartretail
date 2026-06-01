import * as cdk from 'aws-cdk-lib';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface IdentityStackProps extends cdk.StackProps {
  srEnv: string;
  mfeBaseUrl: string;  // CloudFront URL, e.g. https://xyz.cloudfront.net
}

export class IdentityStack extends cdk.Stack {
  public readonly internalPool: cognito.UserPool;
  public readonly internalClient: cognito.UserPoolClient;

  constructor(scope: Construct, id: string, props: IdentityStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-identity-demo');

    const { srEnv, mfeBaseUrl } = props;

    this.internalPool = new cognito.UserPool(this, 'InternalPool', {
      userPoolName: `smartretail-internal-${srEnv}`,
      selfSignUpEnabled: false,
      signInAliases: { email: true },
      autoVerify: { email: true },
      passwordPolicy: {
        minLength: 8,
        requireLowercase: true,
        requireUppercase: true,
        requireDigits: true,
        requireSymbols: true,
      },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    ['STORE_MANAGER', 'SC_PLANNER', 'EXECUTIVE', 'ADMIN'].forEach(group => {
      new cognito.CfnUserPoolGroup(this, `Group${group}`, {
        userPoolId: this.internalPool.userPoolId,
        groupName: group,
      });
    });

    const internalDomain = new cognito.UserPoolDomain(this, 'InternalDomain', {
      userPool: this.internalPool,
      cognitoDomain: { domainPrefix: `smartretail-${srEnv}-internal` },
    });

    this.internalClient = this.internalPool.addClient('InternalAppClient', {
      userPoolClientName: `smartretail-internal-client-${srEnv}`,
      authFlows: { userSrp: true },
      oAuth: {
        flows: { authorizationCodeGrant: true },
        scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL, cognito.OAuthScope.PROFILE],
        callbackUrls: [`${mfeBaseUrl}/sc-planner/callback`],
        logoutUrls:   [`${mfeBaseUrl}/sc-planner/logout`],
      },
      accessTokenValidity: cdk.Duration.hours(1),
      refreshTokenValidity: cdk.Duration.hours(8),
      preventUserExistenceErrors: true,
      generateSecret: false,
    });

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/\-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/cognito/${name}`,
        stringValue: value,
      });

    put('internal-pool-id',   this.internalPool.userPoolId);
    put('internal-client-id', this.internalClient.userPoolClientId);
    put('internal-domain',    `${internalDomain.domainName}.auth.${this.region}.amazoncognito.com`);

    new cdk.CfnOutput(this, 'InternalUserPoolId', {
      value: this.internalPool.userPoolId,
      description: 'Internal Cognito User Pool ID',
    });
  }
}
