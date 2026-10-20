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
      { type: "p", text: "The sidebar is your menu: Analytics for the numbers, Products for the catalogue, Categories for the category tree, Customers for accounts, Transactions for orders and Coupons for discount codes. On a phone or small window the menu opens from the button at the bottom left." },
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
        "Optionally pick a Linked category, or choose up to 5 Gallery pictures (5 MB each). New gallery pictures replace the whole gallery, so choose all of them together.",
        "Click Save.",
      ] },
      { type: "tip", text: "To stop selling something without deleting it, set its stock to 0. The store shows it as sold out." },
      { type: "warn", text: "Delete removes the product permanently, after a confirmation." },
    ],
    related: ["add-product", "product-variants", "products"],
  },
  {
    slug: "product-variants",
    group: "Catalogue",
    title: "Sizes, colours and other variants",
    summary: "Sell one product in several sizes, colours or other options, each with its own stock and price.",
    body: [
      { type: "p", text: "A variant is one buyable combination, such as size M in Black. Each variant has its own SKU, price, optional MRP, stock and status. Customers pick their options on the product page." },
      { type: "steps", title: "Set up variants", items: [
        "Open the product and choose the Variants tab.",
        "Click Size, Colour or Add option, then type the values separated by commas (S, M, L). Press Enter to add them.",
        "For a colour option, pick each swatch colour. Upload the product's images first (Details tab, Gallery images), then use the image button next to a colour to choose which images show when it is picked.",
        "Fill in the table that appears: price, optional MRP and stock for every combination. Leave SKU as auto to have one made for you.",
        "Click Save variants.",
      ] },
      { type: "tip", text: "Changing a stock number in the table saves as soon as you leave the field, and it never overwrites sales that happened in the meantime. Everything else needs Save variants." },
      { type: "tip", text: "Set a variant to Disabled to stop selling it without losing its history. Disabled variants show as sold out." },
      { type: "warn", text: "With variants, the product's own price and stock are worked out for you: the store shows the lowest variant price and the total stock. Removing an option merges or drops existing variants, and uploading a new gallery removes the images you set on colours." },
    ],
    related: ["edit-product", "size-guide", "low-stock", "products-csv"],
  },
  {
    slug: "size-guide",
    group: "Catalogue",
    title: "Adding a size guide",
    summary: "Show shoppers a measurement table on the product page.",
    body: [
      { type: "steps", items: [
        "Open the product and choose the Size guide tab.",
        "Start from the clothing or footwear template, or a blank table. If the product has a Size option, its sizes fill the first column for you.",
        "Type the measurements. Add or remove rows and columns as needed, and add an optional note such as how to measure.",
        "Click Save size guide. Shoppers now see a Size guide link next to the size buttons.",
      ] },
      { type: "tip", text: "Keep the size in the first column and use the same size names as the product's Size option, so shoppers can match them up." },
    ],
    related: ["product-variants", "edit-product"],
  },
  {
    slug: "low-stock",
    group: "Catalogue",
    title: "Low-stock alerts",
    summary: "See what is running out before it sells out.",
    body: [
      { type: "p", text: "Every product has a low-stock level. When the product's stock, or the stock of any active variant, is at or below that level, it shows up in the Low stock card on Analytics and gets a Low badge in the Products list." },
      { type: "steps", title: "Change the level for one product", items: [
        "Open the product. On the Details tab, set Low-stock alert at.",
        "Click Save. Leave it empty to use the store default (5).",
      ] },
      { type: "tip", text: "Set it to 0 to be told only when something has sold out. Disabled variants and draft or archived products never raise alerts." },
    ],
    related: ["products-csv", "product-variants"],
  },
  {
    slug: "products-csv",
    group: "Catalogue",
    title: "Bulk-editing prices and stock with a spreadsheet",
    summary: "Export products to CSV, edit them in Excel or Google Sheets, and import the file back.",
    body: [
      { type: "steps", items: [
        "On the Products page, click Export CSV. Every product without variants and every variant gets its own row.",
        "Edit price, mrp, stock, status or low_stock_threshold. Leave a cell empty to keep the current value. Other columns are for reference and are ignored.",
        "Save as CSV and click Import CSV. You see a list of what will change; nothing is saved yet.",
        "Click Apply to save the changes.",
      ] },
      { type: "tip", text: "You can also import a short file with just two columns, such as sku and stock." },
      { type: "warn", text: "If any row has a problem, the whole file is refused and you get the line numbers to fix. Import changes existing products and variants only; it never creates or deletes them." },
    ],
    related: ["low-stock", "products"],
  },
  {
    slug: "categories",
    group: "Catalogue",
    title: "Managing categories",
    summary: "Create, nest, rename and delete the categories products can be linked to.",
    openLabel: { to: "/admin/category", label: "Open Categories" },
    body: [
      { type: "steps", items: [
        "Go to Categories and click New category.",
        "Enter a Name, optionally pick a Parent category to nest it (shown as Clothing › Shirts), and optionally add an image (JPEG, PNG, WEBP or GIF, 5 MB).",
        "Click Create category. Use the pencil on a row to rename it, change its parent or replace the image.",
        "Link a product to a category from the product page (Linked category).",
      ] },
      { type: "tip", text: "A category can't become its own child, so the parent list leaves out the category you are editing and everything below it. Once a category has a parent, edits can move it to another parent but not back to the top level." },
      { type: "warn", text: "You can only delete a category that has no sub-categories and no linked products. Delete asks you to confirm first." },
    ],
    related: ["add-product", "edit-product"],
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
    title: "Roles, suspending and deleting accounts",
    summary: "Promote a customer to admin, suspend or reactivate an account, or remove it.",
    body: [
      { type: "steps", title: "Change a role", items: [
        "Open the customer from the Customers list.",
        "Pick Admin or User in the Role drop-down.",
        "Click Save.",
      ] },
      { type: "steps", title: "Suspend or reactivate", items: [
        "Open the customer and click Suspend. Confirm the prompt.",
        "They are signed out right away and can't sign in until you click Reactivate. Their orders and data are kept.",
      ] },
      { type: "p", text: "If another instance of the store is serving them, a suspension can take up to 15 seconds to reach every instance. You cannot suspend yourself." },
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
    related: ["orders", "refunds"],
  },
  {
    slug: "refunds",
    group: "Sales",
    title: "Refunds and invoices",
    summary: "Return money to a customer, and open the invoice for an order.",
    body: [
      { type: "steps", title: "Issue a refund", items: [
        "Open the order from Transactions and look at the Payment section: status, how the customer paid, and what has already been refunded.",
        "Enter an Amount in ₹ (leave it empty to refund everything that is left) and a Reason. The customer can see the reason, so keep it polite and leave out names.",
        "Click Issue refund and confirm. The money goes back to the card, UPI or bank account the customer paid with.",
      ] },
      { type: "p", text: "You can refund in parts until the order total is reached. The status goes from Paid to Partially refunded to Refunded." },
      { type: "tip", text: "If a refund fails you will see why in red under Payment, and nothing has been refunded. Fix the cause and try again; trying again can't pay twice." },
      { type: "warn", text: "A refund returns money only. It does not put items back in stock, change the order status, or lower the revenue on Analytics, so adjust stock yourself if the goods come back." },
      { type: "steps", title: "Invoices", items: [
        "Click Invoice at the top of the order page (customers have a View invoice link on their order).",
        "Click Print / Save as PDF and choose Save as PDF in the print window.",
      ] },
      { type: "p", text: "The invoice shows your business details, the tax split by rate (CGST and SGST for buyers in your state, IGST for others) and any refunds. Ask your developer to set your business name, address, GSTIN and state." },
    ],
    related: ["fulfil-order", "orders"],
  },
  {
    slug: "coupons",
    group: "Sales",
    title: "Creating and deleting coupons",
    summary: "Flat or percentage discount codes your customers enter in their cart.",
    openLabel: { to: "/admin/app/coupon", label: "Open Coupons" },
    body: [
      { type: "steps", items: [
        "Go to Coupons.",
        "Type a Code of 3–32 letters, digits, _ or -, or click Generate for a random 8-character code.",
        "Choose Flat (₹ off) or Percent (% off, with an optional maximum) and enter the amount.",
        "Optionally set a minimum order, a start and end time, and limits on total uses and uses per customer.",
        "Optionally limit it to chosen categories or products (hold Ctrl or Cmd to pick several), or to a customer's first order only.",
        "Click Create coupon. Use the pause button on a row to stop and restart a code without deleting it.",
      ] },
      { type: "p", text: "With categories or products picked, the discount counts only matching items in the cart. If you pick both, an item matching either counts. The minimum order still looks at the whole cart." },
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
  if (pathname === "/admin/category") return "categories";
  if (pathname === "/admin/customer") return "customers";
  if (pathname.startsWith("/admin/customer/")) return "customer-roles";
  if (pathname === "/admin/transaction") return "orders";
  if (pathname.startsWith("/admin/transaction/")) return "fulfil-order";
  if (pathname.startsWith("/admin/app/coupon")) return "coupons";
  return "getting-started";
};
