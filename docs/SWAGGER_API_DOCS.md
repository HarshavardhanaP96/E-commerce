# API documentation (OpenAPI / Swagger UI)

The backend serves interactive API docs generated from the controllers by springdoc-openapi. There is nothing to
write by hand: add or change a controller and the docs follow. The human-written contract, which is the source of
truth for behaviour and error cases, is [`foundations/contracts.md`](foundations/contracts.md).

## Where

| | URL | Notes |
|---|---|---|
| Swagger UI | `http://localhost:8080/swagger-ui.html` | local and test |
| OpenAPI JSON | `http://localhost:8080/api-docs` | feed it to a client generator or Postman |

**Off in production.** The `prod` profile sets `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` from
`SPRINGDOC_ENABLED`, which defaults to `false`. Set it to `true` in a prod config only for a short, deliberate
look. The two paths are open to anyone (no login) when enabled, so don't leave it on.

## Calling protected endpoints from the UI

1. Call `POST /api/v1/user/login` from Swagger UI (body `{"email": "...", "password": "..."}`) and copy `token`
   from the response.
2. Click **Authorize**, paste the token (no `Bearer ` prefix), and confirm. The scheme is `bearerAuth` (HTTP bearer, JWT),
   configured in `shared/config/OpenApiConfig.java` and applied to every operation.
3. Admin endpoints also need the admin agreement accepted: `POST /api/v1/consent` with
   `{"purpose": "admin_aup", "granted": true, "source": "settings"}`. Until then they answer 403.

Locally the bootstrap admin comes from `ADMIN_EMAIL` / `ADMIN_PASSWORD` in `.env`.

## Conventions the docs rely on

- Every response is `{ "success": true, ... }` or `{ "success": false, "message": "..." }`.
- Ids are `_id` strings. Money is rupees as a number; Razorpay amounts are paise.
- Endpoints that take files are `multipart/form-data` (`/user/register`, `/product/new`, `/product/{id}`,
  `/category/new`, `/category/{id}`).
- Suspended or deleted accounts get 403 / 401 on every call, even with a token issued earlier.

## Improving the generated docs

springdoc reads standard annotations. To describe an operation, add `@Operation(summary = "...")` and
`@ApiResponse` from `io.swagger.v3.oas.annotations` on the controller method; the request and response records
are picked up automatically, including Bean Validation limits (`@Size`, `@Min`, …). Keep the top-level title
and description in `OpenApiConfig`. Don't duplicate the contract in annotations; link to it.
