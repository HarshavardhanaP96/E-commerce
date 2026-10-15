# Razorpay Integration Guide

## Overview
This e-commerce platform uses **Razorpay** as the payment gateway, which is one of the most reliable and widely-used payment solutions in India.

## Why Razorpay?

### Advantages for Indian Market:
- ✅ **100+ Payment Methods**: UPI, Cards, NetBanking, Wallets, EMI
- ✅ **Instant Settlements**: Get your money faster
- ✅ **Lower Transaction Fees**: Competitive pricing for Indian businesses
- ✅ **INR Support**: Native support for Indian Rupee
- ✅ **RBI Compliant**: Fully compliant with Indian regulations
- ✅ **Better Success Rates**: Optimized for Indian payment methods
- ✅ **UPI Integration**: Seamless UPI payments (PhonePe, Google Pay, Paytm)
- ✅ **Easy Integration**: Simple API and excellent documentation

## Setup Instructions

### 1. Create Razorpay Account

1. Visit [Razorpay Dashboard](https://dashboard.razorpay.com/signup)
2. Sign up with your business details
3. Complete KYC verification (required for live mode)

### 2. Get API Keys

#### Test Mode (for development):
1. Go to [Dashboard → Settings → API Keys](https://dashboard.razorpay.com/app/keys)
2. Generate **Test Keys**
3. Copy `Key ID` and `Key Secret`

#### Live Mode (for production):
1. Complete KYC verification
2. Activate your account
3. Generate **Live Keys**
4. Copy `Key ID` and `Key Secret`

### 3. Configure Environment Variables

Update your `.env` file:

```env
# Test Mode
RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxxx
RAZORPAY_KEY_SECRET=your_test_secret_key

# Live Mode (after KYC)
# RAZORPAY_KEY_ID=rzp_live_xxxxxxxxxxxxx
# RAZORPAY_KEY_SECRET=your_live_secret_key
```

## Payment Flow

### Backend Flow:

1. **Create Order**
   ```
   POST /api/v1/payment/create-order
   Body: { amount: 1000 }
   Response: { orderId, amount, currency, key }
   ```

2. **Frontend Integration** (Razorpay Checkout)
   - Use the order details to open Razorpay checkout
   - Customer completes payment
   - Razorpay returns payment details

3. **Verify Payment**
   ```
   POST /api/v1/payment/verify
   Body: {
     razorpay_order_id,
     razorpay_payment_id,
     razorpay_signature
   }
   ```

### Security:
- Payment signature is verified using HMAC SHA256
- Prevents payment tampering
- Server-side validation ensures authenticity

## API Endpoints

### Create Razorpay Order
```http
POST /api/v1/payment/create-order
Content-Type: application/json

{
  "amount": 1000
}
```

**Response:**
```json
{
  "success": true,
  "order": {
    "id": "order_xxxxxxxxxxxxx",
    "amount": 100000,
    "currency": "INR",
    "receipt": "receipt_1234567890"
  },
  "key": "rzp_test_xxxxxxxxxxxxx"
}
```

### Verify Payment
```http
POST /api/v1/payment/verify
Content-Type: application/json

{
  "razorpay_order_id": "order_xxxxxxxxxxxxx",
  "razorpay_payment_id": "pay_xxxxxxxxxxxxx",
  "razorpay_signature": "signature_string"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Payment verified successfully"
}
```

## Frontend Integration

### Install Razorpay SDK
```bash
npm install razorpay
```

### Example React Integration

```javascript
const handlePayment = async (amount) => {
  try {
    // 1. Create order on backend
    const { data } = await axios.post('/api/v1/payment/create-order', {
      amount: amount
    });

    // 2. Configure Razorpay options
    const options = {
      key: data.key,
      amount: data.order.amount,
      currency: data.order.currency,
      name: 'Your Store Name',
      description: 'Purchase Description',
      order_id: data.order.id,
      handler: async function (response) {
        // 3. Verify payment on backend
        const verifyResponse = await axios.post('/api/v1/payment/verify', {
          razorpay_order_id: response.razorpay_order_id,
          razorpay_payment_id: response.razorpay_payment_id,
          razorpay_signature: response.razorpay_signature
        });

        if (verifyResponse.data.success) {
          alert('Payment Successful!');
          // Create order in your system
        }
      },
      prefill: {
        name: 'Customer Name',
        email: 'customer@example.com',
        contact: '9999999999'
      },
      theme: {
        color: '#3399cc'
      }
    };

    // 4. Open Razorpay checkout
    const rzp = new window.Razorpay(options);
    rzp.open();
  } catch (error) {
    console.error('Payment failed:', error);
  }
};
```

### Add Razorpay Script to HTML
```html
<script src="https://checkout.razorpay.com/v1/checkout.js"></script>
```

## Testing

### Test Cards (Test Mode Only)

#### Successful Payment:
- **Card Number**: 4111 1111 1111 1111
- **CVV**: Any 3 digits
- **Expiry**: Any future date

#### Failed Payment:
- **Card Number**: 4111 1111 1111 1234
- **CVV**: Any 3 digits
- **Expiry**: Any future date

### Test UPI:
- **UPI ID**: success@razorpay
- **Status**: Will show success

### Test Wallets:
- Use test credentials provided in Razorpay dashboard

## Webhook Integration (Optional)

For production, set up webhooks to handle payment events:

1. Go to Dashboard → Settings → Webhooks
2. Add webhook URL: `https://yourdomain.com/api/v1/payment/webhook`
3. Select events: `payment.captured`, `payment.failed`
4. Implement webhook handler in your backend

## Transaction Fees

### Razorpay Pricing (India):
- **Domestic Cards**: 2% per transaction
- **International Cards**: 3% per transaction
- **UPI**: 0% (free during promotional period)
- **Net Banking**: 2% per transaction
- **Wallets**: 2% per transaction

*Note: Fees may vary. Check [Razorpay Pricing](https://razorpay.com/pricing/) for latest rates.*

## Going Live

### Checklist:
- [ ] Complete KYC verification
- [ ] Add business bank account
- [ ] Update to live API keys
- [ ] Test in live mode with small amount
- [ ] Set up webhooks
- [ ] Configure settlement schedule
- [ ] Enable required payment methods

### Important Notes:
1. **Never expose** `RAZORPAY_KEY_SECRET` to frontend
2. **Always verify** payment signature on backend
3. **Use HTTPS** in production
4. **Store** payment details securely
5. **Handle** payment failures gracefully

## Support

- **Documentation**: https://razorpay.com/docs/
- **API Reference**: https://razorpay.com/docs/api/
- **Support**: support@razorpay.com
- **Dashboard**: https://dashboard.razorpay.com/

## Migration from Stripe

If migrating from Stripe:

| Stripe | Razorpay |
|--------|----------|
| `stripe.paymentIntents.create()` | `razorpay.orders.create()` |
| `client_secret` | `order_id` |
| Payment Intent | Order |
| Confirm Payment | Verify Signature |

## Troubleshooting

### Common Issues:

1. **"Key ID or Key Secret is incorrect"**
   - Verify keys in `.env` file
   - Ensure no extra spaces
   - Check if using test/live keys correctly

2. **"Payment verification failed"**
   - Check if signature verification logic is correct
   - Ensure `RAZORPAY_KEY_SECRET` is correct
   - Verify the order_id and payment_id match

3. **"Amount mismatch"**
   - Razorpay expects amount in **paise** (multiply by 100)
   - Example: ₹100 = 10000 paise

## Example: Complete Payment Flow

```javascript
// Backend: Create Order
app.post('/api/v1/payment/create-order', async (req, res) => {
  const { amount } = req.body;
  const order = await razorpay.orders.create({
    amount: amount * 100, // Convert to paise
    currency: 'INR',
    receipt: `receipt_${Date.now()}`
  });
  res.json({ success: true, order, key: process.env.RAZORPAY_KEY_ID });
});

// Backend: Verify Payment
app.post('/api/v1/payment/verify', async (req, res) => {
  const { razorpay_order_id, razorpay_payment_id, razorpay_signature } = req.body;
  
  const body = razorpay_order_id + "|" + razorpay_payment_id;
  const expectedSignature = crypto
    .createHmac("sha256", process.env.RAZORPAY_KEY_SECRET)
    .update(body)
    .digest("hex");
  
  if (expectedSignature === razorpay_signature) {
    // Payment is verified
    res.json({ success: true, message: "Payment verified" });
  } else {
    res.status(400).json({ success: false, message: "Invalid signature" });
  }
});
```

---

**Happy Coding! 🚀**
