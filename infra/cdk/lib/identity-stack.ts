import * as cdk     from 'aws-cdk-lib';
import * as cognito  from 'aws-cdk-lib/aws-cognito';
import * as ssm      from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface IdentityStackProps extends cdk.StackProps {
  srEnv: string;
}

export class IdentityStack extends cdk.Stack {
  public readonly internalPool:   cognito.UserPool;
  public readonly internalClient: cognito.UserPoolClient;
  public readonly supplierPool:   cognito.UserPool;

  constructor(scope: Construct, id: string, props: IdentityStackProps) {
    super(scope, id, props);
    const { srEnv } = props;

    // ── Internal User Pool ────────────────────────────────────────────────────
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

    this.internalClient = this.internalPool.addClient('InternalAppClient', {
      userPoolClientName: `smartretail-internal-client-${srEnv}`,
      authFlows: { userSrp: true },
      accessTokenValidity: cdk.Duration.hours(1),
      refreshTokenValidity: cdk.Duration.hours(8),
      preventUserExistenceErrors: true,
    });

    // ── Supplier User Pool ────────────────────────────────────────────────────
    this.supplierPool = new cognito.UserPool(this, 'SupplierPool', {
      userPoolName: `smartretail-supplier-${srEnv}`,
      selfSignUpEnabled: false,
      signInAliases: { email: true },
      mfa: cognito.Mfa.REQUIRED,
      mfaSecondFactor: { otp: true, sms: false },
      customAttributes: {
        supplierId: new cognito.StringAttribute({ mutable: false }),
      },
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const supplierClient = this.supplierPool.addClient('SupplierAppClient', {
      userPoolClientName: `smartretail-supplier-client-${srEnv}`,
      authFlows: { userSrp: true },
      accessTokenValidity: cdk.Duration.hours(1),
      refreshTokenValidity: cdk.Duration.hours(8),
      preventUserExistenceErrors: true,
    });

    // ── SSM Outputs ───────────────────────────────────────────────────────────
    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/cognito/${name}`,
        stringValue: value,
      });

    put('internal-pool-id',   this.internalPool.userPoolId);
    put('internal-client-id', this.internalClient.userPoolClientId);
    put('supplier-pool-id',   this.supplierPool.userPoolId);
    put('supplier-client-id', supplierClient.userPoolClientId);
  }
}
