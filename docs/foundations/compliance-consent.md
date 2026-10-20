# Policies, Consent & Cookies

This file lists the legal pages 6Pack needs, every point where a user must be told something or must agree to
something, and how each consent is stored, versioned and withdrawn. It is an engineering spec, not legal advice.
Have a lawyer review the policy text before going live.

**Markers used below**
- 🔴 **Mandatory**: required by law or by Razorpay before going live.
- 🟡 **Conditional**: becomes mandatory once a trigger happens (EU customers, analytics tags, marketing email, and so on).
- 🟢 **Recommended**: good practice that lowers risk. Not strictly required.

## 1. Which rules apply

6Pack is a single Indian store that charges in INR through Razorpay, so Indian law is the baseline.

| Law / rule | What it requires from us | Marker |
|---|---|---|
| **Digital Personal Data Protection Act 2023 + DPDP Rules 2025** | A clear notice before collecting data. Consent that is free, specific, informed, unconditional and unambiguous, given by a clear affirmative action. Withdrawing consent must be as easy as giving it. We must be able to prove consent was given. Verifiable parental consent for anyone under **18**. Report breaches to the Board and to affected users. A published grievance contact. Most obligations start 18 months after the Rules were notified in Nov 2025, so by about **May 2027** (check the exact date). Build for it now. | 🔴 |
| **Consumer Protection (E-Commerce) Rules 2020** | Show the legal entity name, address and customer-care contact. Name a **Grievance Officer**, acknowledge complaints within 48 h and resolve them within 1 month. Publish return, refund, exchange, cancellation, delivery and payment terms. Show the full price breakup before payment. | 🔴 |
| **Guidelines on Dark Patterns 2023 (CCPA)** | No pre-ticked boxes, forced action, drip pricing, basket sneaking or confirm-shaming. Applies to the cookie banner, sign-up and checkout. | 🔴 |
| **IT Act s.43A + SPDI Rules 2011** | A privacy policy published on the site. Passwords count as sensitive personal data, so "reasonable security practices" are expected (already covered by bcrypt + TLS). | 🔴 |
| **Razorpay merchant onboarding** | Activation requires these pages on the live site: **Contact Us, Terms & Conditions, Privacy Policy, Cancellation & Refund Policy, Shipping & Delivery Policy**. | 🔴 |
| **GDPR / ePrivacy (EU), UK GDPR** | Opt-in cookie consent before any non-essential storage, a lawful basis per purpose, and data subject rights. Applies only if we target EU/UK customers. | 🟡 |

## 2. Policy documents needed

| # | Document | Marker | Route (proposed) | How the user accepts | Linked from |
|---|---|---|---|---|---|
| P1 | **Terms & Conditions** | 🔴 | `/legal/terms` | **Clickwrap**: an unticked checkbox at registration. At checkout, a line of text next to the Pay button. | Footer, register form, checkout |
| P2 | **Privacy Policy / Privacy Notice** (DPDP notice) | 🔴 | `/legal/privacy` | Notice shown before data is collected. Accepted with the same checkbox as P1, but listed as its own link. | Footer, register form, checkout, forgot-password |
| P3 | **Cancellation & Refund Policy** | 🔴 | `/legal/refunds` | Notice: shown before payment | Footer, cart, checkout |
| P4 | **Shipping & Delivery Policy** | 🔴 | `/legal/shipping` | Notice: shown before payment | Footer, shipping page |
| P5 | **Contact Us + Grievance Officer** | 🔴 | `/legal/contact` | Notice only | Footer, privacy policy |
| P6 | **Cookie & Storage Notice** | 🔴 (notice) / 🟡 (banner) | `/legal/cookies` | Notice today. Becomes an opt-in banner once non-essential storage exists (§5). | Footer, banner |
| P7 | **Marketing communications consent** | 🟡 (only if we send promotional email, SMS or WhatsApp) | covered in P2 | Separate unticked opt-in | Register form, account settings |
| P8 | **Admin acceptable-use & confidentiality** | 🟢 | `/legal/admin-aup` | Clickwrap on an admin's first login, and again whenever the version changes | Admin sidebar |
| P9 | **Data retention schedule** | 🟢 (internal) | this file, §7 | None. This is an internal document. | Referenced in P2 |

**What the Privacy Policy (P2) must cover:** what we collect (name, email, password hash, photo, gender, DOB,
shipping address, order history, IP and logs). The purpose of each item. Who we share it with: Razorpay for
payments, the courier for delivery, and hosting/infra providers. How long we keep it. User rights: access,
correction, erasure, nominating someone, and grievance. How to withdraw consent. The Grievance Officer's
contact. The right to complain to the Data Protection Board. Under DPDP, the notice must be available in
English or in any of the 22 Eighth Schedule languages, on request.

**Data minimisation:** registration currently *requires* `gender` and `dob`. DOB is justified because we need it
for the age check (§3, C2). Gender is only used by the admin dashboard charts, so **make it optional**, or else
state that purpose in P2 and take consent for it.

## 3. When to prompt: consent and notice points

| # | Moment / trigger | What to show | Mechanism | Marker | Blocks the action? | Record it? |
|---|---|---|---|---|---|---|
| C1 | **Registration** (`login.tsx`, register mode) | "I agree to the [Terms] and have read the [Privacy Policy]" | **Unticked checkbox, required**. The submit button stays disabled until it is ticked. | 🔴 | Yes | Yes: `terms`, `privacy` + version |
| C2 | **Registration: age check** | Work out age from `dob`. If under 18, show "You must be 18 or older to create an account". | Server-side check in `POST /user/register`, mirrored in the UI | 🔴 | Yes, reject | Yes: an `age_confirmed` flag |
| C3 | **Registration: marketing** | "Send me offers and updates by email" | **Separate unticked, optional checkbox** | 🟡 | No | Yes: `marketing_email` |
| C4 | **Login, when a policy changed materially** | A modal: "We've updated our Terms/Privacy Policy" with a summary of the change and links | Blocking modal with an **Accept** button and a **Log out** option. Re-prompt whenever the user's accepted version is older than the current *major* version. | 🔴 | Yes, until accepted | Yes: new version |
| C5 | **Login, when a policy changed in a minor way** | A dismissible banner: "Our Privacy Policy was updated on …" | Notice only | 🟢 | No | No |
| C6 | **First visit, before any non-essential storage** | Cookie banner (§5) | Opt-in banner with **Accept all / Reject all / Customise** given equal weight | 🟡 (today 🟢 notice only) | No (site stays usable) | Yes: `cookies.*` |
| C7 | **Cart → shipping page** | Links to Refund and Shipping policies. Full price breakup: subtotal, tax 18%, shipping, discount, total. | Notice. The price breakup already exists (FR5). | 🔴 | No | No |
| C8 | **Pay button** (`shipping.tsx`) | "By placing this order you agree to the [Terms] and [Refund Policy]. Payment is processed by Razorpay." | Text next to the button (browsewrap at the point of action). Clickwrap is stronger if wanted. | 🔴 | No | Yes: store `termsVersion` on the order |
| C9 | **Before loading Razorpay checkout.js** | Covered by C8. Razorpay sets its own cookies for fraud checks. List them in P6 as strictly necessary. | None needed | 🔴 (disclose) | No | No |
| C10 | **Profile photo upload** | "Optional. Shown on your account only." | Inline hint, covered by P2 | 🟢 | No | No |
| C11 | **Forgot password** | Link to Privacy Policy | Notice | 🟢 | No | No |
| C12 | **Admin first login / AUP version change** | Admin acceptable-use (P8) | Blocking clickwrap | 🟢 | Yes, for every `/api/v1/**` request made with an admin token, except `/user/me/**`, `/consent/**` and `/legal/**` | Yes: `admin_aup` |
| C13 | **Account settings → Privacy** (new page) | Current consents with toggles, cookie preferences, "Download my data", "Delete my account" | Self-service. Withdrawing must take as few steps as granting. | 🔴 | n/a | Yes, every change |
| C14 | **Footer, every page** | Terms · Privacy · Refunds · Shipping · Contact · Cookie settings | Links. **There is no footer today, so we need to add one.** | 🔴 | n/a | n/a |

**Rules that apply to every prompt (dark-pattern guidelines):**
- No checkbox is ticked by default.
- "Reject" is as visible as "Accept". No confirm-shaming copy such as "No, I don't like deals".
- Never bundle optional purposes (such as marketing) with required ones (such as Terms).
- Never block shopping because a user refused optional cookies or marketing.

## 4. Browser storage & cookie inventory

The app sets **no cookies today**. It uses `localStorage`, which ePrivacy and DPDP treat the same way as cookies.

| Key / cookie | Set by | Where | Purpose | Category | Lifetime | Consent needed? |
|---|---|---|---|---|---|---|
| `token` | us | localStorage (`redux/reducer/userReducer.ts`) | JWT session (24 h expiry, ADR-006) | Strictly necessary | Until logout or expiry | No, list it in P6 |
| `cart` | us | localStorage (`redux/reducer/cartReducer.ts`) | Cart items, coupon, shipping info (address, recipient name and phone) | Strictly necessary (the user asked for it) | Until checkout or cleared | No, list it in P6 |
| `consent` (new) | us | localStorage + server (§6) | Remembers the cookie-banner choice | Strictly necessary | 6 months, then re-prompt | No |
| Razorpay cookies | checkout.razorpay.com | 3rd-party, only on the payment step | Payment processing, fraud checks | Strictly necessary | Set by Razorpay | No, disclose in P6 |
| Google Fonts request | fonts.googleapis.com (`index.html`) | 3rd-party request, sends the visitor's IP to Google | Font delivery | Not necessary | n/a | 🟡 Under GDPR this needs consent. **Recommended: self-host the fonts** so the issue goes away. |
| *(future)* Google Analytics, Meta Pixel, Hotjar, etc. | 3rd-party | cookies | Analytics, marketing | Analytics / Marketing | varies | **Yes, opt-in. Load only after consent.** |

Note: `cart.shippingInfo` holds a home address in `localStorage`. It is cleared on sign-out, and P6 mentions it.

**Categories for the banner, once one is needed:**
1. **Strictly necessary**: always on, and the toggle is disabled.
2. **Functional / preferences**: for example a remembered theme or currency. Off by default.
3. **Analytics**: off by default.
4. **Marketing**: off by default.

## 5. Cookie banner behaviour (when it is needed)

- Show it on the first visit, and again if the stored choice is older than 6 months or the cookie-policy version changed.
- Use a bottom bar with no overlay. The site stays fully usable.
- Before a choice is made, load **nothing** from categories 2–4. Gate every non-essential script behind a single
  `hasConsent("analytics")` helper instead of adding `<script>` tags straight into `index.html`.
- "Customise" opens per-category toggles. "Cookie settings" in the footer reopens them at any time.
- When a category is withdrawn, stop the scripts and delete the cookies they set, as far as possible.
- A logged-in user's choice is also sent to the server (§6) so it follows them across devices.
  Anonymous choices stay in `localStorage` only.

## 6. How consents are stored and handled

### Data model: new collection `consents` (append-only)
```
{ _id, userId?: string, anonId?: string,         // anonId for cookie choices made before login
  purpose: "terms"|"privacy"|"marketing_email"|"cookies.analytics"|"cookies.marketing"|"cookies.functional"|"admin_aup"|"age_confirmed",
  granted: boolean,
  policyVersion: "2026-11-01" ,                   // the version of the document the user saw
  source: "register"|"login_reprompt"|"banner"|"settings"|"checkout",
  ipHash: string, userAgent: string,              // proof of consent; the IP is hashed, not stored raw
  createdAt }
```
- **Append-only**: never update a row. Withdrawing consent inserts a new row with `granted:false`. A user's current
  state is the latest row for each purpose. Under DPDP the business has to prove consent was given, and this log is that proof.
- `GET /consent/me` returns `pending`: the required policies whose newest record isn't a grant of the
  current version. The SPA re-prompts (C4) while it's non-empty. No denormalised copy is kept on `users`.
- Accounts created before this existed have no records, so they are prompted once on their next visit.
- Store `termsVersion` on each `orders` document (C8). Checkout returns 403 until the current Terms are accepted.

### Policy versioning
- Policy text lives in `ecommerce-frontend/src/legal/policies.ts`, each with an `updated` date shown on the page.
- The version users must accept is the backend's `LEGAL_TERMS_VERSION` / `LEGAL_PRIVACY_VERSION` / `LEGAL_ADMIN_AUP_VERSION`, bumped
  only for a major change. `GET /api/v1/legal/versions` (🔓) returns them.
- **Major change** (new purpose, new data-sharing recipient, changed refund terms): re-prompt with C4, and
  email users 🟢 at least 7 days before it takes effect.
- **Minor change** (typos, contact details): notice banner C5 only.

### API additions (built)
| Method & path | Auth | Purpose |
|---|---|---|
| `GET /legal/versions` | 🔓 | Current policy versions |
| `GET /admin/audit` | 🛡 | Search the audit log (§10) |
| `POST /consent` | 👤 | Record a consent change, `{purpose, granted, source}`. The server stamps the version, hashed IP and user agent |
| `GET /consent/me` | 👤 | The user's current consent state, `pending` list and history |
| `DELETE /user/me` | 👤 | Delete my own account (right to erasure). Needs the password; blocked while orders are undelivered |
| `POST /register` | 🔓 | `acceptTerms` (must be true) and `marketingOptIn`. Rejects anyone under 18 |

"Download my data" (right to access) is assembled in the browser from `/user/me`, `/order/my` and
`/consent/me`, so no new endpoint was needed. Anonymous cookie choices (`anonId`) aren't stored because there
is no banner yet. Full request and response shapes are in `contracts.md`.

### User rights & grievances
- **Access / export**: return JSON with the profile, orders and consent history.
- **Correction**: a profile edit page. Only admins can edit users today.
- **Erasure**: delete or anonymise the user. Keep order records with personal fields removed for the period tax
  law requires (GST/Income-tax records: about 8 years). Write down that exception in P2.
- **Grievance**: a contact form or email that goes to the Grievance Officer. Acknowledge within 48 h and resolve
  within 1 month (E-Commerce Rules). Log each ticket.
- **Breach**: notify affected users and the Data Protection Board without delay. Send a detailed report to the
  Board within 72 h (DPDP Rules). Hook this into the existing alert rules and incident runbook (ADR-012).

## 7. Retention schedule

| Data | Keep for | Then |
|---|---|---|
| Platform administrator accounts (`platform_admins`: name, email, password hash) | While the person operates the platform. They are not customers of any store and have no access to store data. | Deleted when the role ends |
| A store's own data (customers, orders, consents, audit log, uploads) | Each tenant is its own controller of its customers' data. A tenant's data is kept apart from every other tenant's and is erased per account like before (`DELETE /user/me`). Offboarding a whole tenant (export then delete) is not built yet and needs a procedure before the first outside tenant. | Per tenant |
| Per-customer coupon usage counters (`coupon_redemptions`) | While the account exists. Deleted on `UserDeletedEvent`. Each coupon's total `usedCount` is anonymous and stays. | Deleted with the account |
| Order contact snapshot, billing address, recipient name/phone, delivery notes | Until the account is deleted, then erased from its orders by `OrderAccountPolicy`. Orders keep amounts, order number and status history for tax. | Erased with the account |
| Phone, locale, saved addresses, last-login time | Same as the account profile. They are stored on the user record, erased with it (`DELETE /user/me`), included in the data export, and never used for marketing. | Deleted with the account |
| Account profile | While the account is active. 🟢 Delete after 3 years of inactivity, with a 48 h warning email (mirrors DPDP Rules for large platforms). | Delete or anonymise |
| Orders, payment ids | ~8 years (tax / accounting) | Anonymise the user link |
| Consent records | Account lifetime + 3 years (proof). On erasure each row gets `expireAt = now + 3 years` and a TTL index deletes it (`ConsentIntegrationTest` checks this) | Deleted automatically |
| Server and access logs | **At least 1 year** (DPDP Rules), then delete | Loki retention config |
| `payment_intents`, `password_reset_tokens`, `email_verification_tokens` | Already TTL'd (24 h / 15 min / 24 h) | Already handled |
| Audit log (`audit_log`) | 365 days, then deleted by a TTL index. The period is fixed in `AuditDocument` (`@Indexed(expireAfter = "365d")`); changing it needs a code change plus dropping the existing `at` index (`db.audit_log.dropIndex("at")`), because MongoDB refuses to change an index's options in place. Holds actor ids, record ids, the names of changed fields, non-personal values (prices, statuses, roles), a salted hash of the IP and the browser user-agent; personal values (name, email, phone, address) are never stored. | Deleted automatically |
| Attempted-login email hash (`subjectHash`) | Same as the audit log. It is a salted hash, so it is pseudonymous. It can't be reversed without the salt, but that holds only when `CONSENT_IP_SALT` is set. The salt defaults to empty, in which case the hash is plain SHA-256 and the backend logs a startup warning; set it in every real environment. | Deleted automatically |

## 8. Implementation options

**Option A, recommended: build our own minimal consent layer now, and add a banner only when it is needed.**
Self-host the fonts. Add the footer, the policy pages, the C1–C4 registration and re-prompt flow, the `consents`
collection, and the privacy settings page. Skip the cookie banner because everything stored today is strictly
necessary. A short notice in P6 is enough. Build the `hasConsent()` gate now so that adding analytics later is a small change.
*Why:* it covers every 🔴 item with the least UI clutter, and the site has no third-party trackers to gate.

**Option B: build our own consent layer *plus* a full cookie banner now.** This is future-proof, and it is the
safest choice if EU sales are likely. The cost is a banner that, today, has nothing optional to turn off.

**Option C: use a third-party CMP** (Cookiebot, OneTrust, or the open-source Klaro/Orestbida CookieConsent).
It scans tags automatically and handles the IAB TCF standard. Worth it once there are many marketing tags or
EU/UK traffic. It adds cost, adds a script, and itself needs disclosing.

I'll go with **Option A** unless you object.

## 9. Implementation checklist (Option A, ADR-014)

- [x] 🔴 Draft P1–P6 (`ecommerce-frontend/src/legal/policies.ts`) at `/legal/:slug`, plus a footer with the C14 links.
- [x] 🔴 Register form: required terms checkbox (C1), optional marketing checkbox (C3), gender optional, under-18s blocked (C2) in the UI and in `POST /user/register`.
- [x] 🔴 Backend `consent` module: `consents` collection, `GET /legal/versions`, `POST /consent`, `GET /consent/me`.
- [x] 🔴 Blocking re-prompt dialog (`components/consentPrompt.tsx`) while `pending` is non-empty (C4). Checkout is also refused server-side.
- [x] 🔴 Checkout: policy links on the cart, the "By placing this order…" line on the shipping page (C7, C8). `termsVersion` stored on the order.
- [x] 🔴 Account → Privacy & data (`/account/privacy`): marketing toggle, consent history, JSON export, account deletion (C13).
- [ ] 🔴 **Fill in `BUSINESS` in `policies.ts`** (legal entity, address, contact, Grievance Officer) and the `[bracketed]` values in the policies, then have a lawyer review the text. This blocks launch and Razorpay activation.
- [ ] 🔴 **Set `CONSENT_IP_SALT`** in each environment's sealed secret (`k8s/scripts/seal-backend-secrets.sh` now requires it).
- [ ] 🔴 Profile correction (users editing their own name and email). Until then it's handled by request to the Grievance Officer.
- [x] 🟡 Fonts self-hosted (`@fontsource-variable/*`). The Google Fonts links are gone and the CSP no longer allows them.
- [x] 🟢 `cart.shippingInfo` cleared on sign-out.
- [x] 🟢 `hasConsent(category)` helper (`utils/consent.ts`) as the gate for future third-party scripts.
- [x] 🟢 Admin acceptable-use clickwrap (P8 / C12): `admin_aup` purpose, accepted on first login and re-asked when `LEGAL_ADMIN_AUP_VERSION` changes. Admin endpoints return 403 until it is accepted.
- [ ] 🟢 Set Loki log retention to ≥ 1 year.
- [x] Tests (`ConsentIntegrationTest`): registration rejected without `acceptTerms` or under 18; consent recorded with a hashed IP; legacy users prompted and blocked from checkout until they accept; marketing withdrawal is append-only; terms can't be withdrawn; deletion needs the password, waits for delivery (admins too) and anonymises orders; admins can't self-delete.

## 10. Audit trail

Every change an admin or customer makes to products, categories, coupons, orders and users, and every sign-in
attempt, password reset and consent change after registration (the `consents` ledger holds the registration consents), is recorded in the append-only `audit_log` collection (ADR-019).
Admins read it at **Admin → Audit log**. The log never stores personal values: personal fields (name, phone,
and so on) are recorded as "changed" only, emails are never stored, and the IP is a salted hash. It does keep
actor and record ids, changed field names, non-personal values (prices, statuses, roles), the browser user-agent and,
for failed logins and reset requests, a salted hash of the attempted email. Because it holds no personal values,
erasing an account needs no change to the log. "Who failed to log in as X" is answerable only for existing accounts
(through the account's record id): the attempted-email hash is not returned by the API and cannot be searched. Reads of personal data are not logged.
The Admin Acceptable Use Agreement tells admins that their activity is recorded.

## Open questions
- What is the legal entity name, registered address and Grievance Officer? This blocks P5 and Razorpay activation.
- Will we ship or sell to EU/UK customers? If yes, move to Option B or C and add GDPR wording to P2.
- Should under-18s be blocked outright (recommended) or allowed with verifiable parental consent (much more work)?
- Will we send promotional email or SMS? This decides whether C3 / P7 is needed.
