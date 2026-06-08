# LLD §6.8 — API Gateway Configuration & Routing Specification

> **Why this section exists.** The platform API Gateway serves four structurally different
> integration types routed to different backends with different auth mechanisms. This is not
> a standard single-backend proxy pattern. Previous documentation described it as "VPC Link
> to ECS on port 8080" which is correct only for three of the four route groups, and omits
> the EventBridge service integration entirely. This section is the authoritative specification.

---

## 6.8.1 API Gateway Type and Constraints

| Property         | Value                          | Rationale                                                                                                                               |
| ---------------- | ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| API type         | REST API                       | Required for VPC Link (NLB target) and AWS service integrations. HTTP API supports neither.                                             |
| Deployment model | Regional                       | Paired with CloudFront for MFE assets. API Gateway itself is regional; CloudFront adds edge caching for GET responses where applicable. |
| Stage count      | 4                              | `internal`, `supplier`, `ingest`, `system` — each with independent throttle, auth, and WAF override settings.                           |
| Custom domains   | 2                              | `api.smartretail.com` (internal + ingest + system stages) · `supplier-api.smartretail.com` (supplier stage)                             |
| TLS              | ACM public cert — both domains | DNS validation via Route 53. Auto-renewal. CloudWatch alarm: `CertificateDaysToExpiry < 30` → L1 alert.                                 |
| WAFv2            | One web ACL per custom domain  | OWASP Core Rule Set 3.2, rate-based rules, SQLi/XSS managed rules. Separate rate limit per stage (see §6.8.6).                          |
| VPC Link         | 1 shared VPC Link              | Backed by NLB in private app subnets. All `HTTP_PROXY` integrations share this link.                                                    |
| No ALB           | Confirmed                      | VPC Link to NLB is the only ingress path into ECS tasks. No Application Load Balancer is provisioned.                                   |

---

## 6.8.2 Route Group Overview

API Gateway is a **per-resource router**. Each resource (path + HTTP method) has its own
independently configured integration. Four distinct integration types are used across
the platform. They share the same API Gateway but are entirely independent at the
routing, authentication, and backend level.

```
api.smartretail.com / supplier-api.smartretail.com
│
├── /v1/dashboard/**          → HTTP_PROXY → VPC Link → ARS ECS :8080    [Cognito JWT Internal]
├── /v1/inventory/**          → HTTP_PROXY → VPC Link → IMS ECS :8080    [Cognito JWT Internal]
├── /v1/forecast/**           → HTTP_PROXY → VPC Link → DFS ECS :8080    [Cognito JWT Internal]
├── /v1/replenishment/**      → HTTP_PROXY → VPC Link → RE  ECS :8080    [Cognito JWT Internal]
│
├── /v1/supplier/**           → HTTP_PROXY → VPC Link → SUP ECS :8080    [Cognito JWT Supplier]
│   (supplier-api domain)
│
├── /ingest/v1/ingest/events  → HTTP_PROXY → VPC Link → SIS ECS :8080    [Firehose access key]
│   (ingest stage)
│
└── /system/v1/events/**      → AWS service integration → EventBridge     [API key — Usage Plan]
    (system stage)            PutEvents — no VPC Link, no Lambda
```

---

## 6.8.3 Route Group Specifications

### 6.8.3.1 Staff APIs — `internal` stage

| Property              | Value                                                                                                                                                                                         |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Stage                 | `internal`                                                                                                                                                                                    |
| Domain                | `api.smartretail.com`                                                                                                                                                                         |
| Path prefixes         | `/v1/dashboard/**` · `/v1/inventory/**` · `/v1/forecast/**` · `/v1/replenishment/**`                                                                                                          |
| Integration type      | `HTTP_PROXY`                                                                                                                                                                                  |
| Connection            | VPC Link → NLB → ECS target groups                                                                                                                                                            |
| Backend port          | `:8080` per service                                                                                                                                                                           |
| Authoriser            | Cognito JWT — Internal User Pool (`smartretail-internal-{env}`)                                                                                                                               |
| Token source          | `Authorization` header — `Bearer {jwt}`                                                                                                                                                       |
| Group claims enforced | Yes — JWT group claim (`STORE_MANAGER`, `SC_PLANNER`, `SC_PLANNER_ADMIN`, `EXECUTIVE`) validated by ECS service at the use-case layer. API Gateway validates token signature and expiry only. |
| Health endpoint       | `GET /actuator/health` — no authoriser, bypass rule in API Gateway resource policy                                                                                                            |

**CDK integration (per resource):**
```typescript
const vpcLinkIntegration = (dnsName: string) =>
  new apigw.Integration({
    type: apigw.IntegrationType.HTTP_PROXY,
    integrationHttpMethod: 'ANY',
    uri: `http://${dnsName}:8080/{proxy}`,
    options: {
      connectionType: apigw.ConnectionType.VPC_LINK,
      vpcLink: sharedVpcLink,
      requestParameters: {
        'integration.request.path.proxy': 'method.request.path.proxy',
      },
    },
  });

// Applied per service resource
arsDashboardResource.addProxy({ defaultIntegration: vpcLinkIntegration(arsServiceDns), anyMethod: true });
imsResource.addProxy({ defaultIntegration: vpcLinkIntegration(imsServiceDns), anyMethod: true });
```

---

### 6.8.3.2 Supplier APIs — `supplier` stage

| Property           | Value                                                                                                                                                              |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Stage              | `supplier`                                                                                                                                                         |
| Domain             | `supplier-api.smartretail.com`                                                                                                                                     |
| Path prefix        | `/v1/supplier/**`                                                                                                                                                  |
| Integration type   | `HTTP_PROXY`                                                                                                                                                       |
| Connection         | VPC Link → NLB → SUP ECS target group                                                                                                                              |
| Authoriser         | Cognito JWT — Supplier User Pool (`smartretail-supplier-{env}`)                                                                                                    |
| Custom claim       | `custom:supplierId` (UUID) — extracted by SUP service to scope all data access                                                                                     |
| Supplier isolation | Enforced at SUP service layer. `JWT.supplierId` must match `supplierPO.supplierId` on every mutation. API Gateway does not enforce this — it validates token only. |
| MFA                | TOTP required on Supplier Cognito Pool — enforced at authentication, not at API Gateway.                                                                           |

---

### 6.8.3.3 Firehose Ingest — `ingest` stage

| Property         | Value                                                                                                                                                                                                                                                                                    |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Stage            | `ingest`                                                                                                                                                                                                                                                                                 |
| Domain           | `api.smartretail.com`                                                                                                                                                                                                                                                                    |
| Path             | `POST /ingest/v1/ingest/events`                                                                                                                                                                                                                                                          |
| Integration type | `HTTP_PROXY`                                                                                                                                                                                                                                                                             |
| Connection       | VPC Link → NLB → SIS ECS target group                                                                                                                                                                                                                                                    |
| Caller           | Amazon Data Firehose HTTP endpoint destination (not a human user, not an MFE)                                                                                                                                                                                                            |
| Auth mechanism   | Static access key — Firehose sends `X-Amz-Firehose-Access-Key` header on every request                                                                                                                                                                                                   |
| Key storage      | AWS Secrets Manager: `/smartretail/{env}/firehose/ingest-access-key`                                                                                                                                                                                                                     |
| Key validation   | API Gateway validates the header via a Lambda authoriser or usage plan API key. SIS also validates the key on receipt as a second check.                                                                                                                                                 |
| Auth note        | Firehose HTTP endpoint destination does not support IAM SigV4. The static access key is the standard mechanism for this integration. This is a known trade-off — see ADR-003.                                                                                                            |
| Retry behaviour  | On non-2xx response, Firehose retries for up to 24 hours, then writes to S3 `firehose-backup/` prefix. SIS must return 200 for all outcomes (accepted, duplicate, validation failure). Individual record failures route to SQS DLQ within SIS — they do not cause a non-2xx to Firehose. |

**Request headers Firehose sends:**
```
X-Amz-Firehose-Request-Id: <UUID — unique per delivery attempt>
X-Amz-Firehose-Access-Key: <secret from Secrets Manager>
Content-Type: application/json
```

**Expected response (200 OK):**
```json
{ "requestId": "<echo of X-Amz-Firehose-Request-Id>", "timestamp": <epoch millis> }
```

---

### 6.8.3.4 External Event Ingestion — `system` stage

| Property         | Value                                                                         |
| ---------------- | ----------------------------------------------------------------------------- |
| Stage            | `system`                                                                      |
| Domain           | `api.smartretail.com`                                                         |
| Path             | `POST /system/v1/events/promotions`                                           |
| Integration type | `AWS` service integration                                                     |
| Backend          | EventBridge `PutEvents` — **no Lambda, no VPC Link, no ECS**                  |
| Auth mechanism   | API key — `x-api-key` header, API Gateway Usage Plan                          |
| API key storage  | Secrets Manager — shared securely with Campaign Management System out of band |
| Caller           | Campaign Management System (external enterprise system — no AWS credentials)  |

**Why AWS service integration, not VPC Link:**
External systems without AWS credentials cannot call AWS APIs directly. API Gateway
acts as the authenticated bridge. The `AWS` integration type allows API Gateway to
assume an IAM execution role and call `events:PutEvents` on behalf of the caller.
No Lambda or ECS service is needed on this path — the mapping template performs
the request transformation inline.

**API Gateway execution role (least privilege):**
```typescript
const apiGwEventBridgeRole = new iam.Role(this, 'ApiGwEventBridgeRole', {
  assumedBy: new iam.ServicePrincipal('apigateway.amazonaws.com'),
  inlinePolicies: {
    PutEvents: new iam.PolicyDocument({
      statements: [new iam.PolicyStatement({
        actions: ['events:PutEvents'],
        resources: [eventBus.eventBusArn],  // scoped to this bus only
      })],
    }),
  },
});
```

**Request mapping template (Velocity — applied at API Gateway):**
```velocity
#set($body = $input.path('$'))
{
  "Entries": [{
    "Source": "external.campaign-management",
    "DetailType": "PromotionActivated",
    "Detail": "$util.escapeJavaScript($input.body).replaceAll("\'", "'")",
    "EventBusName": "${EventBusArn}"
  }]
}
```

**Response mapping (success → 200, EventBridge error → 500):**
```velocity
#set($result = $input.path('$'))
#if($result.FailedEntryCount == 0)
  #set($context.responseOverride.status = 200)
  {"status": "accepted", "eventId": "$result.Entries[0].EventId"}
#else
  #set($context.responseOverride.status = 500)
  {"status": "error", "message": "EventBridge rejected the entry"}
#end
```

**Downstream event flow after API Gateway:**
```
Campaign Management System
  → POST /system/v1/events/promotions  (x-api-key header)
  → API Gateway (AWS service integration, mapping template)
  → EventBridge PutEvents (Source: external.campaign-management, DetailType: PromotionActivated)
  → EventBridge rule: smartretail-promotion-to-pps-{env}
  → SQS: smartretail-pps-inbound-{env}
  → PPS ECS service
```

---

## 6.8.4 VPC Link and NLB Configuration

One VPC Link backs all three `HTTP_PROXY` route groups (staff, supplier, ingest).

| Property             | Value                                                 |
| -------------------- | ----------------------------------------------------- |
| VPC Link type        | REST API VPC Link (backed by NLB — not ALB)           |
| NLB scheme           | Internal                                              |
| NLB subnets          | Private app subnets (all 3 AZs)                       |
| Listener             | TCP `:8080`                                           |
| Target groups        | One per ECS service — path-based routing at NLB level |
| Health check         | `GET /actuator/health` → HTTP 200                     |
| Deregistration delay | 30s (prototype) · 60s (production)                    |

**NLB target group routing (path prefix → target group):**

| Path prefix                | NLB target group | ECS service |
| -------------------------- | ---------------- | ----------- |
| `/v1/dashboard/**`         | `tg-ars-{env}`   | ARS         |
| `/v1/inventory/**`         | `tg-ims-{env}`   | IMS         |
| `/v1/forecast/**`          | `tg-dfs-{env}`   | DFS         |
| `/v1/replenishment/**`     | `tg-re-{env}`    | RE          |
| `/v1/supplier/**`          | `tg-sup-{env}`   | SUP         |
| `/ingest/v1/ingest/events` | `tg-sis-{env}`   | SIS         |

**CDK VPC Link definition:**
```typescript
const nlb = new elbv2.NetworkLoadBalancer(this, 'ApiNlb', {
  vpc,
  internetFacing: false,
  vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
});

const vpcLink = new apigw.VpcLink(this, 'VpcLink', {
  targets: [nlb],
  vpcLinkName: `smartretail-vpc-link-${env}`,
});
```

---

## 6.8.5 Authoriser Definitions

### Cognito JWT Authoriser — Internal Pool

```typescript
const internalAuthorizer = new apigw.CognitoUserPoolsAuthorizer(this, 'InternalAuthorizer', {
  cognitoUserPools: [internalUserPool],
  authorizerName: `smartretail-internal-auth-${env}`,
  identitySource: 'method.request.header.Authorization',
  resultsCacheTtl: cdk.Duration.minutes(5),
});
```

Applied to all resources under `internal` stage except `/actuator/health`.

### Cognito JWT Authoriser — Supplier Pool

```typescript
const supplierAuthorizer = new apigw.CognitoUserPoolsAuthorizer(this, 'SupplierAuthorizer', {
  cognitoUserPools: [supplierUserPool],
  authorizerName: `smartretail-supplier-auth-${env}`,
  identitySource: 'method.request.header.Authorization',
  resultsCacheTtl: cdk.Duration.minutes(5),
});
```

Applied to all resources under `supplier` stage.

### Firehose Access Key — Lambda Authoriser

```typescript
// Lambda authoriser validates X-Amz-Firehose-Access-Key header
// against cached value from Secrets Manager
const firehoseAuthorizer = new apigw.RequestAuthorizer(this, 'FirehoseAuthorizer', {
  handler: firehoseAuthLambda,  // small Lambda — reads SM, compares header
  identitySources: [apigw.IdentitySource.header('X-Amz-Firehose-Access-Key')],
  authorizerName: `smartretail-firehose-auth-${env}`,
  resultsCacheTtl: cdk.Duration.minutes(10),
});
```

Applied only to `POST /ingest/v1/ingest/events`.

### API Key — Campaign Management (Usage Plan)

```typescript
const systemApiKey = new apigw.ApiKey(this, 'SystemApiKey', {
  apiKeyName: `smartretail-system-events-${env}`,
  description: 'Campaign Management System — external event ingestion',
});

const systemUsagePlan = new apigw.UsagePlan(this, 'SystemUsagePlan', {
  name: `smartretail-system-usage-${env}`,
  throttle: { rateLimit: 50, burstLimit: 100 },
  quota: { limit: 10000, period: apigw.Period.DAY },
});

systemUsagePlan.addApiKey(systemApiKey);
systemUsagePlan.addApiStage({ stage: systemStage });
```

---

## 6.8.6 Stage Configuration and Throttling

| Stage      | Default throttle | Burst | Quota      | WAF override                                    |
| ---------- | ---------------- | ----- | ---------- | ----------------------------------------------- |
| `internal` | 500 req/s        | 1000  | None       | Rate rule: 1000 req/5 min per IP                |
| `supplier` | 100 req/s        | 200   | None       | Rate rule: 200 req/5 min per IP                 |
| `ingest`   | 1000 req/s       | 2000  | None       | Rate rule: Firehose source IPs only (allowlist) |
| `system`   | 50 req/s         | 100   | 10,000/day | Rate rule: 100 req/5 min per IP                 |

Stage-level throttle applies to the entire stage. Method-level overrides can be added
per route if specific endpoints need tighter limits (e.g. `POST /v1/replenishment/orders`
vs `GET /v1/dashboard`).

---

## 6.8.7 CORS Configuration

CORS headers are required on all routes accessed by the React MFEs (staff and supplier stages).
The ingest and system stages do not require CORS — their callers are server-side.

| Stage      | Allowed origins             | Allowed methods           | Allowed headers                                 |
| ---------- | --------------------------- | ------------------------- | ----------------------------------------------- |
| `internal` | `https://*.smartretail.com` | `GET, POST, PUT, OPTIONS` | `Authorization, Content-Type, X-Correlation-ID` |
| `supplier` | `https://*.smartretail.com` | `GET, POST, OPTIONS`      | `Authorization, Content-Type, X-Correlation-ID` |
| `ingest`   | None (no browser callers)   | `POST`                    | N/A                                             |
| `system`   | None (no browser callers)   | `POST`                    | `x-api-key, Content-Type`                       |

```typescript
// Applied at resource level on each ECS-backed proxy resource
resource.addCorsPreflight({
  allowOrigins: ['https://*.smartretail.com'],
  allowMethods: apigw.Cors.ALL_METHODS,
  allowHeaders: ['Authorization', 'Content-Type', 'X-Correlation-ID'],
  maxAge: cdk.Duration.hours(1),
});
```

---

## 6.8.8 Request Validation

API Gateway request validation is a first-line defence before requests reach ECS services.

| Stage      | Validation applied                                                                                                                                                                                                     |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `internal` | Query string parameters validated for known GET endpoints. Request body schema validated for all POST/PUT mutations (JSON Schema linked to API Gateway models).                                                        |
| `supplier` | Request body schema validated for `POST /v1/supplier/orders/{poId}/acknowledge` and `POST /v1/supplier/orders/{poId}/ship`.                                                                                            |
| `ingest`   | Firehose envelope structure validated (required fields: `requestId`, `timestamp`, `records`). Individual record validation done at SIS service layer — not at API Gateway to allow partial batch acceptance.           |
| `system`   | Request body validated against `PromotionActivatedEvent` schema (required: `promotionId`, `skuIds`, `discountPct`, `validFrom`, `validTo`). API Gateway rejects malformed bodies before the mapping template executes. |

---

## 6.8.9 Parameter Store Entries (CDK outputs)

| Parameter                                    | Value written by CDK                   | Consumer                            |
| -------------------------------------------- | -------------------------------------- | ----------------------------------- |
| `/smartretail/{env}/apigw/internal-endpoint` | `https://api.smartretail.com`          | MFE config, CDK references          |
| `/smartretail/{env}/apigw/supplier-endpoint` | `https://supplier-api.smartretail.com` | Supplier MFE config                 |
| `/smartretail/{env}/apigw/ingest-endpoint`   | `https://api.smartretail.com/ingest`   | Firehose delivery stream config     |
| `/smartretail/{env}/apigw/system-endpoint`   | `https://api.smartretail.com/system`   | Campaign Management integration doc |
| `/smartretail/{env}/apigw/vpc-link-id`       | VPC Link resource ID                   | CDK cross-stack reference           |

---

## 6.8.10 Monitoring and Alerting

CloudWatch metrics are emitted per stage and per route by API Gateway natively.

| Metric                             | Alarm threshold      | Alert level | Action                                   |
| ---------------------------------- | -------------------- | ----------- | ---------------------------------------- |
| `5XXError` — ingest stage          | > 10 errors in 5 min | L1          | Page on-call — Firehose delivery failure |
| `5XXError` — internal stage        | > 20 errors in 5 min | L2          | Team notification                        |
| `Latency` p99 — internal stage     | > 3000 ms            | L2          | Team notification                        |
| `Latency` p99 — ingest stage       | > 5000 ms            | L2          | Investigate SIS batch processing         |
| `4XXError` — supplier stage        | > 50 errors in 5 min | L2          | Possible Supplier Cognito issue          |
| `Count` — system stage             | 0 requests in 24 h   | L3          | Campaign Management connectivity check   |
| `CacheHitCount` / `CacheMissCount` | Informational only   | —           | Used for throttle tuning                 |

All alarms route to SNS → Alerting Platform. Log groups: `/aws/apigateway/smartretail-{stage}-{env}`.

---

## 6.8.11 Diagram Reference

**LLD-DGM-020** (Network & Port Topology, §6.5) shows the VPC Link and NLB in the subnet
layout but does not detail integration types per route.

A new diagram **LLD-DGM-APIGW-01** should be added to the diagram prompts file covering:
- The four route groups as vertical swim-lanes
- Integration type per lane (HTTP_PROXY vs AWS service)
- Auth mechanism badges per lane
- VPC Link to NLB to ECS target groups
- EventBridge service integration path (no VPC Link)

This diagram is not yet produced — add to backlog for next diagram revision cycle.

---

*End of LLD §6.8 — API Gateway Configuration & Routing Specification*
*Cross-references: ARCHITECTURE.md §API Gateway · LLD §6.5 Network Topology · CDK_SPEC.md §MessagingStack · EVENT_ASYNC_SPEC.md §3 Firehose Ingestion Channel · ADR-003*
