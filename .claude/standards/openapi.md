# OpenAPI Design Standards
 
All REST APIs in this project follow contract-first design.
The OpenAPI YAML is the source of truth. Code is generated from it.
 
---
 
## File Locations
 
```
openapi/
├── sis-api.yaml ← Sales Ingestion Service API
├── ims-api.yaml ← Inventory Management Service API
├── re-api.yaml ← Replenishment Engine API
├── ars-api.yaml ← Analytics & Reporting Service API
└── components/
├── schemas.yaml ← shared schema components ($ref'd by service specs)
├── responses.yaml ← shared response components
└── parameters.yaml ← shared parameter components
```
 
---
 
## OpenAPI Spec Structure (required sections)
 
Every service spec must include these top-level sections:
 
```yaml
openapi: "3.1.0"
 
info:
title: SmartRetail {Service Name} API
version: "1.0.0"
description: |
{One paragraph describing the service's purpose and bounded context.}
contact:
name: SmartRetail Platform Team
 
servers:
- url: http://localhost:{port}
description: Local development
- url: https://{api-gateway-id}.execute-api.us-east-1.amazonaws.com/internal
description: AWS (internal stage)
 
tags:
- name: {resource}
description: {description}
 
security:
- BearerAuth: []
 
components:
securitySchemes:
BearerAuth:
type: http
scheme: bearer
bearerFormat: JWT
 
schemas:
ErrorResponse:
$ref: './components/schemas.yaml#/ErrorResponse'
 
paths:
# ... all endpoints
 
```
 
---
 
## Schema Design Rules
 
### Rule 1: Use $ref for reused schemas
 
```yaml
# CORRECT — define once, reference everywhere
components:
schemas:
PurchaseOrderStatus:
type: string
enum:
- DRAFT
- PENDING_APPROVAL
- APPROVED
- REJECTED
- EXPIRED
- DISPATCHED
- ACKNOWLEDGED
- SHIPPED
- PARTIAL_DELIVERY
- COMPLETED
- CANCELLED
 
# In paths:
workflowStatus:
$ref: '#/components/schemas/PurchaseOrderStatus'
```
 
### Rule 2: All properties must have descriptions
 
```yaml
# CORRECT
properties:
poId:
type: string
format: uuid
description: Unique identifier for the purchase order
example: "550e8400-e29b-41d4-a716-446655440000"
totalValue:
type: number
format: double
description: Total monetary value of the order (quantity × unit cost)
minimum: 0
example: 6495.00
 
# WRONG — no descriptions
properties:
poId:
type: string
totalValue:
type: number
```
 
### Rule 3: Required fields must be explicit
 
```yaml
SalesEventRequest:
type: object
required:
- transactionId
- storeId
- skuId
- dcId
- quantity
- unitPrice
- channel
- eventTimestamp
properties:
transactionId:
type: string
format: uuid
description: Client-generated unique identifier for idempotency
# ... rest of properties
```
 
### Rule 4: Use format qualifiers on all typed fields
 
```yaml
# String formats
format: uuid # UUID strings
format: date-time # ISO-8601 timestamps (maps to Instant in Java)
format: date # ISO-8601 dates (maps to LocalDate in Java)
format: email # email strings (never log raw — PII)
 
# Number formats
format: double # monetary values, rates
format: int32 # counts, quantities
format: int64 # large counts
```
 
### Rule 5: Use additionalProperties: false on request bodies
 
```yaml
SalesEventRequest:
type: object
additionalProperties: false # reject unknown fields
required: [...]
properties: {...}
```
 
---
 
## Response Standards
 
### Success responses
 
```yaml
# 200 OK — resource returned
responses:
'200':
description: Purchase order details
content:
application/json:
schema:
$ref: '#/components/schemas/PurchaseOrderResponse'
 
# 202 Accepted — async processing
'202':
description: Event accepted for asynchronous processing
content:
application/json:
schema:
$ref: '#/components/schemas/AcceptedResponse'
 
# 204 No Content — action succeeded, no body
'204':
description: Operation completed successfully
```
 
### Error responses — standardised envelope
 
Every endpoint must declare these error responses:
 
```yaml
responses:
'400':
description: Validation error
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
'401':
description: Missing or invalid authentication token
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
'403':
description: Authenticated but insufficient role
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
'404':
description: Resource not found
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
'409':
description: State conflict (duplicate, invalid transition, optimistic lock)
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
'500':
description: Unexpected internal error
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
```
 
### Shared ErrorResponse schema (in components/schemas.yaml)
 
```yaml
ErrorResponse:
type: object
required:
- errorCode
- message
- timestamp
properties:
errorCode:
type: string
description: Machine-readable error classification
enum:
- VALIDATION_ERROR
- DUPLICATE_EVENT
- INVALID_STATUS_TRANSITION
- CONCURRENT_MODIFICATION
- UNAUTHORIZED
- NOT_FOUND
- INTERNAL_ERROR
example: INVALID_STATUS_TRANSITION
message:
type: string
description: Human-readable error description
example: "PO cannot be approved from status DRAFT. Status must be PENDING_APPROVAL."
traceId:
type: string
description: AWS X-Ray trace ID for log correlation
example: "1-5759e988-bd862e3fe1be46a994272793"
timestamp:
type: string
format: date-time
description: When the error occurred
details:
type: object
description: Optional additional context (e.g. currentStatus on transition errors)
additionalProperties: true
```
 
---
 
## Pagination Standard
 
All list endpoints use cursor-based pagination:
 
```yaml
parameters:
- name: page
in: query
schema:
type: integer
minimum: 0
default: 0
- name: size
in: query
schema:
type: integer
minimum: 1
maximum: 100
default: 20
 
# Response envelope for paginated results
PaginatedResponse:
type: object
required:
- content
- page
- size
- totalElements
properties:
content:
type: array
items: {} # replaced with $ref in each spec
page:
type: integer
description: Current page (0-indexed)
size:
type: integer
description: Page size
totalElements:
type: integer
format: int64
description: Total number of matching elements
```
 
---
 
## Idempotency Header
 
Any endpoint with side effects (POST mutations) must declare this header:
 
```yaml
parameters:
- name: X-Idempotency-Key
in: header
required: true
schema:
type: string
format: uuid
description: |
Client-generated UUID for idempotency.
Repeat the same key to safely retry without duplicate effects.
```
 
---
 
## Complete RE API Example (re-api.yaml skeleton)
 
```yaml
openapi: "3.1.0"
 
info:
title: SmartRetail Replenishment Engine API
version: "1.0.0"
description: |
Manages purchase order lifecycle from generation through dispatch.
Implements a DB-backed state machine — no external orchestrator.
All state transitions are atomic with optimistic locking.
 
servers:
- url: http://localhost:8082
description: Local development
- url: https://{api-id}.execute-api.us-east-1.amazonaws.com/internal
description: AWS internal stage
 
tags:
- name: purchase-orders
description: Purchase order lifecycle management
 
security:
- BearerAuth: []
 
components:
securitySchemes:
BearerAuth:
type: http
scheme: bearer
bearerFormat: JWT
 
schemas:
WorkflowStatus:
type: string
description: Current state in the replenishment approval state machine
enum:
- DRAFT
- PENDING_APPROVAL
- APPROVED
- REJECTED
- EXPIRED
- DISPATCHED
- ACKNOWLEDGED
- SHIPPED
- PARTIAL_DELIVERY
- COMPLETED
- CANCELLED
 
PurchaseOrderSummary:
type: object
required: [poId, supplierId, skuId, dcId, quantity, totalValue, workflowStatus, version, createdAt]
additionalProperties: false
properties:
poId:
type: string
format: uuid
description: Unique purchase order identifier
supplierId:
type: string
description: Supplier identifier (logical reference — not a DB FK)
skuId:
type: string
description: Stock Keeping Unit identifier
dcId:
type: string
description: Distribution Centre identifier
quantity:
type: integer
format: int32
minimum: 1
totalValue:
type: number
format: double
minimum: 0
description: Total order value (quantity × cost_per_unit)
workflowStatus:
$ref: '#/components/schemas/WorkflowStatus'
version:
type: integer
format: int32
description: Optimistic lock version — increment on every state change
approvedBy:
type: string
description: JWT sub of the approving planner (present when APPROVED)
approvedAt:
type: string
format: date-time
createdAt:
type: string
format: date-time
updatedAt:
type: string
format: date-time
 
ApproveRequest:
type: object
additionalProperties: false
properties:
notes:
type: string
maxLength: 500
description: Optional approval notes
 
RejectRequest:
type: object
required: [reason]
additionalProperties: false
properties:
reason:
type: string
minLength: 1
maxLength: 500
description: Mandatory rejection reason
 
ApproveResponse:
type: object
required: [poId, workflowStatus, approvedBy, approvedAt, version]
properties:
poId:
type: string
format: uuid
workflowStatus:
$ref: '#/components/schemas/WorkflowStatus'
approvedBy:
type: string
approvedAt:
type: string
format: date-time
version:
type: integer
 
ErrorResponse:
$ref: './components/schemas.yaml#/ErrorResponse'
 
paths:
/v1/replenishment/orders:
get:
tags: [purchase-orders]
operationId: listPurchaseOrders
summary: List purchase orders
parameters:
- name: status
in: query
schema:
$ref: '#/components/schemas/WorkflowStatus'
- name: dcId
in: query
schema:
type: string
- name: page
in: query
schema: { type: integer, minimum: 0, default: 0 }
- name: size
in: query
schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
responses:
'200':
description: Paginated list of purchase orders
content:
application/json:
schema:
type: object
properties:
orders:
type: array
items:
$ref: '#/components/schemas/PurchaseOrderSummary'
page: { type: integer }
size: { type: integer }
totalElements: { type: integer, format: int64 }
'401': { $ref: '#/components/responses/Unauthorized' }
'403': { $ref: '#/components/responses/Forbidden' }
 
/v1/replenishment/orders/{poId}/approve:
post:
tags: [purchase-orders]
operationId: approvePurchaseOrder
summary: Approve a purchase order
description: |
Transitions a PO from PENDING_APPROVAL to APPROVED.
Requires SC_PLANNER or ADMIN role.
Uses optimistic locking — include current version.
Idempotent with X-Idempotency-Key header.
parameters:
- name: poId
in: path
required: true
schema:
type: string
format: uuid
- name: X-Idempotency-Key
in: header
required: true
schema:
type: string
format: uuid
requestBody:
content:
application/json:
schema:
$ref: '#/components/schemas/ApproveRequest'
responses:
'200':
description: PO approved successfully
content:
application/json:
schema:
$ref: '#/components/schemas/ApproveResponse'
'403':
description: Insufficient role (SC_PLANNER or ADMIN required)
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
'404':
description: PO not found
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
'409':
description: |
State conflict. errorCode will be one of:
- INVALID_STATUS_TRANSITION: PO is not in PENDING_APPROVAL status
- CONCURRENT_MODIFICATION: PO was modified concurrently
content:
application/json:
schema:
$ref: '#/components/schemas/ErrorResponse'
```
 
 