import { FormEvent, ReactNode, useEffect, useMemo, useState } from "react";
import toast from "react-hot-toast";
import AdminLayout from "../../components/admin/AdminLayout";
import { errorMessage } from "../../redux/api/baseQuery";
import { useSaveStoreSettingsMutation, useStoreSettingsQuery } from "../../redux/api/storeAPI";
import { loadTenantConfig } from "../../tenant/tenant";
import { currencySymbol, formatMoney } from "../../utils/money";
import { emptyForm, fromSettings, StoreSettingsForm, toPayload } from "./storeSettingsForm";

type Key = keyof StoreSettingsForm;

const zones = (() => {
  try {
    return (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
  } catch {
    return [];
  }
})();

/** The store owner's own shop rules. Every empty field uses the platform default shown in it. */
const StoreSettings = () => {
  const { data, isLoading, isError, error } = useStoreSettingsQuery();
  const [save, { isLoading: saving }] = useSaveStoreSettingsMutation();
  const [form, setForm] = useState<StoreSettingsForm>(emptyForm);

  useEffect(() => {
    if (data) setForm(fromSettings(data.settings));
  }, [data]);

  const fx = data?.effective;
  const dflt = data?.defaults;
  const sym = currencySymbol();
  const set = (key: Key) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [key]: e.target.value }));

  const dirty = useMemo(
    () => (data ? JSON.stringify(form) !== JSON.stringify(fromSettings(data.settings)) : false),
    [data, form],
  );

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = await save(toPayload(form));
    if ("error" in res) {
      toast.error(errorMessage(res.error));
      return;
    }
    await loadTenantConfig(); // this tab's cart and prices follow the new rules straight away
    toast.success("Store settings saved");
  };

  const number = (key: Key, label: ReactNode, hint: string | undefined, attrs: { min: number; max: number; step?: number }) => (
    <label>
      {label}
      <input type="number" inputMode="decimal" value={form[key]} onChange={set(key)} placeholder={hint}
             min={attrs.min} max={attrs.max} step={attrs.step ?? 1} />
    </label>
  );

  const textField = (key: Key, label: string, attrs: { type?: string; maxLength?: number; span?: boolean } = {}) => (
    <label className={attrs.span ? "span-2" : undefined}>
      {label}
      <input type={attrs.type ?? "text"} value={form[key]} onChange={set(key)} maxLength={attrs.maxLength ?? 200} />
    </label>
  );

  return (
    <AdminLayout>
      <div className="store-settings">
        <header className="store-settings__head">
          <h1>Store settings</h1>
          <p>Your shop&apos;s own rules. Leave a field empty to use the platform default shown in it.</p>
        </header>

        {isLoading && <p className="store-settings__note">Loading…</p>}
        {isError && <p className="store-settings__note" role="alert">{errorMessage(error)}</p>}

        {fx && (
          <form onSubmit={submit} className="store-settings__form">
            <section className="card form-grid">
              <h4 className="span-2">Shipping and tax</h4>
              {number("shippingFee", `Shipping fee (${sym})`, `Default: ${dflt?.shippingFee}`, { min: 0, max: 1_000_000, step: 0.01 })}
              {number("freeShippingAbove", `Free shipping above (${sym})`, `Default: ${dflt?.freeShippingAbove}`, { min: 0, max: 10_000_000, step: 0.01 })}
              {number("defaultTaxRate", "Default GST rate (%)", `Default: ${dflt?.defaultTaxRate}`, { min: 0, max: 100, step: 0.01 })}
              <p className="span-2 store-settings__hint">
                Applies to products without their own GST rate. Orders keep the rate they were charged at.
                Now: {fx.shippingFee === 0 ? "shipping is always free" : `${formatMoney(fx.shippingFee)} shipping, free above ${formatMoney(fx.freeShippingAbove)}`}, {fx.defaultTaxRate}% GST.
              </p>
            </section>

            <section className="card form-grid">
              <h4 className="span-2">Coupons and sign-up</h4>
              {number("maxCouponAmount", `Largest flat coupon (${sym})`, `Default: ${dflt?.maxCouponAmount}`, { min: 1, max: 10_000_000 })}
              {number("minimumAge", "Minimum shopper age", `Default: ${dflt?.minimumAge}`, { min: 18, max: 100 })}
              <p className="span-2 store-settings__hint">The minimum age can be raised, never set below 18.</p>
            </section>

            <section className="card form-grid">
              <h4 className="span-2">Catalogue</h4>
              {number("pageSize", "Products per search page", `Default: ${dflt?.pageSize}`, { min: 1, max: 100 })}
              {number("lowStockThreshold", "Low-stock level", `Default: ${dflt?.lowStockThreshold}`, { min: 0, max: 1_000_000 })}
              <p className="span-2 store-settings__hint">A product&apos;s own low-stock level, when set, wins.</p>
            </section>

            <section className="card form-grid">
              <h4 className="span-2">Invoices and contact details</h4>
              <p className="span-2 store-settings__hint">
                Printed on your invoices and shown on your Terms, Privacy and Contact pages.
              </p>
              {textField("legalName", "Legal name", { span: true })}
              {textField("address", "Registered address", { span: true })}
              {textField("gstin", "GSTIN", { maxLength: 15 })}
              {textField("state", "State of registration", { maxLength: 60 })}
              <p className="span-2 store-settings__hint">
                The state decides CGST + SGST (buyer in the same state) or IGST. Spell it as customers do, e.g. Karnataka.
              </p>
              {textField("email", "Contact email", { type: "email" })}
              {textField("phone", "Contact phone", { type: "tel" })}
              {textField("grievanceOfficer", "Grievance officer")}
              {textField("grievanceEmail", "Grievance email", { type: "email" })}
              {textField("logoUrl", "Logo URL (https or uploads/ path)", { span: true })}
            </section>

            <section className="card form-grid">
              <h4 className="span-2">Region</h4>
              <label className="span-2">
                Time zone
                <input list="store-time-zones" value={form.timeZone} onChange={set("timeZone")} placeholder={`Default: ${dflt?.timeZone}`} />
                <datalist id="store-time-zones">
                  {zones.map((z) => <option key={z} value={z} />)}
                </datalist>
              </label>
              <p className="span-2 store-settings__hint">Analytics days, and dates on orders and invoices, follow this zone.</p>
            </section>

            <div className="store-settings__actions">
              <button type="submit" className="btn btn--primary" disabled={saving || !dirty}>
                {saving ? "Saving…" : "Save settings"}
              </button>
              <button type="button" className="btn" disabled={saving || !dirty} onClick={() => data && setForm(fromSettings(data.settings))}>
                Discard changes
              </button>
            </div>
          </form>
        )}
      </div>
    </AdminLayout>
  );
};

export default StoreSettings;
