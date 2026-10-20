/**
 * Store policies (docs/foundations/compliance-consent.md §2). Draft text: have it reviewed by a lawyer before
 * launch, and fill in BUSINESS below — Razorpay won't activate the account without these details.
 *
 * Changing a policy: edit the text and its `updated` date. If the change is material (new purpose, new
 * recipient, changed refund terms), also bump LEGAL_TERMS_VERSION / LEGAL_PRIVACY_VERSION (or LEGAL_ADMIN_AUP_VERSION for the admin agreement) on the backend so
 * every user is asked to accept again.
 */

export const BUSINESS = {
  legalName: "[Legal entity name]",
  address: "[Registered address]",
  email: "[support@your-domain]",
  phone: "[Customer care phone]",
  grievanceOfficer: "[Grievance Officer name]",
  grievanceEmail: "[grievance@your-domain]",
};

export type PolicySlug =
  | "terms"
  | "privacy"
  | "refunds"
  | "shipping"
  | "contact"
  | "cookies"
  | "admin-aup";

export type Policy = {
  slug: PolicySlug;
  title: string;
  updated: string;
  intro: string;
  sections: { heading: string; body: string[] }[];
};

/** Builds the policy text for a store's business details. */
const build = (b: typeof BUSINESS): Record<PolicySlug, Policy> => ({
  terms: {
    slug: "terms",
    title: "Terms & Conditions",
    updated: "2026-10-16",
    intro: `These terms apply when you use 6Pack, run by ${b.legalName} ("we", "us"). By creating an account or placing an order you agree to them.`,
    sections: [
      {
        heading: "Your account",
        body: [
          "You must be 18 or older to create an account.",
          "Keep your password secret. You are responsible for what happens under your account. Tell us straight away if you think someone else has used it.",
          "You can delete your account at any time from Account → Privacy, once your orders have been delivered.",
        ],
      },
      {
        heading: "Orders and prices",
        body: [
          "Prices are in Indian Rupees and include the GST shown at checkout. Before you pay, you will see the subtotal, tax, shipping, any discount and the total.",
          "An order is accepted when payment succeeds and we send you an order confirmation. If an item turns out to be out of stock after you have paid, we cancel that item and refund it in full.",
          "Coupons give a flat discount and can't be exchanged for cash.",
        ],
      },
      {
        heading: "Payments",
        body: [
          "Payments are processed by Razorpay. We never see or store your card, UPI or bank details. We only keep the payment and order reference numbers.",
        ],
      },
      {
        heading: "Returns, refunds and delivery",
        body: [
          "Returns and refunds follow our Cancellation & Refund Policy. Delivery follows our Shipping & Delivery Policy. Both are part of these terms.",
        ],
      },
      {
        heading: "Acceptable use",
        body: [
          "Don't misuse the site. That includes trying to get around security, scraping it at scale, placing fraudulent orders or uploading unlawful content. We may suspend accounts that do.",
        ],
      },
      {
        heading: "Liability",
        body: [
          "Nothing in these terms limits your rights under the Consumer Protection Act, 2019. Otherwise, our liability for an order is limited to the amount you paid for it.",
        ],
      },
      {
        heading: "Changes and disputes",
        body: [
          "If we change these terms in a material way, we will ask you to accept the new version the next time you sign in.",
          "These terms are governed by the laws of India. Please contact our Grievance Officer first (see Contact Us). Courts at [city] have jurisdiction.",
        ],
      },
    ],
  },

  privacy: {
    slug: "privacy",
    title: "Privacy Policy",
    updated: "2026-10-16",
    intro: `This notice explains what personal data ${b.legalName} collects, why, and the rights you have under the Digital Personal Data Protection Act, 2023. This notice is available in English or, on request, in any language listed in the Eighth Schedule to the Constitution.`,
    sections: [
      {
        heading: "What we collect and why",
        body: [
          "Account: your name, email, password (stored only as a one-way hash) and date of birth. We use these to run your account, and your date of birth to confirm you are 18 or older.",
          "Optional: your gender, used only for anonymous store statistics, and a profile photo shown on your account.",
          "Orders: delivery address, items, amounts and payment reference numbers, used to deliver your order, handle returns and keep tax records.",
          "Technical: your IP address, browser type and server logs, used for security, fraud prevention and fixing problems. When you accept a policy we keep a hashed (not readable) version of your IP and your browser type as proof.",
          "Marketing emails: sent only if you opt in. You can opt out at any time.",
        ],
      },
      {
        heading: "Who we share it with",
        body: [
          "Razorpay, to process payments. Our courier partners, who receive your name and delivery address to deliver orders. Our hosting and infrastructure providers, who store data on our behalf.",
          "We don't sell your personal data. We share it with authorities only when the law requires it.",
        ],
      },
      {
        heading: "How long we keep it",
        body: [
          "Account data: until you delete your account.",
          "Order and payment records: for as long as tax law requires (about 8 years). We remove your street address and PIN code once your account is deleted.",
          "Consent records: for the life of your account plus 3 years, as proof.",
          "Server logs: at least 1 year, then deleted.",
          "Audit log: a record of changes to the store's data and of sign-in attempts is kept for 1 year. It holds ids, the names of the fields that changed, hashed network details and the browser type, not your name, email, phone number or address.",
        ],
      },
      {
        heading: "Your rights",
        body: [
          "Access: download a copy of your data from Account → Privacy.",
          "Correction: ask us to correct inaccurate data.",
          "Erasure: delete your account from Account → Privacy once your orders have been delivered.",
          "Withdraw consent: turn marketing emails off at any time. Withdrawing consent doesn't affect anything we did before.",
          "Nominate: you may nominate someone to exercise these rights if you die or become incapacitated.",
          `Grievances: contact our Grievance Officer, ${b.grievanceOfficer}, at ${b.grievanceEmail}. We acknowledge complaints within 48 hours and resolve them within 1 month. If you're not satisfied, you can complain to the Data Protection Board of India.`,
        ],
      },
      {
        heading: "Security and breaches",
        body: [
          "We use TLS, hashed passwords, access controls and monitoring. If a breach affects your data, we will tell you and the Data Protection Board without delay.",
        ],
      },
      {
        heading: "Children",
        body: ["We don't knowingly create accounts for anyone under 18."],
      },
    ],
  },

  refunds: {
    slug: "refunds",
    title: "Cancellation & Refund Policy",
    updated: "2026-10-16",
    intro: "How to cancel an order, return an item and get your money back.",
    sections: [
      {
        heading: "Cancellation",
        body: [
          "You can cancel an order free of charge until it is shipped. Contact us with your order ID.",
        ],
      },
      {
        heading: "Returns",
        body: [
          "You can return most items within [7] days of delivery if they are unused and in their original packaging.",
          "Items that arrive damaged, defective or wrong can be returned or replaced at no cost. Please report them within 48 hours of delivery and include photos.",
        ],
      },
      {
        heading: "Refunds",
        body: [
          "Approved refunds go back to your original payment method within 5–7 business days of approval. Your bank may take longer to show the money.",
          "If we can't fulfil an order after you've paid (for example, an item went out of stock), we refund it in full automatically.",
        ],
      },
    ],
  },

  shipping: {
    slug: "shipping",
    title: "Shipping & Delivery Policy",
    updated: "2026-10-16",
    intro: "Where we deliver, how much it costs and how long it takes.",
    sections: [
      {
        heading: "Where and how much",
        body: [
          "We deliver within India.",
          "Shipping is free on orders over ₹1,000. Otherwise there is a flat ₹200 charge, which is shown before you pay.",
        ],
      },
      {
        heading: "How long",
        body: [
          "Orders are usually dispatched within [2] business days and delivered within [3–7] business days, depending on your PIN code.",
          "You can follow the status (Processing → Shipped → Delivered) under My orders.",
        ],
      },
      {
        heading: "Problems",
        body: [
          "If your order hasn't arrived within [10] business days of shipping, contact us and we'll trace it, or send a replacement or a refund.",
        ],
      },
    ],
  },

  contact: {
    slug: "contact",
    title: "Contact Us",
    updated: "2026-10-16",
    intro: "How to reach us.",
    sections: [
      {
        heading: "Business details",
        body: [b.legalName, b.address],
      },
      {
        heading: "Customer care",
        body: [`Email: ${b.email}`, `Phone: ${b.phone}`],
      },
      {
        heading: "Grievance Officer",
        body: [
          `${b.grievanceOfficer} — ${b.grievanceEmail}`,
          "We acknowledge complaints within 48 hours and resolve them within 1 month.",
        ],
      },
    ],
  },

  cookies: {
    slug: "cookies",
    title: "Cookie & Storage Notice",
    updated: "2026-10-16",
    intro:
      "6Pack doesn't use advertising or analytics trackers. We store only what the site needs to work. That's why there's no cookie banner: none of this is optional, and none of it tracks you across other sites.",
    sections: [
      {
        heading: "What we store in your browser",
        body: [
          "token: keeps you signed in. Deleted when you sign out, and expires after 24 hours.",
          "cart: your cart, coupon and the delivery address you last entered, so they survive a page reload. The address is removed when you sign out.",
        ],
      },
      {
        heading: "Third parties",
        body: [
          "On the payment step only, Razorpay's checkout loads and may set its own cookies to process the payment and prevent fraud. See Razorpay's privacy policy.",
          "Fonts are served from our own servers, so loading a page doesn't contact any third party.",
        ],
      },
      {
        heading: "Your control",
        body: [
          "You can clear site data in your browser at any time. You'll be signed out and your cart will be emptied.",
          "If we ever add optional analytics or marketing tools, we'll ask for your consent first and give you a way to change your choice.",
        ],
      },
    ],
  },
  "admin-aup": {
    slug: "admin-aup",
    title: "Admin Acceptable Use Agreement",
    updated: "2026-10-18",
    intro: `This agreement applies to everyone with an admin account on 6Pack, run by ${b.legalName}. Admins can see customers' personal data, so please read it before you continue.`,
    sections: [
      {
        heading: "Using customer data",
        body: [
          "Use customer data only for the task in front of you, such as fulfilling an order, answering a support request or fixing a problem.",
          "Don't copy, export, photograph or share customer data outside 6Pack, and don't look up people you have no work reason to look up.",
          "Treat order contact details, addresses and phone numbers as confidential, even after you stop being an admin.",
        ],
      },
      {
        heading: "Keeping your account safe",
        body: [
          "Use your own account and a strong, unique password. Never share your login or let someone else use it.",
          "Sign out on shared or public devices.",
        ],
      },
      {
        heading: "Reporting problems",
        body: [
          `If you think customer data has been exposed, or your account has been misused, tell the Grievance Officer straight away: ${b.grievanceOfficer}, ${b.grievanceEmail}.`,
        ],
      },
      {
        heading: "Activity is recorded",
        body: [
          "Changes you make to products, categories, coupons, orders and users, and sign-in attempts, are recorded in an audit log with your account, the time and what changed.",
          "The log is kept for one year and is reviewed by other admins. It doesn't record personal details such as customers' names, emails or addresses.",
        ],
      },
      {
        heading: "Accepting this agreement",
        body: [
          "You must accept this agreement to use the admin area. You'll be asked again whenever it changes in a material way.",
          "Misuse of customer data can lead to removal of admin access and, where the law requires, to legal action.",
        ],
      },
    ],
  },
});

/** Live policy text. Replaced in place by {@link refreshPolicies} once the store's details are known. */
export const POLICIES: Record<PolicySlug, Policy> = build(BUSINESS);

const rebrand = <T,>(value: T, name: string): T => {
  if (typeof value === "string") return value.split("6Pack").join(name) as unknown as T;
  if (Array.isArray(value)) return value.map((v) => rebrand(v, name)) as unknown as T;
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, rebrand(v, name)])) as T;
  }
  return value;
};

/** Rebuilds the policy text after BUSINESS changed, using the store's own name instead of the built-in one. */
export const refreshPolicies = (brandName: string) => {
  Object.assign(POLICIES, rebrand(build(BUSINESS), brandName));
};

/** Footer order. */
export const POLICY_LINKS: PolicySlug[] = ["terms", "privacy", "refunds", "shipping", "contact", "cookies"];

export const policyPath = (slug: PolicySlug) => `/legal/${slug}`;
