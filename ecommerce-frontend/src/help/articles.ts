/**
 * Help centre content. Answers restate the store policies in plain language (see legal/policies.ts), so when a
 * policy changes — shipping charge, return window, refund time — update the matching answer here too.
 * `link` points to the full policy for anyone who wants the detail.
 */

export type HelpCategory = "orders" | "payments" | "shipping" | "returns" | "account";

export const HELP_CATEGORIES: { id: HelpCategory; label: string }[] = [
  { id: "orders", label: "Orders" },
  { id: "payments", label: "Payments" },
  { id: "shipping", label: "Shipping" },
  { id: "returns", label: "Returns & refunds" },
  { id: "account", label: "Account" },
];

export type HelpArticle = {
  id: string;
  category: HelpCategory;
  question: string;
  answer: string[];
  link?: { to: string; label: string };
};

export const HELP_ARTICLES: HelpArticle[] = [
  {
    id: "place-order",
    category: "orders",
    question: "How do I place an order?",
    answer: [
      "Add products to your cart, open the cart and choose Checkout. Enter your shipping address, then pay securely. You'll get a confirmation once the payment succeeds.",
    ],
  },
  {
    id: "track-order",
    category: "orders",
    question: "How do I track my order?",
    answer: [
      "Open My orders from the account menu and select an order. The status moves from Processing to Shipped to Delivered.",
    ],
    link: { to: "/orders", label: "Go to My orders" },
  },
  {
    id: "change-address",
    category: "orders",
    question: "Can I change my shipping address after ordering?",
    answer: [
      "Contact us with your order ID as soon as possible. We can usually update the address until the order is shipped.",
    ],
    link: { to: "/legal/contact", label: "Contact us" },
  },
  {
    id: "coupon",
    category: "orders",
    question: "How do I use a coupon?",
    answer: [
      "Enter the code in the Coupon code box in your cart. A coupon gives a flat discount, and the new total is shown before you pay. Coupons can't be exchanged for cash.",
    ],
  },
  {
    id: "out-of-stock",
    category: "orders",
    question: "What if an item goes out of stock after I've paid?",
    answer: [
      "We cancel that item and refund it in full automatically. The refund goes back to your original payment method.",
    ],
  },
  {
    id: "payment-methods",
    category: "payments",
    question: "How do I pay?",
    answer: [
      "Payments are processed by Razorpay. The payment options available to you are shown at checkout. We never see or store your card, UPI or bank details.",
    ],
  },
  {
    id: "gst",
    category: "payments",
    question: "Are prices inclusive of tax?",
    answer: [
      "Prices are in Indian Rupees. Before you pay, the cart shows the subtotal, GST, shipping, any discount and the total.",
    ],
  },
  {
    id: "payment-failed",
    category: "payments",
    question: "My payment failed but money was deducted. What now?",
    answer: [
      "Don't pay again straight away. A failed payment is normally reversed by your bank within 5–7 business days. If it isn't, contact us with the payment reference and we'll help.",
    ],
    link: { to: "/legal/contact", label: "Contact us" },
  },
  {
    id: "shipping-cost",
    category: "shipping",
    question: "How much does shipping cost?",
    answer: [
      "Shipping is free on orders over ₹1,000. Otherwise there is a flat ₹200 charge, shown before you pay. We deliver within India.",
    ],
    link: { to: "/legal/shipping", label: "Shipping & Delivery Policy" },
  },
  {
    id: "delivery-time",
    category: "shipping",
    question: "How long will delivery take?",
    answer: [
      "Orders are usually dispatched within a couple of business days and delivered within 3–7 business days, depending on your PIN code.",
    ],
    link: { to: "/legal/shipping", label: "Shipping & Delivery Policy" },
  },
  {
    id: "not-arrived",
    category: "shipping",
    question: "My order hasn't arrived.",
    answer: [
      "If it hasn't arrived within 10 business days of shipping, contact us. We'll trace it, or send a replacement or a refund.",
    ],
    link: { to: "/legal/contact", label: "Contact us" },
  },
  {
    id: "cancel",
    category: "returns",
    question: "Can I cancel my order?",
    answer: [
      "Yes, free of charge until it is shipped. Contact us with your order ID.",
    ],
    link: { to: "/legal/refunds", label: "Cancellation & Refund Policy" },
  },
  {
    id: "return",
    category: "returns",
    question: "How do I return an item?",
    answer: [
      "Most items can be returned within 7 days of delivery if they are unused and in their original packaging. If an item arrives damaged, defective or wrong, report it within 48 hours of delivery with photos and we'll replace it or take it back at no cost.",
    ],
    link: { to: "/legal/refunds", label: "Cancellation & Refund Policy" },
  },
  {
    id: "refund-time",
    category: "returns",
    question: "When will I get my refund?",
    answer: [
      "Approved refunds go back to your original payment method within 5–7 business days. Your bank may take a little longer to show the money.",
    ],
  },
  {
    id: "forgot-password",
    category: "account",
    question: "I forgot my password.",
    answer: [
      "On the sign-in page choose Forgot password and enter your email. We'll send you a link to set a new one.",
    ],
    link: { to: "/forgot-password", label: "Reset my password" },
  },
  {
    id: "marketing-emails",
    category: "account",
    question: "How do I stop marketing emails?",
    answer: [
      "Open Privacy & data from the account menu and untick Email me offers and updates. Order and account emails (receipts, delivery updates, password resets) are always sent.",
    ],
    link: { to: "/account/privacy", label: "Privacy & data" },
  },
  {
    id: "download-data",
    category: "account",
    question: "How do I download or delete my data?",
    answer: [
      "Both are in Privacy & data. Download my data gives you a file with your profile, orders and consent history. Delete your account removes your profile; order records are kept for tax purposes with your street address and PIN code removed.",
    ],
    link: { to: "/account/privacy", label: "Privacy & data" },
  },
];
