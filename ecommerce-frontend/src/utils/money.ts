/** The store's currency, locale and time zone. Filled from the store's config before the app renders. */
export const regional = { currency: "INR", locale: "en-IN", timeZone: "Asia/Kolkata" };

const format = (value: number, currency: string, locale: string) =>
  new Intl.NumberFormat(locale, { style: "currency", currency, minimumFractionDigits: 0, maximumFractionDigits: 2 })
    .format(value);

/** An amount in the store's currency, e.g. ₹1,23,456.5. */
export const formatMoney = (value: number) => {
  try {
    return format(value, regional.currency, regional.locale);
  } catch {
    return format(value, "INR", "en-IN");
  }
};

/** The store's currency symbol, for form labels such as "Price (₹)". */
export const currencySymbol = () =>
  formatMoney(0).replace(/[\d\s.,]/g, "") || regional.currency;
