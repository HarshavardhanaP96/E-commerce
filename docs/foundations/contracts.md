# Data Model & Interface Contracts

## Data model (MongoDB, database `Ecommerce_2024`)
The collection names and field names match the old Mongoose models, so existing data loads unchanged.

| Collection | Shape |
|---|---|
| `users` | `_id: string` (legacy: string ids like Firebase uids or ObjectId hex), `name`, `email` (unique), `password` (bcrypt, never serialised), `photo`, `role: "admin"\|"user"`, `gender: "male"\|"female"`, `dob: Date`, `createdAt`, `updatedAt` |
| `products` | `_id: ObjectId`, `name`, `photo` (relative path `uploads/yyyy/mm/dd/<uuid>.<ext>` or absolute URL), `price: number`, `stock: int ≥ 0`, `category` (lower-case, trimmed), `createdAt`, `updatedAt` |
| `orders` | `_id`, `shippingInfo{address, city, state, country, pinCode}`, `user: string` (user id), `subtotal`, `tax`, `shippingCharges`, `discount`, `total`, `status: Processing\|Shipped\|Delivered`, `orderItems[{name, photo, price, quantity, productId}]`, `payment{provider, orderId, paymentId}` (new, optional), `termsVersion` (new, optional: the Terms version in force when the order was placed), `createdAt`, `updatedAt` |
| `coupons` | `_id`, `coupon` (unique code), `amount` (flat ₹ discount) |
| `payment_intents` (new) | `_id`, `userId`, `providerOrderId` (unique), `items[{productId, name, photo, price, quantity}]`, `coupon`, `subtotal`, `tax`, `shippingCharges`, `discount`, `total`, `status: CREATED\|CONSUMED`, `createdAt` (TTL index: 24 h) |
| `consents` (new, append-only) | `_id`, `userId`, `purpose: terms\|privacy\|marketing_email`, `granted`, `policyVersion`, `source: register\|reprompt\|settings`, `ipHash` (salted SHA-256), `userAgent`, `createdAt`. Never updated or deleted: a withdrawal is a new row. A user's state is the newest row per purpose |
| `password_reset_tokens` (new) | `_id`, `userId`, `tokenHash` (SHA-256), `expiresAt` (TTL index), `used` |

Pricing rule: `subtotal = Σ price×qty`, `shipping = subtotal > 1000 ? 0 : 200`, `tax = round(subtotal × 0.18)`,
`discount = min(coupon.amount, subtotal)`, `total = subtotal + tax + shipping − discount`.

## Interfaces (REST, base `/api/v1`, JSON unless noted)
Auth: `Authorization: Bearer <jwt>`. 🔓 means public, 👤 means any logged-in user, 🛡 means admin.

### User — `/user`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `POST /register` | 🔓 | multipart: name, email, password (≥8), gender?, dob (yyyy-mm-dd), photo?, `acceptTerms=true` (required), `marketingOptIn?` | 201 `{success, message, token, user}`. 400 if under 18 or `acceptTerms` isn't true. Records terms, privacy and marketing consents |
| `POST /login` | 🔓 (rate-limited) | `{email, password}` | `{success, message, token, user}` |
| `POST /forgot-password` | 🔓 (rate-limited) | `{email}` | always 200 `{success, message}` (no account enumeration) |
| `POST /reset-password` | 🔓 | `{token, password}` | `{success, message}` |
| `GET /me` | 👤 | — | `{success, user}` |
| `DELETE /me` | 👤 | `{password}` | `{success, message}`. 403 wrong password, 400 for admins, 409 while any order isn't delivered. Deletes the photo and the user, then strips street address and PIN code from their orders |
| `GET /all` | 🛡 | `?role=&gender=` | `{success, users}` |
| `GET /{id}` | 🛡 | — | `{success, user}` |
| `PUT /{id}` | 🛡 | `{role}` | `{success, message}` |
| `DELETE /{id}` | 🛡 | — | `{success, message}` (400 if deleting yourself, 409 while the user has undelivered orders). Same erasure as `DELETE /me` |

`user = {_id, name, email, photo, role, gender?, dob, createdAt}` (`gender` is optional since the consent work)

### Product — `/product`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /latest` | 🔓 | — | `{success, products}` (5 newest public) |
| `GET /categories` | 🔓 | — | `{success, categories}` (distinct category strings of public products) |
| `GET /all` | 🔓 | `?search=&price=&category=&sort=asc\|dsc&page=` | `{success, products, totalPage}` |
| `GET /{id}` | 🔓 | — | `{success, product}` (any status, so admin edit and direct links work) |
| `GET /admin-products` | 🛡 | — | `{success, products}` (every status) |
| `POST /new` | 🛡 | multipart: name, price, stock, category, photo, plus the optional catalogue fields below | 201 `{success, message, product}`. 409 on duplicate `sku`/`slug`, 400 on invalid fields |
| `PUT /{id}` | 🛡 | multipart: any of name, price, stock, category, photo and the optional catalogue fields. Omitted or blank means keep the current value | `{success, message, product}` |
| `DELETE /{id}` | 🛡 | — | `{success, message}` |

`product = {_id, name, photo, price, stock, category, createdAt, slug, sku, description, brand, mrp, taxRate, hsn, status, tags, images, categoryId}`.
The new fields are optional (null or empty for older products; `status` defaults to `ACTIVE`).

Optional catalogue fields on `POST /new` and `PUT /{id}`:
`slug` (≤80, generated from the name if absent, made unique with `-2`, `-3`…; an explicit duplicate is 409),
`sku` (≤60, unique, blank means none), `description` (≤5000), `brand` (≤60), `mrp` (0–10,000,000 and not below `price`),
`taxRate` (0–100), `hsn` (4–8 digits), `status` (`DRAFT|ACTIVE|ARCHIVED`, case-insensitive),
`tags` (comma separated, lower-cased and de-duplicated, ≤20 of ≤30 characters), `categoryId` (must exist),
`images` (repeatable file part, ≤5; sending any replaces the whole gallery).
`DRAFT` and `ARCHIVED` products are left out of `/latest`, `/all`, `/categories` and cannot be bought (checkout treats them as missing).

### Category — `/category`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /` | 🔓 | — | `{success, categories}` |
| `POST /new` | 🛡 | multipart: name, parentId?, image? | 201 `{success, message, category}`. 400 if `parentId` doesn't exist |
| `PUT /{id}` | 🛡 | multipart: any of name, parentId, image | `{success, message, category}`. 400 if the new parent would create a cycle (including itself) |
| `DELETE /{id}` | 🛡 | — | `{success, message}`. 409 if it has sub-categories or products |

`category = {_id, name, slug, parentId, image, createdAt}`. The slug is generated from the name when created and never changes.

### Order — `/order`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `POST /checkout` | 👤 | `{items:[{productId, quantity}], coupon?}` | 201 `{success, checkout:{providerOrderId, provider, key, amount (paise), currency, subtotal, tax, shippingCharges, discount, total}}`. 403 if the user hasn't accepted the current Terms (checked before any money moves) |
| `POST /new` | 👤 | `{providerOrderId, paymentId, signature, shippingInfo}` | 201 `{success, message, orderId}` |
| `GET /my` | 👤 | — | `{success, orders}` (own orders only) |
| `GET /all` | 🛡 | `?status=` | `{success, orders}` (`user` is `{_id, name}`) |
| `GET /{id}` | 👤 owner or 🛡 | — | `{success, order}` |
| `PUT /{id}` | 🛡 | — | `{success, message}` (advances status) |
| `DELETE /{id}` | 🛡 | — | `{success, message}` |

### Payment / coupons — `/payment`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /discount` | 🔓 | `?coupon=CODE` | `{success, discount}` / 400 |
| `GET /coupon/all` | 🛡 | — | `{success, coupons}` |
| `POST /coupon/new` | 🛡 | `{coupon, amount}` | 201 `{success, message}` |
| `DELETE /coupon/{id}` | 🛡 | — | `{success, message}` |

Removed: `POST /payment/create-order` and `POST /payment/verify`. They're replaced by `/order/checkout` and `/order/new` (ADR-009).

### Legal & consent
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /legal/versions` | 🔓 | — | `{success, versions:{terms, privacy}}` |
| `GET /consent/me` | 👤 | — | `{success, consents:{<purpose>:{granted, version, at}}, pending:[purpose], history:[{purpose, granted, version, source, at}]}`. `pending` lists the required policies not yet accepted at the current version; the SPA blocks with a prompt while it's non-empty |
| `POST /consent` | 👤 | `{purpose, granted, source: reprompt\|settings}` | same as `GET /consent/me`. 400 for withdrawing terms or privacy (delete the account instead) |

### Dashboard — `/dashboard` (🛡, all accept `?productId=&category=&userId=`)
- `GET /stats` → `{success, stats:{count{revenue,product,user,order}, changePercent{...same}, chart{order[6], revenue[6]}, categoryCount:[{category, percent}], genderRatio{male,female}, latestTransaction:[{_id, discount, amount, quantity, status}]}}`
- `GET /pie` → `{success, charts:{orderFulfillment{processing,shipped,delivered}, productCategories:[{category, percent}], stockAvailability{inStock,outOfStock}, revenueDistribution{totalTax,totalShippingCharges,totalDiscount,totalIncome}, ageGroup{teen,adult,old}, userCategory{admin,customer}}}`
- `GET /bar` → `{success, charts:{products[6], users[6], orders[12]}}`
- `GET /line` → `{success, charts:{products[12], users[12], discount[12], revenue[12]}}`

Month arrays run oldest to newest and end with the current month. Buckets account for the year.

### Ops
- `GET /actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`, `/actuator/info`
- `GET /api-docs` (springdoc OpenAPI JSON), `/swagger-ui.html`. Both are disabled in prod (`SPRINGDOC_ENABLED=false`).
- `GET /uploads/**`: static images, served with `X-Content-Type-Options: nosniff`.

## Error handling conventions
Every error is `{"success": false, "message": "<human readable>"}` with the right status:
400 validation or a bad id, 401 not authenticated or a bad token, 403 not an admin or not the owner,
404 not found, 409 conflict (duplicate email or coupon, out of stock), 413 upload too large,
429 rate limited, 502 payment provider failure, 500 unexpected. A 500 never leaks a stack trace or internal
detail; it's logged with the traceId. Validation messages name the field.
