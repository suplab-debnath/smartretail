import * as cdk from 'aws-cdk-lib';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface IdentityStackProps extends cdk.StackProps {
  srEnv: string;
}

export class IdentityStack extends cdk.Stack {
  public readonly internalPool: cognito.UserPool;
  public readonly internalClient: cognito.UserPoolClient;
  public readonly supplierPool: cognito.UserPool;
  public readonly supplierClient: cognito.UserPoolClient;

  constructor(scope: Construct, id: string, props: IdentityStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-identity-prod');

    const { srEnv } = props;

    const poolConfig: Partial<cognito.UserPoolProps> = {
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
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    };

    // Internal pool — store managers, SC planners, executives, admins
    this.internalPool = new cognito.UserPool(this, 'InternalPool', {
      ...poolConfig,
      userPoolName: `smartretail-internal-${srEnv}`,
    });

    ['STORE_MANAGER', 'SC_PLANNER', 'EXECUTIVE', 'ADMIN'].forEach(group => {
      new cognito.CfnUserPoolGroup(this, `Group${group}`, {
        userPoolId: this.internalPool.userPoolId,
        groupName: group,
      });
    });

    this.internalClient = this.internalPool.addClient('InternalAppClient', {
      userPoolClientName: `smartretail-internal-client-${srEnv}`,
      authFlows: { userSrp: true },
      accessTokenValidity: cdk.Duration.hours(1),
      refreshTokenValidity: cdk.Duration.hours(8),
      preventUserExistenceErrors: true,
      generateSecret: false,
    });

    // Supplier pool — external supplier users
    this.supplierPool = new cognito.UserPool(this, 'SupplierPool', {
      ...poolConfig,
      userPoolName: `smartretail-supplier-${srEnv}`,
    });

    new cognito.CfnUserPoolGroup(this, 'GroupSUPPLIER_ADMIN', {
      userPoolId: this.supplierPool.userPoolId,
      groupName: 'SUPPLIER_ADMIN',
    });

    this.supplierClient = this.supplierPool.addClient('SupplierAppClient', {
      userPoolClientName: `smartretail-supplier-client-${srEnv}`,
      authFlows: { userSrp: true },
      accessTokenValidity: cdk.Duration.hours(1),
      refreshTokenValidity: cdk.Duration.hours(8),
      preventUserExistenceErrors: true,
      generateSecret: false,
    });

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/cognito/${name}`,
        stringValue: value,
      });

    put('internal-pool-id',   this.internalPool.userPoolId);
    put('internal-client-id', this.internalClient.userPoolClientId);
    put('supplier-pool-id',   this.supplierPool.userPoolId);
    put('supplier-client-id', this.supplierClient.userPoolClientId);

    new cdk.CfnOutput(this, 'InternalUserPoolId', {
      value: this.internalPool.userPoolId,
      description: 'Internal Cognito User Pool ID',
    });

    new cdk.CfnOutput(this, 'SupplierUserPoolId', {
      value: this.supplierPool.userPoolId,
      description: 'Supplier Cognito User Pool ID',
    });
  }
}
