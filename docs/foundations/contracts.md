# Data Model & Interface Contracts

## Data model (MongoDB, database `Ecommerce_2024`)
Every collection except `tenants` and `platform_admins` carries `tenantId`; uniqueness (user email, product slug/sku/variant sku,
category slug, coupon code, order number, provider order id, reset token hash, coupon redemption) is per tenant.

The collection names and field names match the old Mongoose models, so existing data loads unchanged.

| Collection | Shape |
|---|---|
| `users` | `_id: string` (legacy: string ids like Firebase uids or ObjectId hex), `name`, `email` (unique), `password` (bcrypt, never serialised), `photo`, `role: "admin"\|"user"`, `gender: "male"\|"female"`, `dob: Date`, `createdAt`, `updatedAt` |
| `products` | `_id: ObjectId`, `name`, `photo` (relative path `uploads/yyyy/mm/dd/<uuid>.<ext>` or absolute URL), `price: number`, `stock: int ≥ 0`, `category` (lower-case, trimmed), `createdAt`, `updatedAt`, `countryOfOrigin?`, `lowStockThreshold?`, `sizeChart?{columns, rows, note}`, `variants?{axes[], items[{variantId, sku, options[{name,value}], price, mrp, stock, status}]}` (absent unless the product has variants; sparse unique index on `variants.items.sku`), `rev?` (bumped by every stock change) |
| `orders` | `_id`, `shippingInfo{address, city, state, country, pinCode}`, `user: string` (user id), `subtotal`, `tax`, `shippingCharges`, `discount`, `total`, `status: Processing\|Shipped\|Delivered`, `orderItems[{name, photo, price, quantity, productId, sku?, taxRate?, variantId?, variantLabel?, options?[{name,value}]}]`, `payment{provider, orderId, paymentId, status, amount, currency, paidAt, method?, failureReason?, refundedPaise?, refunds?[{providerRefundId, amount, reason, status, actor, at}]}` (new, optional; the refund fields are changed only by atomic updates, and `refundedPaise` includes refunds still in flight), `termsVersion` (new, optional: the Terms version in force when the order was placed), `createdAt`, `updatedAt` |
| `coupons` | `_id`, `coupon` (unique code), `amount` (flat ₹ discount; a percent for PERCENT), plus the optional rule fields `type, maxDiscount, minOrderValue, startsAt, expiresAt, usageLimit, usedCount, perUserLimit, active`, and `productIds[], categories[], firstOrderOnly` (scope; absent means everything) |
| `payment_intents` (new) | `_id`, `userId`, `providerOrderId` (unique), `items[{productId, name, photo, price, quantity, variantId?, variantLabel?, options?}]`, `coupon`, `subtotal`, `tax`, `shippingCharges`, `discount`, `total`, `status: CREATED\|CONSUMED`, `createdAt` (TTL index: 24 h) |
| `consents` (new, append-only) | `_id`, `userId`, `expireAt?` (set to now + 3 years when the account is erased; a TTL index then deletes the rows), `purpose: terms\|privacy\|marketing_email\|admin_aup`, `granted`, `policyVersion`, `source: register\|reprompt\|settings`, `ipHash` (salted SHA-256), `userAgent`, `createdAt`. Never updated or deleted: a withdrawal is a new row. A user's state is the newest row per purpose |
| `audit_log` (new, append-only) | `_id`, `at` (TTL index, fixed at 365 d in `AuditDocument`), `tenantId` (the store whose log it is), `actorId?`, `actorRole: ADMIN\|USER\|ANONYMOUS\|SYSTEM\|PLATFORM_ADMIN`, `action` (see below), `entityType?`, `entityId?`, `outcome: SUCCESS\|FAILURE`, `changes[{field, before?, after?, redacted}]` (personal fields have `redacted: true` and no values), `subjectHash?` (salted hash of an attempted login email), `requestId?`, `ipHash`, `userAgent`. Never updated or deleted by the app |
| `email_verification_tokens` (new) | `_id`, `userId`, `tokenHash` (SHA-256, unique), `expiresAt` (TTL index, 24 h) |
| `password_reset_tokens` (new) | `_id`, `userId`, `tokenHash` (SHA-256), `expiresAt` (TTL index), `used` |
| `daily_sales` (new, analytics step 2) | `_id`, `tenantId`, `day` (store-local `YYYY-MM-DD`), `currency`, `timeZone`, `orders`, `grossRevenue`, `refunds`, `netRevenue`, `discount`, `tax`, `units`, `newCustomers`, `computedAt`; unique (`tenantId`, `currency`, `day`). Counts and sums only, no customer data. Days with no orders and no refunds have no row |
| `daily_sales_state` (new) | `_id`, `tenantId`, `key` (= tenantId, unique), `through` (last complete day), `timeZone`, `computedAt` |
| `marketing_integrations` (new, marketing phase 1, ADR-028) | `_id`, `tenantId`, `provider` (Google or Meta), `browser{ga4MeasurementId?, adsConversionId?, adsPurchaseLabel?, gtmContainerId?}` (Google) or `browser{pixelId}` (Meta), `createdAt`, `updatedAt`; unique (`tenantId`, `provider`). A row exists only while at least one ID is set, so a store with no row for a provider is `NOT_CONNECTED`. Public IDs only; secrets, feed token, outbox and daily spend come in later phases (design spec §5) |
| `marketing_settings` (new, marketing phase 2) | `_id` (= `tenantId`, so one row per store), `tenantId`, `feedEnabled`, `feedToken` (32 random bytes, URL-safe base64 without padding; created on the first enable, kept while the feed is off, replaced by "New URL"), `updatedAt`. The token is a credential: it never appears in audit entries, logs, traces, error messages or the offboarding export (`TenantExport` leaves the field out). Purged with the store |

Pricing rule (store rules from `StoreConfig`, ADR-023): `subtotal = Σ price×qty`, `shipping = subtotal > freeShippingAbove ? 0 : shippingFee` (platform default 1000 / 200), `tax = Σ per rate round(group × rate)` with the store's `defaultTaxRate` (platform default 18) for products without their own rate, stamped on each order line at checkout,
`discount = min(coupon.amount, subtotal)`, `total = subtotal + tax + shipping − discount`.

## Interfaces (REST, base `/api/v1`, JSON unless noted)
Auth: `Authorization: Bearer <jwt>`. 🔓 means public, 👤 means any logged-in user, 🛡 means admin.

### User — `/user`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `POST /register` | 🔓 | multipart: name, email, password (≥8), gender?, dob (yyyy-mm-dd), photo?, `acceptTerms=true` (required), `marketingOptIn?` | 201 `{success, message, token, user}`. 400 if under 18 or `acceptTerms` isn't true. Records terms, privacy and marketing consents |
| `POST /login` | 🔓 (rate-limited) | `{email, password}` | `{success, message, token, user}`. 403 if the account is suspended (checked after the password, so status can't be probed). A successful login stamps `lastLoginAt` |
| `POST /forgot-password` | 🔓 (rate-limited) | `{email}` | always 200 `{success, message}` (no account enumeration) |
| `POST /reset-password` | 🔓 | `{token, password}` | `{success, message}` |
| `POST /verify-email` | 🔓 (rate-limited) | `{token}` | `{success, message}`. 400 if the link is unknown, used or older than 24 h. Sets `emailVerified` |
| `POST /me/verify-email/resend` | 👤 (rate-limited) | — | `{success, message}`. Sends a fresh link and retires the old one; says so and sends nothing if the email is already verified |
| `GET /me` | 👤 | — | `{success, user}`. 403 if suspended |
| `PUT /me` | 👤 | `{name?, phone?, locale?}` (blank or omitted keeps the current value; phone is 10–13 digits, optional `+`; locale like `en` or `en-IN`) | `{success, message, user}`. 400 on invalid values |
| `GET /me/addresses` | 👤 | — | `{success, addresses}` |
| `POST /me/addresses` | 👤 | `{label?, name, phone, line1, line2?, city, state, country, pinCode (4–10 digits), isDefault}` | 201 `{success, message, address}`. 409 beyond 10 addresses, 400 invalid |
| `PUT /me/addresses/{addressId}` | 👤 | same body, replaces the address | `{success, message, address}`. 404 unknown id |
| `DELETE /me/addresses/{addressId}` | 👤 | — | `{success, message}`. 404 unknown id. Deleting the default promotes the first remaining address |
| `DELETE /me` | 👤 | `{password}` | `{success, message}`. 403 wrong password, 400 for admins, 409 while any order isn't delivered. Deletes the photo and the user, then strips street address and PIN code from their orders |
| `GET /all` | 🛡 | `?role=&gender=` | `{success, users}` |
| `GET /{id}` | 🛡 | — | `{success, user}` |
| `PUT /{id}` | 🛡 | `{role?, status?}` (`status` is `active\|suspended`; at least one is required) | `{success, message}`. 400 if you suspend yourself |
| `DELETE /{id}` | 🛡 | — | `{success, message}` (400 if deleting yourself, 409 while the user has undelivered orders). Same erasure as `DELETE /me` |

`user = {_id, name, email, photo, role, gender?, dob, age, createdAt, phone?, locale?, emailVerified, status, lastLoginAt?}` (`gender` is optional since the consent work; `status` is `ACTIVE` or `SUSPENDED`, defaulting to `ACTIVE` for older users; `emailVerified` becomes true when the link emailed at registration is followed; registration sends it, a mail failure never fails the registration, and nothing is blocked while it is false)

**Suspended or deleted accounts are cut off on every request, not only at login.** A filter checks each authenticated request against the account (cached for 15 s per instance, and cleared at once on the instance that made the change). A suspended user's existing token gets 403 `Your account is suspended`; a deleted user's gets 401. Reactivating restores the same token. A role change still applies only from the next login, because the role is read from the token.

`address = {id, label?, name, phone, line1, line2?, city, state, country, pinCode, isDefault}`. A user has at most 10, and exactly one is the default whenever any exist. Addresses are never part of `user`; only their owner can read them, through `/me/addresses`.

### Product — `/product`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /latest` | 🔓 | — | `{success, products}` (5 newest public) |
| `GET /categories` | 🔓 | — | `{success, categories}` (distinct category strings of public products) |
| `GET /options` | 🔓 | — | `{success, options:[{name, type: TEXT\|COLOUR, values:[{label, hex}]}]}` (option names and values of public products with variants, for search filters) |
| `GET /all` | 🔓 | `?search=&price=&category=&sort=asc\|dsc&page=&option=Name:Value` (`option` repeatable, up to 3; one sellable in-stock variant must match all of them; malformed is 400) | `{success, products, totalPage}` |
| `GET /{id}` | 🔓 | — | `{success, product}` (any status, so admin edit and direct links work) |
| `GET /admin-products` | 🛡 | — | `{success, products}` (every status) |
| `POST /new` | 🛡 | multipart: name, price, stock, category, photo, plus the optional catalogue fields below | 201 `{success, message, product}`. 409 on duplicate `sku`/`slug`, 400 on invalid fields |
| `PUT /{id}` | 🛡 | multipart: any of name, price, stock, category, photo and the optional catalogue fields. Omitted or blank means keep the current value. On a product with variants, `price` and `stock` are ignored (derived from the variants) | `{success, message, product}` |
| `PUT /{id}/variants` | 🛡 | JSON `{axes:[{name, type: text\|colour, values:[{label, hex?, images?}]}], variants:[{id?, sku?, options:[{name, value}], price, mrp?, stock?, status?: ACTIVE\|DISABLED}]}`. Full replace; `{axes:[], variants:[]}` removes variants. 403 while `app.products.variants-enabled` is off | `{success, message, product}`. 400 invalid, 404 unknown product, 409 duplicate SKU or concurrent change |
| `PUT /{id}/size-chart` | 🛡 | JSON `{columns:[≤8], rows:[[cell…]] (≤30, one cell per column), note?}`; empty columns and rows remove it | `{success, message, product}`. 400 invalid |
| `GET /low-stock` | 🛡 | — | `{success, alerts:[{productId, name, photo, variantId, label, sku, stock, threshold, out}]}`: public products without variants, and active variants, at or below their threshold, emptiest first |
| `GET /export` | 🛡 | — | `text/csv` attachment (UTF-8 with BOM): `product_id,product_name,variant_id,variant,sku,price,mrp,stock,status,low_stock_threshold`, one row per product without variants and per variant. Text starting with `= + - @` is prefixed with `'` |
| `POST /import` | 🛡 | multipart `file` (CSV ≤1 MB, ≤5000 rows), `?dryRun=true` (default) | `{success, applied, changes:[{line, productId, product, variant, changes:[…]}], unchanged}`. Rows match on `product_id`+`variant_id`, or `sku`; blank cells keep values; edits price, mrp, stock, status, low_stock_threshold of existing lines only. Any bad row → 400 `{success:false, message, errors:[{line, message}]}` and nothing is written |
| `PATCH /{id}/variants/{variantId}/stock` | 🛡 | JSON `{delta}` or `{set}` (exactly one) | `{success, message, product}`. 409 if the result would be negative, 404 unknown variant |
| `DELETE /{id}` | 🛡 | — | `{success, message}` |

`product = {_id, name, photo, price, stock, category, createdAt, slug, sku, description, brand, mrp, taxRate, hsn, status, tags, images, categoryId, countryOfOrigin, lowStockThreshold (null = store default), lowStockAt (effective), sizeChart:{columns, rows, note}|null, variants:{axes:[{name, type: TEXT\|COLOUR, values:[{label, hex, images}]}], items:[{id, sku, label, options:[{name, value}], price, mrp, stock, status}]}}`.
A product without variants has `variants:{axes:[], items:[]}`. With variants, `price` is the lowest sellable variant price and `stock` the sum of variant stock; both are kept in step by every stock change. Limits: 3 axes, 20 values per axis, 100 variants; option names cannot contain `:`; colour images must be paths from the product's own gallery. Blank variant SKUs are generated (`<product sku or slug>-<VALUES>`); SKUs are unique across all products and variants.
The new fields are optional (null or empty for older products; `status` defaults to `ACTIVE`).

Optional catalogue fields on `POST /new` and `PUT /{id}`:
`slug` (≤80, generated from the name if absent, made unique with `-2`, `-3`…; an explicit duplicate is 409),
`sku` (≤60, unique, blank means none), `description` (≤5000), `brand` (≤60), `mrp` (0–10,000,000 and not below `price`),
`taxRate` (0–100), `hsn` (4–8 digits), `status` (`DRAFT|ACTIVE|ARCHIVED`, case-insensitive),
`tags` (comma separated, lower-cased and de-duplicated, ≤20 of ≤30 characters), `categoryId` (must exist),
`countryOfOrigin` (≤56), `lowStockThreshold` (0–1,000,000), `images` (repeatable file part, ≤5; sending any replaces the whole gallery).
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
| `POST /checkout` | 👤 | `{items:[{productId, variantId?, quantity}], coupon?}`. `variantId` is required for a product with variants and rejected (400) for one without; the same product in two variants is two lines; a disabled or deleted variant is 400, a sold-out one 409 | 201 `{success, checkout:{providerOrderId, provider, key, amount (paise), currency, subtotal, tax, shippingCharges, discount, total}}`. 403 if the user hasn't accepted the current Terms (checked before any money moves) |
| `POST /new` | 👤 | `{providerOrderId, paymentId, signature, shippingInfo:{address, city, state, country, pinCode, name?, phone?}, billingInfo?:{same shape}, notes?}` (`phone` is 10–13 digits with an optional `+`; `notes` ≤ 500) | 201 `{success, message, orderId}`. 400 on invalid input, and the payment intent is **not** consumed, so the customer can fix it and retry |
| `GET /my` | 👤 | — | `{success, orders}` (own orders only) |
| `GET /all` | 🛡 | `?status=` | `{success, orders}` (`user` is `{_id, name}`) |
| `POST /quote` | 🔓 (a valid token adds the caller's coupon limits and first-order check) | `{items:[{productId, variantId?, quantity}], coupon?}` | `{success, quote:{subtotal, tax, shippingCharges, discount, total}}`. Prices the cart exactly as `/checkout` does but opens no payment and reserves nothing. 400 with the coupon's reason; 409 if an item is out of stock. The cart page uses it |
| `GET /{id}` | 👤 owner or 🛡 | — | `{success, order}` |
| `GET /{id}/invoice` | 👤 owner or 🛡 | — | `{success, invoice}`. 404 for anyone else |
| `POST /{id}/refund` | 🛡 | `{amount?, reason}` (`amount` in rupees > 0, omit to refund everything left; `reason` required, ≤ 200, shown to the customer) | `{success, message, payment}`. 400 over the refundable amount; 409 if already fully refunded, if the order has no online payment, if it was paid through another provider than the active one, or if another refund on it is in flight; 502 if the provider refuses or is down (the amount is released and can be retried) |
| `PUT /{id}` | 🛡 | — | `{success, message, status}` (advances the status and records the acting admin and time in `statusHistory`; a Delivered order is left alone, with no new entry) |
| `DELETE /{id}` | 🛡 | — | `{success, message}` |

`order = {_id, orderNumber?, user:{_id,name}, shippingInfo, billingInfo?, customer?:{name,email,phone}, orderItems:[{productId,name,photo,price,quantity,sku?,taxRate?,variantId?,variantLabel?,options:[{name,value}]}], subtotal, tax, shippingCharges, discount, total, status, statusHistory:[{status,at,actor}], couponCode?, notes?, payment?:{provider,status,amount,currency,paidAt,method?,refunded,refunds:[{amount,reason,status,at}],failureReason?}, createdAt}`.
`orderNumber` looks like `ORD-20261017-K7M3QX`. Provider order and payment ids are never returned. Orders placed before these fields existed omit them (`statusHistory` is `[]`). `customer` is a snapshot of the buyer's name, email and phone at purchase time.

`payment.status` is `PAID`, `PARTIALLY_REFUNDED` or `REFUNDED`. `method` is how the customer paid as reported by the provider (`upi`, `card`, …) and is absent if the provider could not be asked at the time. `refunded` is rupees returned or being returned. `failureReason` (admins only) says why the last refund attempt failed and clears on the next success.

`invoice = {number, date, orderNumber?, seller:{name,address?,gstin?,state?,email?,phone?}, buyer?:{name,email,phone}, billTo, shipTo, lines:[{description,sku?,quantity,unitPrice,amount,taxRate}], taxSummary:[{rate,taxableValue,cgst,sgst,igst}], subtotal, discount, shippingCharges, tax, total, paymentMethod?, paymentStatus?, refunded}`. It is computed from the order on every call and never stored. `number` is `INV-` plus the order number's suffix. The tax summary has one row per rate, rounded to whole rupees exactly as the order was priced, so the rows add up to `tax`. `seller` is the store's own details: name = storefront `legalName` else the store name; address, email, phone from `storefront`; `gstin` and `state` from the store's `invoice` settings. Only the default store falls back to `INVOICE_SELLER_*` for fields it has not set; other stores never print the platform's seller. When the bill-to state (billing address, else shipping) equals the seller's `state` (case-insensitive) each rate's tax is split into equal `cgst` and `sgst`, otherwise it is all `igst`. An erased account's invoice has no `buyer`.

Pricing: tax is computed per product `taxRate` (else the store's default rate, platform default 18%), rounded to whole rupees per rate group. A cart where every item uses the default 18% is taxed exactly as before.

### Payment / coupons — `/payment`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /discount` | 🔓 (a valid token adds the caller's per-customer limit) | `?coupon=CODE&subtotal=` (`subtotal` is optional; a percent coupon previews as ₹0 without it). Kept for old clients; use `POST /order/quote`, which knows what is in the cart | `{success, discount}`. A coupon limited to some items is refused here with `This coupon only applies to selected items`, and "first order only" is not checked (checkout always does). 400 with the reason: `Invalid coupon`, `This coupon is not active`, `This coupon is not valid yet`, `This coupon has expired`, `This coupon's usage limit has been reached`, `You have already used this coupon`, `Minimum order value for this coupon is ₹N` |
| `GET /coupon/all` | 🛡 | — | `{success, coupons}` |
| `POST /coupon/new` | 🛡 | `{coupon, amount, type? (FLAT\|PERCENT), maxDiscount? (percent only), minOrderValue?, startsAt?, expiresAt?, usageLimit?, perUserLimit?, active?, productIds? (≤ 50), categories? (≤ 50), firstOrderOnly?}` (ISO-8601 instants). A body with only `coupon` and `amount` still creates an always-active flat coupon | 201 `{success, message, coupon}`. 400 for a percent over 100, `maxDiscount` on a flat coupon, `startsAt` not before `expiresAt`, an expiry in the past, or a limit under 1. 409 if the code exists |
| `PUT /coupon/{id}` | 🛡 | any of `amount, maxDiscount, minOrderValue, startsAt, expiresAt, usageLimit, perUserLimit, active, productIds, categories, firstOrderOnly` (omitted means keep, and an empty `productIds` or `categories` list clears that scope; `type` and the code cannot change) | `{success, message, coupon}`. 404 unknown id |
| `DELETE /coupon/{id}` | 🛡 | — | `{success, message}` |

`coupon = {_id, coupon, amount, type, maxDiscount?, minOrderValue?, startsAt?, expiresAt?, usageLimit?, usedCount, perUserLimit?, active, status, productIds, categories, firstOrderOnly}`. `amount` is rupees for FLAT and a percent for PERCENT. `status` is one of `active | scheduled | expired | exhausted | inactive`.
**Scope.** With `productIds` and/or `categories` set, only matching cart lines are discounted: a percent coupon takes its percentage of their total, a flat one is capped at it, and a cart with no matching line is refused with `This coupon doesn't apply to the items in your cart`. A line matches if its product id is listed or its category (stored trimmed and lower-case, as are the values here) is listed. `minOrderValue` still looks at the whole cart. `firstOrderOnly` refuses customers who already have any order (`This coupon is only for your first order`); an anonymous quote can't tell, so checkout is where it is enforced. Product ids are not checked to exist when the coupon is saved.
A use is counted when the order is created, so a limited coupon can be used more than `usageLimit` times by the number of checkouts that were open at the same moment; new checkouts are refused once the limit is reached. Deleting or cancelling an order does not return a use.

Removed: `POST /payment/create-order` and `POST /payment/verify`. They're replaced by `/order/checkout` and `/order/new` (ADR-009).

### Legal & consent
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /legal/versions` | 🔓 | — | `{success, versions:{terms, privacy, admin_aup}}` |
| `GET /consent/me` | 👤 | — | `{success, consents:{<purpose>:{granted, version, at}}, pending:[purpose], history:[{purpose, granted, version, source, at}]}`. `pending` lists the required policies not yet accepted at the current version; the SPA blocks with a prompt while it's non-empty |
| `POST /consent` | 👤 | `{purpose, granted, source: reprompt\|settings}` | same as `GET /consent/me`. 400 for withdrawing terms or privacy (delete the account instead), for a non-admin granting `admin_aup`, and for withdrawing `admin_aup` |

### Audit — `/admin/audit`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /admin/audit` | 🛡 | `?entityType=&entityId=&actorId=&action=&outcome=success\|failure&from=&to=&page=&limit=` (`from`/`to` are ISO-8601 date-times, e.g. `2026-10-18T00:00:00Z`, not bare dates; `limit` is entries per page, 1–100, default 100) | `{success, entries: [{_id, at, actorId?, actorRole, action, entityType?, entityId?, outcome, changes: [{field, before?, after?, redacted}], requestId?}], totalPage, total}`, newest first. 400 for an unknown `action`, `outcome` or malformed date. `ipHash`, `subjectHash` and `userAgent` are not returned |

`action` is one of: `PRODUCT_CREATED/UPDATED/DELETED`, `CATEGORY_CREATED/UPDATED/DELETED`, `COUPON_CREATED/UPDATED/DELETED`,
`ORDER_PLACED`, `ORDER_STATUS_CHANGED`, `ORDER_DELETED`, `ORDER_REFUNDED`, `USER_REGISTERED`, `USER_ROLE_CHANGED`, `USER_STATUS_CHANGED`,
`USER_ERASED`, `ACCOUNT_DELETED_SELF`, `PROFILE_UPDATED`, `ADDRESS_ADDED/UPDATED/DELETED`, `LOGIN_SUCCEEDED`,
`LOGIN_FAILED`, `LOGIN_BLOCKED_SUSPENDED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `EMAIL_VERIFIED`, `CONSENT_CHANGED`,
`TENANT_CREATED`, `TENANT_UPDATED`, `TENANT_STATUS_CHANGED`, `TENANT_EXPORTED`, `TENANT_PURGED`, `STORE_SETTINGS_UPDATED`,
`ANALYTICS_EXPORTED`, `INTEGRATION_UPDATED`, `INTEGRATION_DISCONNECTED`. Only successful refunds are recorded as `ORDER_REFUNDED`; a failed one is kept on the order
(`payment.failureReason`), not in the log.

Product-variant writes (`replaceVariants`, `adjustVariantStock`) are audited as `PRODUCT_UPDATED`, with `variantPrices`
and `variantStocks` (maps keyed by SKU) in the change set. Registration consents are not duplicated into the audit
log; the `consents` ledger is the proof. "Who failed to log in as X" is answerable only for existing accounts, by
searching on the account's record id: the attempted-email hash (`subjectHash`) is not returned or searchable.

Admin endpoints (every route a JWT with role ADMIN reaches, except `/user/me/**`, `/consent/**` and `/legal/**`)
return **403** until the admin has accepted the current acceptable-use agreement (`POST /consent` with
`purpose=admin_aup`).

### Tenant — `/tenant` and `/platform`
Every request outside `/`, `/actuator/**`, `/api-docs/**`, `/swagger-ui/**` and `/api/v1/platform/**` belongs to the tenant
whose domain it arrived on (`X-Forwarded-Host`). An unknown host is 404 `Store not found` once there are two or more tenants
(with exactly one, every host maps to it); a suspended tenant is 403 `This store is currently unavailable`. Tokens carry a
`tid` claim and work only on that tenant's domains; tokens from before tenancy work on the default tenant only.

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /tenant/config` | 🔓 | — | `{success, tenant:{slug, name, theme, locale, currency, storefront:{logoUrl?, legalName?, address?, email?, phone?, grievanceOfficer?, grievanceEmail?}, features:{variants}, commerce:{shippingFee, freeShippingAbove, defaultTaxRate, minimumAge}, timeZone, marketing?:{tags:{ga4, adsId, adsPurchaseLabel, gtm, metaPixel}}}}`. `features`, `commerce` and `timeZone` are the effective values (the store's own, else the platform default). `marketing` is present only when the store's `marketing` switch is on and at least one tag ID is set; otherwise the key is absent. It holds public IDs only, never a secret |
| `GET /store/settings` | 🛡 | — | `{success, settings:{commerce:{shippingFee, freeShippingAbove, defaultTaxRate, maxCouponAmount, minimumAge}, catalog:{pageSize, lowStockThreshold}, invoice:{gstin, state}, regional:{timeZone}, storefront:{…}}, effective:{shippingFee, freeShippingAbove, defaultTaxRate, maxCouponAmount, minimumAge, pageSize, lowStockThreshold, timeZone}, defaults:{same keys}}`. `defaults` is what each rule returns to when cleared. `settings` holds only the store's own values (null = platform default `app.store.defaults.*`) |
| `PUT /store/settings` | 🛡 | any of `commerce, catalog, invoice, regional, storefront`; a group sent replaces the store's previous one, a group left out is kept, a null field returns to the platform default | same as GET. 400 out of range (shipping 0–1,000,000, free-shipping level 0–10,000,000, GST 0–100, coupon cap 1–10,000,000, minimum age 18–100, page size 1–100, low-stock 0–1,000,000), unknown time zone, malformed GSTIN, invalid storefront email/URL. Audited as `STORE_SETTINGS_UPDATED` in the store's log |
| `POST /platform/login` | 🔓 (rate-limited) | `{email, password}` | `{success, message, token}`. Platform credentials only; the token has role `PLATFORM_ADMIN` and no tenant |
| `GET /platform/tenants`, `GET /platform/tenants/{id}` | 🏛 | — | `{success, tenants}` / `{success, tenant}` |
| `POST /platform/tenants` | 🏛 | `{slug, name, domains[1..20], theme?, locale?, currency?, legal?, payment?, mail?, storefront?, features?, limits?, trial?, admin?:{name,email,password}}`. With `trial: true` the store starts as `TRIAL` with `trialEndsAt` = now + `TENANT_TRIAL_LENGTH` (14 days) | 201 `{success, message, tenant}`. 400 invalid slug/domain/theme token/URL/email, 409 slug or domain already used. With `admin`, the first administrator of that tenant is created (and the tenant removed again if that fails) |
| `PUT /platform/tenants/{id}` | 🏛 | any of `name, domains, theme, locale, currency, legal, payment, mail, storefront, features` (omitted keeps; a nested object replaces the previous one). `limits: {products?, uploadBytes?, requestsPerMinute?}` sets the store's own limits (null or omitted field = platform default `TENANT_MAX_PRODUCTS` 5000, `TENANT_MAX_UPLOADS` 2GB, `TENANT_REQUESTS_PER_MINUTE` 600; non-positive is 400). `features` maps a switch (`variants`, `marketing`) to true/false; `{}` returns all to the platform default (`app.features.defaults`); an unknown name is 400 | `{success, message, tenant}` (`tenant.features` holds only the store's own switches) |
| `POST /platform/tenants/{id}/suspend` / `/activate` / `/cancel` | 🏛 | — | `{success, message, tenant}`. `activate` also ends a trial. `cancel` (the owner is leaving) closes the store like `suspend`; it can be activated again or offboarded. The default tenant cannot be suspended or cancelled |
| Tenant statuses | | | `TRIAL` and `ACTIVE` serve the storefront; `SUSPENDED`, `CANCELLED`, `SCHEDULED_FOR_DELETION` answer 403 "This store is currently unavailable"; `DELETED` stores have no domains (404). A trial past `trialEndsAt` is suspended by the hourly job (audited with actor `SYSTEM`) |
| `GET /platform/tenants/{id}/export` | 🏛 | — | `application/zip` download `<slug>-export.zip`: `manifest.json` (tenant, `exportedAt`, `counts` per collection, `omitted`), `<collection>.json` (Mongo relaxed Extended JSON array) for every tenant collection except `password_reset_tokens` and `payment_intents`, `users` without `password`, and every uploaded file under its public path (`uploads/<tenantId>/…`). Audited as `TENANT_EXPORTED` |
| `POST /platform/tenants/{id}/offboard` | 🏛 | — | `{success, message, tenant}` with `status: SCHEDULED_FOR_DELETION` and `deletionDueAt` (now + `TENANT_DELETION_GRACE`). The storefront answers 403 from then on. 400 for the default tenant, 409 if already scheduled or deleted |
| `POST /platform/tenants/{id}/restore` | 🏛 | — | `{success, message, tenant}` back to `ACTIVE`. 409 unless scheduled for deletion. `suspend`/`activate` answer 409 for a scheduled or deleted store; `PUT` answers 409 for a deleted one |
| `GET /platform/audit` | 🏛 | query: the filters of `GET /admin/audit` plus `tenantId?`, but no `limit` (always 100 per page) | `{success, entries:[{…entry, tenantId}], totalPage}` (no `total`). Read-only, across every store's log |

🏛 = `PLATFORM_ADMIN`. Platform tokens are refused on every store endpoint (even public ones), and store tokens (admin or
customer) are refused on `/platform/**`.
`tenant.legal = {termsVersion?, privacyVersion?, adminAupVersion?}` (unset falls back to `app.legal.*`),
`tenant.payment = {provider (razorpay|fake), keyId, secretRef}` (the secret is the environment entry `TENANT_SECRET_<secretRef>`),
`tenant.mail = {baseUrl?}` (reset links; default `https://<first domain>`). A tenant other than the default with no payment account
gets 503 `Payments are not configured for this store` at checkout. Theme tokens are CSS custom properties (`--name`: value).

### Marketing — `/admin/marketing` (phase 1, ADR-028)
Store owners save the public tag IDs of their Google and Meta accounts. Every route: 🛡 tenant `ADMIN` only (the
admin-agreement 403 above applies), **404 when the store's `marketing` switch is off** (platform default off).
`{provider}` is `google` or `meta`; anything else is 404.

An integration is `{provider, status, browser, updatedAt}`. `status` is `NOT_CONNECTED` or `ACTIVE` in phase 1.
`browser` is `{ga4MeasurementId, adsConversionId, adsPurchaseLabel, gtmContainerId}` for `google` and `{pixelId}` for
`meta`; unset fields are `null`. `updatedAt` is ISO-8601 UTC, or `null` when not connected.

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /admin/marketing` | 🛡 | — | `{success:true, integrations:[google, meta], feed:{enabled, url}}`: always both integrations, `google` first; `feed` as in "Marketing product feed" below |
| `PUT /admin/marketing/{provider}` | 🛡 | `{browser:{…}}` with that provider's fields | `{success:true, integration}`. Values are trimmed and blank becomes `null`; all `null` deletes the store's row and returns `NOT_CONNECTED`. 400 `{success:false, message:"<field>: <reason>"}` for a value that fails its pattern, and for `adsPurchaseLabel` without `adsConversionId`. Audited as `INTEGRATION_UPDATED` |
| `DELETE /admin/marketing/{provider}` | 🛡 | — | `{success:true, integration:{provider, status:"NOT_CONNECTED", browser:{all null}, updatedAt:null}}`. Audited as `INTEGRATION_DISCONNECTED` |

ID patterns, checked before saving because the IDs are written into a `<script>` call: `ga4MeasurementId`
`^G-[A-Z0-9]{4,12}$`, `adsConversionId` `^AW-\d{6,12}$`, `adsPurchaseLabel` `^[A-Za-z0-9_-]{1,40}$`, `gtmContainerId`
`^GTM-[A-Z0-9]{4,10}$`, `pixelId` `^\d{10,20}$`.

Audit: entity type `marketing_integration`, entity id = the provider (`google`/`meta`), with a before/after diff of the
`browser` fields.

Storefront: the tags come from `GET /tenant/config` (`marketing.tags`), so the storefront makes no extra call.
Only storefront routes load tags. GA4 loads after analytics consent; Google Ads, GTM and the Meta Pixel after marketing
consent; Google Consent Mode v2 starts `denied` and is updated from the banner. Events: `view_item`, `add_to_cart`,
`begin_checkout`, `purchase` (Meta: `ViewContent`, `AddToCart`, `InitiateCheckout`, `Purchase`); the purchase event id
is the order `_id`. The admin and the storefront are one SPA behind `ecommerce-frontend/nginx.conf.template`, so the
CSP additions for the Google and Meta hosts apply to both.

**Call budgets:**

| Screen / action | Calls | Budget |
|---|---|---|
| Admin → Marketing, first load | `GET /admin/marketing` | 1 |
| Save a provider | `PUT /admin/marketing/{provider}` (returns the updated integration) | 1 |
| Disconnect a provider | `DELETE /admin/marketing/{provider}` | 1 |
| Storefront, first load | `marketing.tags` inside the existing `GET /tenant/config` | +0 |

Later phases (test event, delivery log, OAuth) add endpoints listed in the design spec §7.

### Marketing product feed (phase 2)
One feed per store that Google Merchant Center and the Meta catalogue both read. The admin routes follow the
Marketing rules above (🛡 tenant `ADMIN`, 404 while the store's `marketing` switch is off). `feed` is
`{enabled, url}`; `url` is `https://<store's first domain>/api/v1/feeds/<token>/products.xml` while the feed is on,
else `null` (also `null` for a store without a domain).

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `PUT /admin/marketing/feed` | 🛡 | `{enabled:boolean}` | `{success:true, feed}`. The first enable creates the token; turning the feed off keeps it, so turning it on again restores the same URL. 400 `enabled: is required`. Audited as `FEED_UPDATED` when `enabled` changes |
| `POST /admin/marketing/feed/rotate` | 🛡 | — | `{success:true, feed}` with a new token; the old URL answers 404 at once. 400 `feed: enable it first` while the feed is off. Audited as `FEED_TOKEN_ROTATED` |
| `GET /feeds/{token}/products.xml` | 🔓 | optional `If-None-Match` | 200 `application/rss+xml;charset=UTF-8`, Google RSS 2.0 with the `g:` namespace, `ETag`, `Cache-Control: max-age=900, public`; 304 when `If-None-Match` matches. An empty 404 unless `marketing` is on, the feed is on and `{token}` equals the token of the store the host resolves to (constant-time compare). 429 after 60 requests a minute from one client address on one store, whatever the token |

Items: one per active variant (`g:item_group_id` = product id) or per product without variants, with `g:id` (product
id, or `<product id>-<variant id>`, the variant part hashed when the whole would pass 50 characters), `title` (name,
plus ` - <variant label>`; at most 150), `description` (HTML stripped; the name when empty; at most 5000), `link`
(`https://<domain>/product/<id>`), `g:image_link` (absolute on the store's domain: the variant's colour image, else
the product photo), `g:availability` (`in stock`/`out of stock`), `g:price` (`<amount with 2 decimals> <store
currency>`), `g:brand` (the product's brand, else the store name) and `g:condition` `new`. Only `ACTIVE` products,
oldest first, at most the store's product limit; products without an image and disabled variants are left out.
Rendered one product at a time from a cursor with a streaming XML writer, cached per store for 15 minutes; a product
change or a feed setting change drops the store's copy.

Audit: entity type `marketing_settings`, entity id `feed`. `FEED_UPDATED` holds `enabled` before/after and `feedToken`
only as "changed" (no value) when a token was created; `FEED_TOKEN_ROTATED` holds `feedToken` as "changed" only.

**Call budgets:**

| Screen / action | Calls | Budget |
|---|---|---|
| Admin → Marketing, first load | `feed` inside the existing `GET /admin/marketing` | +0 |
| Turn the feed on or off | `PUT /admin/marketing/feed` (returns `feed`) | 1 |
| New feed URL | `POST /admin/marketing/feed/rotate` (returns `feed`) | 1 |
| Copy the feed URL | — (clipboard) | 0 |
| Google / Meta fetch | `GET /feeds/{token}/products.xml`, 304 while unchanged | — |

### Dashboard — `/dashboard` (🛡, all accept `?productId=&category=&userId=`)
- `GET /stats` → `{success, stats:{count{revenue,product,user,order}, changePercent{...same}, chart{order[6], revenue[6]}, categoryCount:[{category, percent}], genderRatio{male,female}, latestTransaction:[{_id, discount, amount, quantity, status}]}}`
- `GET /pie` → `{success, charts:{orderFulfillment{processing,shipped,delivered}, productCategories:[{category, percent}], stockAvailability{inStock,outOfStock}, revenueDistribution{totalTax,totalShippingCharges,totalDiscount,totalIncome}, ageGroup{teen,adult,old}, userCategory{admin,customer}}}`
- `GET /bar` → `{success, charts:{products[6], users[6], orders[12]}}`
- `GET /products/{productId}/variants` → `{success, sales:[{variantId, label, sku, units, revenue, orders}]}` (one product's order lines grouped by variant, best sellers first; `variantId` null for sales before it had variants)
- `GET /line` → `{success, charts:{products[12], users[12], discount[12], revenue[12]}}`

Month arrays run oldest to newest and end with the current month. Buckets account for the year.

- `GET /summary?from=&to=&compare=&granularity=&basis=` (plus the scope filters) → `{success, summary:{from, to, timezone, granularity, basis, compareFrom?, compareTo?, current:{revenue, orders, averageOrderValue, units, refunds?, discount?, tax?}, previous?:{...same}, changePercent?:{revenue, orders, averageOrderValue, units}, series:[{start, revenue, orders}]}}`
  - `from`/`to`: inclusive `YYYY-MM-DD` days in the store's time zone (its Store settings, else `STORE_TIME_ZONE`, default `Asia/Kolkata`);
    default the last 30 days ending today; at most 731 days. `compare`: `previous` (default; the same number of days
    ending the day before `from`), `year` (the same days a year earlier) or `none`. `granularity`: `day`, `week`
    (Monday start) or `month`; default by length (≤ 31 days daily, ≤ 92 weekly, else monthly); at most 400 points.
    `basis`: `net` (default) or `gross`. A bad value or range is a 400.
  - **Metric definitions.** An order counts on the local day it was placed. **Gross revenue** = the sum of order
    `total`s. **Refunds** = `payment.refunds[].amount` not in status `failed`, counted on the local day each refund
    was issued (so a refund can fall in a later period than its order). **Net revenue** = gross revenue − refunds.
    **Average order value** = revenue ÷ orders (0 with no orders). **Units** = the sum of item quantities.
    **Discount** and **tax** = the sum of the order fields. Change % is rounded; growth from zero is 100.
    **New customers** (daily summary) = shoppers whose first order with the store was placed that day.
  - With `productId` or `category`, only the matching items count: revenue = their price × quantity, units = their
    quantities, orders = the orders containing them. `refunds`, `discount` and `tax` are then absent, since they
    belong to the whole order. The series takes refunds off the bucket they were issued in (net basis only).

Sections (analytics step 3). Each takes `?from=&to=` with the same rules as `/summary` (no scope filters, no
comparison) and reads live data. Revenue here is the gross value of orders placed in the range: order `total`s, or
price × quantity for product figures. Shares are percentages with 1 decimal. Ranked lists hold the top 20 (places: 50).
- `GET /products` → `{success, products:{top:[{productId, name, units, revenue, orders}], categories:[{category, revenue, units, share}], stock:[{productId, name, variantId?, label?, stock, threshold, unitsSold, daysLeft?}], idle:[{productId, name, stock}]}}`. `categories` looks the category up on the product (`uncategorised` when missing). `stock` = the low-stock lines (same rule as `GET /product/low-stock`); `daysLeft` = floor(stock × days in range ÷ units sold in range), 0 when out of stock, null when nothing sold. `idle` = products on sale (ACTIVE) created before the range ended with no sales in it.
- `GET /customers` → `{success, customers:{customers, newCustomers, returningCustomers, repeatRate, top:[{userId, name?, email?, orders, revenue}], places:[{state?, city?, orders, revenue}]}}`. Returning = ordered before the range; `repeatRate` = share of the range's buyers with 2+ orders in it; name and email from the buyer's latest order in the range (absent once erased); places by delivery address.
- `GET /coupons` → `{success, coupons:{ordersWithCoupon, shareOfOrders, discount, codes:[{code, uses, discount, revenue}]}}` (codes upper-cased; `revenue` = totals of the orders that used it).
- `GET /payments` → `{success, payments:{granularity, methods:[{method, orders, amount, share}], refunds:[{start, count, amount}]}}` (`method` `unknown` when not recorded; `share` of orders; refunds bucketed as the summary series, failed ones left out).
- `GET /tax` → `{success, tax:{sellerState?, rows:[{month, rate, taxableValue, cgst, sgst, igst, tax}], totalTaxable, totalTax}}`. Per order and rate, tax = round(taxable × rate) to whole rupees as checkout charged it (lines saved without a rate count at 18%); buyer state (billing, else delivery) equal to the seller's state → half CGST, half SGST, otherwise IGST. Refunds are not deducted.
- `GET /export/{table}` → `text/csv` (UTF-8 with BOM, every row, text cells starting with `= + - @` prefixed with `'`), `Content-Disposition: attachment; filename="<table>_<from>_<to>.csv"`. Tables: `top-products, categories, stock, idle-products, top-customers, places, coupons, payment-methods, refunds, tax`; unknown → 404. Each download is audited as `ANALYTICS_EXPORTED` (`entityType` `report`, `entityId` the table).

### Ops
- `GET /actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`, `/actuator/info`
- `GET /api-docs` (springdoc OpenAPI JSON), `/swagger-ui.html`. Both are disabled in prod (`SPRINGDOC_ENABLED=false`).
- `GET /uploads/**`: static images, served with `X-Content-Type-Options: nosniff`.

## Error handling conventions
Every error is `{"success": false, "message": "<human readable>"}` with the right status:
400 validation or a bad id, 401 not authenticated or a bad token, 403 not an admin or not the owner, or a store
limit reached ("This store has reached its limit of N products", "This store has used its upload storage"),
404 not found, 409 conflict (duplicate email or coupon, out of stock), 413 upload too large,
429 rate limited (login limiter per IP, or the store's requests per minute, with `Retry-After`), 502 payment provider failure, 500 unexpected. A 500 never leaks a stack trace or internal
detail; it's logged with the traceId. Validation messages name the field.
