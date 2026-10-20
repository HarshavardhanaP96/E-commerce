# Razorpay integration

How payments work in the Spring Boot backend, what you configure, and how to test it. The decision behind the
two-step flow is ADR-009 in [`foundations/architecture.md`](foundations/architecture.md); the endpoint shapes are
in [`foundations/contracts.md`](foundations/contracts.md).

## Gateways

`app.payment.provider` (env `PAYMENT_PROVIDER`) picks exactly one implementation of the `PaymentGateway` port:

| Value | Where | What it does |
|---|---|---|
| `fake` (default) | local, tests | No network. Every order "pays" when the client sends signature `fake`. Refuses to start under the `prod` profile. |
| `razorpay` | test-env, prod-env | Razorpay REST API over plain HTTP (no SDK), with 5 s connect and 10 s read timeouts. |

The `prod` profile defaults the provider to `razorpay`. The class is `payment/infra/razorpay/RazorpayGateway.java`.

## Configuration

| Env var | Purpose |
|---|---|
| `PAYMENT_PROVIDER` | `fake` or `razorpay` |
| `RAZORPAY_KEY_ID` | Public key id. The backend sends it to the browser to open Checkout. |
| `RAZORPAY_KEY_SECRET` | Secret. Used for API calls and signature checks; never sent to the browser. |
| `RAZORPAY_BASE_URL` | Defaults to `https://api.razorpay.com`. Override only for a mock server. |

With `razorpay` selected the app refuses to start if either key is blank. In Kubernetes the keys live in the
sealed `backend-secrets` (see [`../k8s/README.md`](../k8s/README.md)); locally put them in `.env`. Use
`rzp_test_…` keys everywhere except prod.

## Payment flow

The client never sends prices, totals or a user id. The server prices the cart from the catalogue.

1. **`POST /api/v1/order/checkout`** with the cart lines and an optional coupon. The server prices the cart,
   creates a Razorpay order for the total (in paise) and stores a *payment intent* (the priced cart) for 24 h.
   Response: `{provider, key, providerOrderId, amount, currency, subtotal, tax, shippingCharges, discount, total}`.
2. **Razorpay Checkout** opens in the browser (`ecommerce-frontend/src/utils/razorpay.ts` loads the script) and
   returns `razorpay_payment_id` and `razorpay_signature`.
3. **`POST /api/v1/order/new`** with `{providerOrderId, paymentId, signature, shippingInfo, billingInfo?, notes?}`.
   The server then:
   1. verifies the signature, `HMAC_SHA256(keySecret, orderId + "|" + paymentId)`, in constant time;
   2. consumes the intent exactly once (a repeat gets 409);
   3. reserves stock, redeems the coupon, and saves the order with `payment.status = PAID`;
   4. asks Razorpay how the customer paid (`GET /v1/payments/{id}`) and stores `payment.method`
      (`upi`, `card`, `netbanking`, …). If that lookup fails the order is still placed and `method` stays empty.

If stock ran out between payment and confirmation, the intent is released so the customer can retry, a
`sixpack.checkout.failed{reason="paid_but_unfulfilled"}` metric is counted and an ERROR containing
`paid_but_unfulfilled` is logged with the payment id. Refund those by hand (below).

There are **no webhooks**: the order is created from the browser's confirmation call. A customer who pays and
then closes the tab before step 3 has paid without an order; reconcile those from the Razorpay dashboard. Adding
the `payment.captured` webhook is the way to close that gap.

## Refunds

Admins refund from the order page (Transactions → an order → Payment) or with the API:

```
POST /api/v1/order/{id}/refund        (admin)
{ "amount": 100.00, "reason": "Mug arrived chipped" }     // amount optional: omit it to refund what is left
```

- Partial refunds are allowed until the order total is reached. `payment.status` moves
  `PAID → PARTIALLY_REFUNDED → REFUNDED`, and `payment.refunds[]` keeps each one (amount, reason, provider state, time).
- Money goes back through the provider that took it. An order paid through another provider than the active one
  is refused with 409.
- The amount is set aside atomically before Razorpay is called, so two admins cannot refund the same money. If
  Razorpay refuses or is unreachable the amount is released, `payment.failureReason` records why (admins only see
  it), and the admin can retry. The retry sends the same `X-Refund-Idempotency` key, so a request that timed out
  but went through is not paid twice.
- `reason` is shown to the customer and is deliberately kept out of the audit log. The audit entry
  `ORDER_REFUNDED` records only the refunded total.
- A refund does not change the order status, restock items, or reduce the dashboard's revenue, which is gross.

## Invoices

`GET /api/v1/order/{id}/invoice` (owner or admin) returns the invoice data; the SPA renders it at
`/order/:id/invoice` and the browser's "Print / Save as PDF" makes the PDF. It is derived from the order each time and
shows CGST+SGST when the buyer's state equals `INVOICE_SELLER_STATE`, else IGST. Seller details come from
the `INVOICE_SELLER_*` variables in [`.env.example`](../.env.example). Invoice numbers reuse the order number
(`ORD-…` becomes `INV-…`); they are unique but not a gapless series, so check with your accountant if GST rules
need one.

## Testing

- Automated: `RazorpayGatewayTest` runs the gateway against a mock HTTP server; `HmacTest` covers the signature;
  `PaymentDetailsAndRefundIntegrationTest` covers method, refunds and failure handling with the fake gateway.
- By hand with Razorpay test keys: set `PAYMENT_PROVIDER=razorpay` and the two keys, place an order with a
  [Razorpay test card or UPI id](https://razorpay.com/docs/payments/payments/test-card-details/), then refund it.
  Test-mode refunds complete instantly in the dashboard.

## Going live

1. Finish Razorpay KYC and generate live keys.
2. Re-seal `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` for prod.
3. Publish the five pages Razorpay requires (Contact, Terms, Privacy, Cancellation & Refund, Shipping). They exist
   at `/legal/*`; see [`foundations/compliance-consent.md`](foundations/compliance-consent.md).
4. Place one real low-value order and refund it.
