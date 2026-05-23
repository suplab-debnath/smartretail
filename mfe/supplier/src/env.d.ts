/// <reference types="vite/client" />

interface SmartRetailConfig {
  apiGatewayEndpoint: string
  cognitoPoolId: string
  cognitoClientId: string
  cognitoDomain: string
  env: string
}

declare interface Window {
  SMARTRETAIL_CONFIG?: SmartRetailConfig
}
