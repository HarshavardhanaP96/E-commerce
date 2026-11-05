# Multi-tenant console and store types — design

Status: **approved in chat 2026-11-05, spec awaiting review.** Branch: `feat/multitenant-console` (from `dev` at `97e33cd`).
ADR to add: **ADR-030 Store types are presets applied once**.

## 1. Goal

Make running several stores, and configuring each one, clear and quick:

- The platform operator onboards and configures a store without editing JSON.
- Different kinds of customers start with a setup that fits them (store types).
- Store owners can scan their own Store settings and see which values are theirs and which are the platform's.

Visual and flow inspiration: ERP Simplifier (`billing-platform-v2`): a searchable asset list that opens a per-asset workspace, onboarding
on its own page with a business-vertical picker, configuration split into icon tabs, and module switches. We take the flow, not its look:
6Pack keeps its own admin tokens and components (`PageHeader`, `DataTable`, `DetailLayout`, `StatusBadge`). No glass panels, no inline styles.

### What the owner decided (2026-11-05)

| Question | Answer |
|---|---|
| What "different customers" means | **Store types** that pre-set features and shop defaults at onboarding |
| Screens in scope | **Platform console and the store owner's Store settings** |
| How far the operator goes into a store | **Platform-side workspace only.** No "open as store" or impersonation |
| Where types are defined | **In code, applied once.** The type stays on the store as a label |
| Approach | **A: a console with its own pages and linkable tabs** (not a slide-over, not schema-driven forms) |

### Today (the problem)

- `/platform` is one long page: a store table with up to six buttons per row, a raw JSON textarea to edit a store (theme, legal, payment,
  mail, storefront, features, limits), the create form, then the all-stores audit log.
- The only way to set stores up differently is to hand-edit `features` and `limits` JSON per store.
- `/admin/settings` is one long form of five cards. Nothing shows which values are the store's own.

## 2. Store types (backend)

`com.sixpack.shared.tenant.StoreType`: `GENERAL`, `FASHION`, `GROCERY`, `ELECTRONICS`. Each type has a preset: a map of features and
a `StoreSettings` holding only the values it changes.

| Type | Variants | Default GST | Shipping / free above | Low-stock level | Stuck-order days |
|---|---|---|---|---|---|
| General | (platform default) | — | — | — | — |
| Fashion | on | 5% | — | 3 | — |
| Grocery | off | 5% | ₹40 / ₹500 | 10 | 1 |
| Electronics | on | 18% | — | 2 | 2 |

"—" means the preset sets nothing, so the platform default applies. `marketing` is never set by a preset; the operator switches it per store.
Currency, locale and time zone are not part of any preset.

Rules:

- `Tenant.type` is a new nullable field; **null reads as `GENERAL`**, so existing stores need no migration.
- `POST /platform/tenants` accepts `type` (default `GENERAL`; unknown value → 400 `type: unknown store type`).
- On create, the preset is **applied once**: its features and settings are written as that store's own values. Anything the create request
  sends explicitly (`features`) wins over the preset.
- After that the values belong to the store. The owner sees them on Store settings as their own (Custom) and can change or reset them.
- `PUT /platform/tenants/{id}` accepts `type` as a **label change only**; it never re-applies a preset.
- Audit: `TENANT_CREATED` records `type` and the values the preset set. A type change is part of `TENANT_UPDATED`'s before/after.

Why in code (ADR-030): a handful of stores, created by the operator; presets change rarely and should be reviewed like code. Editable types
would need their own storage, screens, API and audit for little gain now.

## 3. Platform console layout

A `PlatformLayout` shell with a sidebar: **Stores**, **Onboard store**, **Audit log**; the signed-in email and **Sign out** at the bottom.
Platform sign-in and its separate token (`PLATFORM_TOKEN_KEY`) are unchanged. Still one lazily loaded chunk.

Routes:

| Route | Screen |
|---|---|
| `/platform` | redirects to `/platform/stores` |
| `/platform/stores` | Store list |
| `/platform/stores/new` | Onboard store |
| `/platform/stores/:slug/:tab?` | Store workspace (`tab` defaults to `overview`) |
| `/platform/audit` | All-stores audit log (today's `PlatformAudit`, moved, with a `?tenant=` filter) |

### 3.1 Store list

- Search by name, slug or domain. Status chips: All, Active, Trial, Suspended, Closing (`CANCELLED`, `SCHEDULED_FOR_DELETION`). Sort by
  name, type, status or created.
- Columns: Store (name and slug), Type, Domains (first one, then "+n"), Status (badge, plus "trial ends …" or "data deleted on …"), Created.
- **No buttons on rows.** Clicking a row (or pressing Enter on it) opens the workspace.
- One call: the existing `GET /platform/tenants`, filtered and sorted in the browser.
  `shortcut: filtering in the browser, fine for tens of stores; move to server-side paging past ~200.`

### 3.2 Onboard store

One page, three blocks (not a multi-step wizard):

1. **Store type**: four cards (icon, one line, what it pre-sets). General is selected by default.
2. **Basics**: name; slug (filled in from the name until edited, same pattern as today); domain; "Start as a trial".
3. **First admin** (optional): name, email, password (12+ characters), as today.

A summary line shows what the type will set ("Variants on · GST 5% · low stock at 3"). **Create store** is one call; on success the operator
lands on the new store's Overview. Field errors from the API show next to the field.

## 4. Store workspace

Header (`DetailLayout`): name, slug, type badge, status badge. Actions:

- **Open storefront** (first domain, new tab) and **Export** (today's download).
- **Status** menu listing only moves valid from the current status (same rules as today's buttons): Activate, Suspend, Cancel, Offboard,
  Restore. Each opens a confirm dialog (replacing `window.confirm`) that explains the effect. **Offboard** also requires typing the store's
  slug. The `default` store has no status moves, as today.

Tabs (linkable; each tab saves only its own group with one `PUT /platform/tenants/{id}`):

| Tab | Contents | Saves |
|---|---|---|
| Overview | Status and dates; usage against limits (products, upload storage); the owner's shop rules, read-only (GST, shipping, order prefix, time zone); last 10 audit entries with "View all" → `/platform/audit?tenant=<id>` | — |
| Profile | Name, type (label), domains (chips: add, remove; at least one), locale, currency, accent colour (picker for `--accent`; other theme tokens in an "Advanced" key/value list), storefront and legal-entity details | `name, type, domains, locale, currency, theme, storefront` |
| Features | One row per `Feature`: segmented control *Platform default (on/off)* · *On* · *Off* | `features` (only explicit choices; "default" removes the key) |
| Payments | Razorpay account reference; a note that the secret lives in the server environment (`TENANT_SECRET_<REF>`) | `payment` |
| Email | Link base and sender details | `mail` |
| Legal | Policy versions (Terms, Privacy, admin agreement); a warning that raising a version makes people accept again | `legal` |
| Limits | Products, upload storage (entered as MB/GB, sent as bytes), requests per minute. Each shows "Default: n" and current usage; empty = platform default | `limits` |

Each tab warns before leaving it with unsaved changes. After a save the workspace refetches the store (RTK Query tag invalidation).

### 4.1 Backend additions

`GET /platform/tenants/{id}` (single store only, not the list) also returns:

- `type` (always present; `GENERAL` when null),
- `settings`: the owner's `StoreSettings` (read-only here),
- `usage`: `{products, uploadBytes}`, counted inside that store's tenant context, the same way `TenantExport` reads one store.

`GET /platform/tenants` adds `type` to each row. No new write endpoints.

### 4.2 Call budget

| Screen | First load | Per action |
|---|---|---|
| Store list | 1 (`GET /platform/tenants`) | 0 (search, chips and sort are local) |
| Onboard store | 0 | 1 (create) |
| Store workspace | 2 on Overview (store + recent audit), 1 on any other tab | 1 per save or status move |
| Audit log | 1 | 1 per filter change or page |

## 5. Store owner's Store settings

`/admin/settings/:tab?` (default `shipping`), tabs:

| Tab | Fields |
|---|---|
| Shipping & tax | shipping fee, free shipping above, default GST |
| Orders & coupons | largest flat coupon, minimum age, stuck-order days, order prefix |
| Catalogue | products per page, low-stock level |
| Invoice & contact | legal name, address, GSTIN, state, email, phone, grievance officer and email, logo URL |
| Region | time zone |

- Each tab saves only its own group (`PUT /store/settings` already keeps groups left out).
- Every field: placeholder "Default: n"; when the store has its own value, a **Custom** tag and a **Reset to default** link (clears the field).
- The header shows the store type: "Fashion store. Starting values were set when the store was created." `GET /store/settings` adds `type`.
- Unsaved changes prompt when switching tabs or leaving.
- The existing scroll container rule for admin pages applies (`flex:1; min-height:0; overflow-y:auto`).

### 5.1 Shared `Tabs` component

`components/admin/Tabs.tsx`, used by the workspace and Store settings:

- Router links (`NavLink`), so each tab has a URL and Back works.
- `role="tablist"` / `role="tab"` / `aria-selected`; Left/Right/Home/End move focus; visible focus ring.
- Scrolls horizontally at phone width; no new dependency.

## 6. Error handling

- API validation errors (400) show next to the field when the message names one (`field: reason`), else as a toast.
- 401 on any platform call signs out and returns to platform sign-in (as today).
- 404 for an unknown slug in the workspace shows "Store not found" with a link back to Stores.
- A failed usage count does not fail the store load: `usage` is omitted and Overview shows "Usage unavailable".

## 7. Security and audit

- All new platform reads stay behind the platform role; usage is counted per store only on the single-store endpoint.
- Isolation: store A's usage must never include store B's products or files (test).
- No secrets are returned (payment secret stays in the environment, as today).
- Audit as in §2; status moves and edits keep their current events. No new audit actions.

## 8. Testing (tests first)

Backend (Testcontainers):
- each type's preset is applied on create (features and settings); an explicit `features` in the request wins
- unknown type → 400; a store with no type reads as `GENERAL`
- a type change through `PUT` does not re-apply the preset
- `GET /platform/tenants/{id}` returns type, settings and usage; usage counts only that store's data
- `TENANT_CREATED` carries the type and the preset values; `GET /store/settings` returns the type

Frontend (Vitest):
- store list search and status chips; slug filled from name until edited
- Status menu shows only valid moves per status; Offboard needs the exact slug
- feature segmented control ↔ payload (default removes the key); MB/GB ↔ bytes
- `Tabs` keyboard behaviour; Reset to default clears to `null`

Call budget: there is no Playwright setup in the repo. Request counts from §4.2 are checked during the browser pass; adding Playwright
request-count tests is a separate task.

Browser pass: every new screen at 1280px and 375px, light and dark, scrolling to the last control of each page.

## 9. Rollout

- Branch `feat/multitenant-console` off `dev`; the old single-page `/platform` is replaced outright (only the operator uses it; no flag).
- No data migration (`type` null = `GENERAL`).
- Docs: ADR-030; `contracts.md` (create/update `type`, single-store `type/settings/usage`, `/store/settings` `type`, call budgets);
  `requirements.md`; admin help centre article on Store settings.

## 10. Out of scope

"Open as store" / impersonation; store types editable in the console; plans, tiers and billing; re-applying a preset; self-serve signup;
server-side paging of the store list.
