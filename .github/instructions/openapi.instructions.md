---
applyTo: "**/*-api.yaml"
---

# OpenAPI Instructions -- SmartRetail

## Contract-first is non-negotiable
The YAML is the source of truth. Never suggest editing generated Java stubs
in `target/generated-sources/openapi/` or TypeScript in
`mfe/shared/api-client/src/generated/`. Change the YAML, then regenerate.

## Required file header (every service YAML)
```yaml
openapi: "3.1.0"
info:
  title: SmartRetail {Service Name} API
  version: "1.0.0"
servers:
  - url: http://localhost:{port}
    description: Local development
  - url: https://{api-id}.execute-api.us-east-1.amazonaws.com/internal
    description: AWS internal stage
security:
  - BearerAuth: []
components:
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
```

## Schema rules
- Every property must have `description` and `example`
- Use `format: uuid` / `format: date-time` / `format: double` / `format: int32` on typed fields
- Request bodies must have `additionalProperties: false`
- Required fields must be declared in an explicit `required:` array
- Use `$ref` for any schema used in more than one place

## Response rules
Every mutating endpoint (POST with side effects) must declare:
- `400` ValidationError
- `401` Unauthorized
- `403` Forbidden
- `404` Not Found (where applicable)
- `409` Conflict (state conflict, duplicate, optimistic lock)
- `500` Internal Error

All 4xx/5xx responses reference `#/components/schemas/ErrorResponse`.

## Idempotency header
Any POST endpoint with side effects must declare:
```yaml
- name: X-Idempotency-Key
  in: header
  required: true
  schema:
    type: string
    format: uuid
```

## Pagination
List endpoints use page/size query params (0-indexed page).
Response envelope includes `content[]`, `page`, `size`, `totalElements`.

## Port assignments (local dev)
SIS: 8080 | IMS: 8081 | RE: 8082 | ARS: 8083 | DFS: 8084 | SUP: 8085 | PPS: 8086
