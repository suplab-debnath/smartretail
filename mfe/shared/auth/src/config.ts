export interface SmartRetailConfig {
  apiGatewayEndpoint: string;
  cognitoPoolId: string;
  cognitoClientId: string;
  cognitoDomain: string;
  env: string;
}

export function getConfig(): SmartRetailConfig {
  const win = window as any;
  if (win.SMARTRETAIL_CONFIG) {
    return win.SMARTRETAIL_CONFIG;
  }
  // Local development fallback
  return {
    apiGatewayEndpoint: 'http://localhost:8083', // ARS default port, can be overridden per MFE if needed
    cognitoPoolId: '',
    cognitoClientId: '',
    cognitoDomain: '',
    env: 'local'
  };
}
