/**
 * Admin knowledge base. Separate from the customer FAQ (help/articles.ts): these are how-to guides for the
 * admin panel. Every admin page has a Help button in the header that opens the guide returned by
 * `guideForPath`, so when you add an admin page, add a guide and a case there.
 */

export type GuideGroup = "Start here" | "Catalogue" | "Customers" | "Sales";

export const GUIDE_GROUPS: GuideGroup[] = ["Start here", "Catalogue", "Customers", "Sales"];

export type GuideBlock =
  | { type: "p"; text: string }
  | { type: "steps"; title?: string; items: string[] }
  | { type: "tip"; text: string }
  | { type: "warn"; text: string };

export type Guide = {
  slug: string;
  group: GuideGroup;
  title: string;
  summary: string;
  body: GuideBlock[];
  /** Slugs of guides worth reading next; shown under the article. */
  related: string[];
  /** The admin page this guide documents (what the header Help button opens there). */
  openLabel?: { to: string; label: string };
};

export const GUIDES: Guide[] = [
  {
    slug: "getting-started",
    group: "Start here",
    title: "Admin panel basics",
    summary: "What each section does and how to get around.",
    body: [
      { type: "p", text: "The sidebar is your menu: Analytics for the numbers, Products for the catalogue, Customers for accounts, Transactions for orders and Coupons for discount codes. On a phone or small window the menu opens from the button at the bottom left." },
      { type: "steps", title: "Good to know", items: [
        "Click any row in a table to open that record.",
        "The Help button at the top right of every admin page opens the guide for the page you are on.",
        "Anything marked Delete asks you to confirm first, and deleting cannot be undone.",
      ] },
      { type: "tip", text: "Use the 6PACK logo to jump back to the store, and the account icon to sign out." },
    ],
    related: ["tables", "analytics"],
  },
  {
    slug: "tables",
    group: "Start here",
    title: "Searching, sorting and paging tables",
    summary: "Find records quickly in the Products, Customers, Transactions and Coupons lists.",
    body: [
      { type: "steps", items: [
        "Type in the search box to filter the rows on the page as you type.",
        "Use the drop-downs next to the search (category, gender, role, status) to narrow the list.",
        "Click a column heading to sort by it; click again to reverse the order.",
        "Change Rows per page at the bottom, and use the arrows to move between pages.",
      ] },
      { type: "tip", text: "Long values are cut off with “…” to keep columns tidy. Open the row to see the full value." },
    ],
    related: ["getting-started"],
  },
  {
    slug: "analytics",
    group: "Start here",
    title: "Reading the Analytics dashboard",
    summary: "Revenue, customers, products and orders at a glance.",
    openLabel: { to: "/admin/analytics", label: "Open Analytics" },
    body: [
      { type: "p", text: "The four cards at the top show totals for revenue, customers, products and orders, each with the change versus last month (green is up, red is down). The charts below break the numbers down." },
      { type: "steps", title: "What the charts show", items: [
        "Revenue & orders (6 months): purple bars are revenue and use the left axis (₹); blue bars are order counts and use the right axis.",
        "Inventory by category, Gender ratio, Customers by age: how your catalogue and customers split.",
        "Order fulfilment and Stock availability: how many orders are Processing, Shipped or Delivered, and how many products are in or out of stock.",
        "Revenue distribution: tax, shipping, discounts and net income.",
        "Latest transactions: click one to open the order.",
      ] },
      { type: "tip", text: "Use the category drop-down at the top right to scope the whole dashboard to one category." },
    ],
    related: ["orders", "products"],
  },
  {
    slug: "products",
    group: "Catalogue",
    title: "Managing products",
    summary: "Browse, search and open products in your catalogue.",
    openLabel: { to: "/admin/product", label: "Open Products" },
    body: [
      { type: "p", text: "The Products list shows every item with its photo, category, price and stock. A stock of 0 is shown in red so sold-out items stand out." },
      { type: "steps", items: [
        "Search by name or filter by category.",
        "Click Add product to create a new one.",
        "Click a row to edit or delete that product.",
      ] },
    ],
    related: ["add-product", "edit-product", "tables"],
  },
  {
    slug: "add-product",
    group: "Catalogue",
    title: "Adding a product",
    summary: "What each field means and what the photo must look like.",
    openLabel: { to: "/admin/product/new", label: "Add a product" },
    body: [
      { type: "steps", items: [
        "Go to Products and click Add product.",
        "Enter the Name (up to 120 characters) and a Category (up to 50 characters, for example laptop or camera).",
        "Enter the Price in ₹ and the Stock (a whole number, 0 or more).",
        "Choose a photo: JPEG, PNG, WEBP or GIF, no larger than 5 MB.",
        "Click Save. The preview on the left updates as you type.",
      ] },
      { type: "tip", text: "Reuse the same category spelling for similar products. Categories drive the store’s category filter and the analytics breakdown, so “Laptop” and “laptops” would count as two." },
    ],
    related: ["edit-product", "products"],
  },
  {
    slug: "edit-product",
    group: "Catalogue",
    title: "Editing or deleting a product",
    summary: "Update price and stock, replace the photo, or remove a product.",
    body: [
      { type: "steps", items: [
        "Open the product from the Products list.",
        "Change any field. Leave the photo alone to keep the current one, or choose a new file to replace it.",
        "Click Save.",
      ] },
      { type: "tip", text: "To stop selling something without deleting it, set its stock to 0. The store shows it as sold out." },
      { type: "warn", text: "Delete removes the product permanently, after a confirmation." },
    ],
    related: ["add-product", "products"],
  },
  {
    slug: "customers",
    group: "Customers",
    title: "Finding customers",
    summary: "Search the customer list and open an account.",
    openLabel: { to: "/admin/customer", label: "Open Customers" },
    body: [
      { type: "steps", items: [
        "Search by name or email.",
        "Filter by gender or role (Admin or User).",
        "Click a row to see that customer’s details, role and order summary.",
      ] },
    ],
    related: ["customer-roles", "tables"],
  },
  {
    slug: "customer-roles",
    group: "Customers",
    title: "Changing a role or deleting an account",
    summary: "Promote a customer to admin, or remove an account.",
    body: [
      { type: "steps", title: "Change a role", items: [
        "Open the customer from the Customers list.",
        "Pick Admin or User in the Role drop-down.",
        "Click Save.",
      ] },
      { type: "warn", text: "Admins can open this panel and change data. Only promote people you trust." },
      { type: "p", text: "For safety you cannot remove your own admin role or delete your own account. Ask another admin to do it." },
      { type: "warn", text: "Deleting an account is permanent and asks you to confirm first." },
    ],
    related: ["customers"],
  },
  {
    slug: "orders",
    group: "Sales",
    title: "Working with orders",
    summary: "The Transactions list and what each status means.",
    openLabel: { to: "/admin/transaction", label: "Open Transactions" },
    body: [
      { type: "p", text: "Transactions lists every order with its customer, number of items, discount, amount, date and status." },
      { type: "steps", title: "Statuses", items: [
        "Processing: paid and waiting to be dispatched.",
        "Shipped: on its way to the customer.",
        "Delivered: the customer has received it.",
      ] },
      { type: "tip", text: "Filter by status to see, for example, everything still waiting to ship. Click a row to open the order." },
    ],
    related: ["fulfil-order", "tables"],
  },
  {
    slug: "fulfil-order",
    group: "Sales",
    title: "Updating an order’s status",
    summary: "Move an order from Processing to Shipped to Delivered.",
    body: [
      { type: "steps", items: [
        "Open the order from Transactions.",
        "Click Mark shipped when you dispatch it; later click Mark delivered once it arrives.",
        "The customer sees each change under My orders in the store.",
      ] },
      { type: "p", text: "The order page also shows the items (click one to open the product), the customer and their delivery address, and the amount breakdown: subtotal, shipping, tax, discount and total." },
      { type: "warn", text: "Delete removes the order permanently, after a confirmation. Use it only for test or mistaken orders." },
    ],
    related: ["orders"],
  },
  {
    slug: "coupons",
    group: "Sales",
    title: "Creating and deleting coupons",
    summary: "Flat-amount discount codes your customers enter in their cart.",
    openLabel: { to: "/admin/app/coupon", label: "Open Coupons" },
    body: [
      { type: "steps", items: [
        "Go to Coupons.",
        "Type a Code of 3–32 letters, digits, _ or -, or click Generate for a random 8-character code.",
        "Enter the discount in ₹ (a flat amount of at least 1).",
        "Click Create.",
      ] },
      { type: "p", text: "Customers enter the code in the Coupon code box in their cart, and the discount comes off the total before they pay." },
      { type: "tip", text: "Share the code in your emails or social posts. Delete it from the list to stop it working." },
    ],
    related: ["orders"],
  },
];

export const guideBySlug = (slug?: string) => GUIDES.find((g) => g.slug === slug);

/** Which guide the header Help button opens on each admin page. */
export const guideForPath = (pathname: string): string => {
  if (pathname.startsWith("/admin/help")) return "";
  if (pathname === "/admin/analytics") return "analytics";
  if (pathname === "/admin/product") return "products";
  if (pathname === "/admin/product/new") return "add-product";
  if (pathname.startsWith("/admin/product/")) return "edit-product";
  if (pathname === "/admin/customer") return "customers";
  if (pathname.startsWith("/admin/customer/")) return "customer-roles";
  if (pathname === "/admin/transaction") return "orders";
  if (pathname.startsWith("/admin/transaction/")) return "fulfil-order";
  if (pathname.startsWith("/admin/app/coupon")) return "coupons";
  return "getting-started";
};
