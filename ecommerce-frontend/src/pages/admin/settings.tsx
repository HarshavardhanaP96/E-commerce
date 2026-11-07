import { FormEvent, ReactNode, useEffect, useMemo, useState } from "react";
import toast from "react-hot-toast";
import { useNavigate, useParams } from "react-router-dom";
import AdminLayout from "../../components/admin/AdminLayout";
import ConfirmDialog from "../../components/admin/ConfirmDialog";
import Tabs from "../../components/admin/Tabs";
import { errorMessage } from "../../redux/api/baseQuery";
import { useIndianStatesQuery } from "../../redux/api/referenceAPI";
import { useSaveStoreSettingsMutation, useStoreSettingsQuery } from "../../redux/api/storeAPI";
import { loadTenantConfig } from "../../tenant/tenant";
import { savedStateChoice } from "../../utils/indianStates";
import { currencySymbol, formatMoney } from "../../utils/money";
import { typeLabel } from "../../utils/storeTypes";
import { useUnsavedGuard } from "../../utils/useUnsavedGuard";
import {
  emptyForm,
  fromSettings,
  SETTINGS_TABS,
  SettingsTab,
  StoreSettingsForm,
  tabDirty,
  toTabPayload,
} from "./storeSettingsForm";

type Key = keyof StoreSettingsForm;

const zones = (() => {
  try {
    return (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
  } catch {
    return [];
  }
})();

/** The store owner's own shop rules, one tab per group. Every empty field uses the platform default shown in it. */
const StoreSettings = () => {
  const { tab: rawTab } = useParams();
  const tab = (SETTINGS_TABS.some((t) => t.key === rawTab) ? rawTab : "shipping") as SettingsTab;
  const navigate = useNavigate();
  const { data, isLoading, isError, error } = useStoreSettingsQuery();
  const [save, { isLoading: saving }] = useSaveStoreSettingsMutation();
  const [form, setForm] = useState<StoreSettingsForm>(emptyForm);
  const [pendingTab, setPendingTab] = useState<SettingsTab | null>(null);
  const { data: stateList } = useIndianStatesQuery();
  const states = stateList?.states ?? [];
  const stateChoice = savedStateChoice(form.state, states);

  const saved = useMemo(() => (data ? fromSettings(data.settings) : emptyForm), [data]);
  useEffect(() => setForm(saved), [saved]);

  const fx = data?.effective;
  const dflt = data?.defaults;
  const sym = currencySymbol();
  const set = (key: Key) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [key]: e.target.value }));
  const dirty = data ? tabDirty(form, saved, tab) : false;
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = await save(toTabPayload(form, tab));
    if ("error" in res) {
      toast.error(errorMessage(res.error));
      return;
    }
    await loadTenantConfig(); // this tab's cart and prices follow the new rules straight away
    toast.success("Store settings saved");
  };

  /** "Custom" when the store has saved its own value; the button clears the field (saved as the default on Save). */
  const own = (key: Key, resetText = "Reset to default") =>
    saved[key] !== "" && (
      <button type="button" className="link-btn" onClick={() => setForm((f) => ({ ...f, [key]: "" }))}>
        {resetText}
      </button>
    );
  const tag = (key: Key) => saved[key] !== "" && <span className="tag">Custom</span>;

  const number = (key: Key, label: ReactNode, hint: string | undefined, attrs: { min: number; max: number; step?: number }) => (
    <label>
      <span>
        {label}
        {tag(key)}
      </span>
      <input type="number" inputMode="decimal" value={form[key]} onChange={set(key)} placeholder={hint}
             min={attrs.min} max={attrs.max} step={attrs.step ?? 1} />
      {own(key)}
    </label>
  );

  const textField = (key: Key, label: string, attrs: { type?: string; maxLength?: number; span?: boolean; resetText?: string } = {}) => (
    <label className={attrs.span ? "span-2" : undefined}>
      <span>
        {label}
        {tag(key)}
      </span>
      <input type={attrs.type ?? "text"} value={form[key]} onChange={set(key)} maxLength={attrs.maxLength ?? 200} />
      {own(key, attrs.resetText)}
    </label>
  );

  // Invoice and contact fields have no platform default to fall back to, so their button only clears.
  const contact = (key: Key, label: string, attrs: { type?: string; maxLength?: number; span?: boolean } = {}) =>
    textField(key, label, { ...attrs, resetText: "Clear" });

  const sections: Record<SettingsTab, ReactNode> = {
    shipping: (
      <section className="card form-grid">
        {number("shippingFee", `Shipping fee (${sym})`, `Default: ${dflt?.shippingFee}`, { min: 0, max: 1_000_000, step: 0.01 })}
        {number("freeShippingAbove", `Free shipping above (${sym})`, `Default: ${dflt?.freeShippingAbove}`, { min: 0, max: 10_000_000, step: 0.01 })}
        {number("defaultTaxRate", "Default GST rate (%)", `Default: ${dflt?.defaultTaxRate}`, { min: 0, max: 100, step: 0.01 })}
        {fx && (
          <p className="span-2 store-settings__hint">
            Applies to products without their own GST rate. Orders keep the rate they were charged at.
            Now: {fx.shippingFee === 0 ? "shipping is always free" : `${formatMoney(fx.shippingFee)} shipping, free above ${formatMoney(fx.freeShippingAbove)}`}, {fx.defaultTaxRate}% GST.
          </p>
        )}
      </section>
    ),
    orders: (
      <section className="card form-grid">
        {number("maxCouponAmount", `Largest flat coupon (${sym})`, `Default: ${dflt?.maxCouponAmount}`, { min: 1, max: 10_000_000 })}
        {number("minimumAge", "Minimum shopper age", `Default: ${dflt?.minimumAge}`, { min: 18, max: 100 })}
        <p className="span-2 store-settings__hint">The minimum age can be raised, never set below 18.</p>
        {number("stuckOrderDays", "Flag orders still processing after (days)", `Default: ${dflt?.stuckOrderDays}`, { min: 1, max: 60 })}
        {textField("orderPrefix", "Order number prefix", { maxLength: 6 })}
        <p className="span-2 store-settings__hint">
          Up to 6 letters or digits. Default: {dflt?.orderPrefix}. New orders look like {fx?.orderPrefix}-20261105-K7M3QX;
          existing orders keep their number.
        </p>
      </section>
    ),
    catalogue: (
      <section className="card form-grid">
        {number("pageSize", "Products per search page", `Default: ${dflt?.pageSize}`, { min: 1, max: 100 })}
        {number("lowStockThreshold", "Low-stock level", `Default: ${dflt?.lowStockThreshold}`, { min: 0, max: 1_000_000 })}
        <p className="span-2 store-settings__hint">A product&apos;s own low-stock level, when set, wins.</p>
      </section>
    ),
    invoice: (
      <section className="card form-grid">
        <p className="span-2 store-settings__hint">
          Printed on your invoices and shown on your Terms, Privacy and Contact pages.
        </p>
        {contact("legalName", "Legal name", { span: true })}
        {contact("address", "Registered address", { span: true })}
        {contact("gstin", "GSTIN", { maxLength: 15 })}
        <label>
          <span>
            State of registration
            {tag("state")}
          </span>
          <select value={stateChoice.value} onChange={set("state")}>
            <option value="">Not set</option>
            {stateChoice.extra && <option value={stateChoice.extra.value}>{stateChoice.extra.label}</option>}
            {states.map((s) => <option key={s.code} value={s.name}>{s.name}</option>)}
          </select>
          {own("state", "Clear")}
        </label>
        <p className="span-2 store-settings__hint">
          The state decides CGST + SGST (buyer in the same state) or IGST.
        </p>
        {contact("email", "Contact email", { type: "email" })}
        {contact("phone", "Contact phone", { type: "tel" })}
        {contact("grievanceOfficer", "Grievance officer")}
        {contact("grievanceEmail", "Grievance email", { type: "email" })}
        {contact("logoUrl", "Logo URL (https or uploads/ path)", { span: true })}
      </section>
    ),
    region: (
      <section className="card form-grid">
        <label className="span-2">
          <span>
            Time zone
            {tag("timeZone")}
          </span>
          <input list="store-time-zones" value={form.timeZone} onChange={set("timeZone")} placeholder={`Default: ${dflt?.timeZone}`} />
          <datalist id="store-time-zones">
            {zones.map((z) => <option key={z} value={z} />)}
          </datalist>
          {own("timeZone")}
        </label>
        <p className="span-2 store-settings__hint">Analytics days, and dates on orders and invoices, follow this zone.</p>
      </section>
    ),
  };

  return (
    <AdminLayout>
      <div className="store-settings">
        <header className="store-settings__head">
          <h1>Store settings</h1>
          <p>
            Your shop&apos;s own rules. Leave a field empty to use the platform default shown in it.
            {data && data.type !== "GENERAL" && <> {typeLabel(data.type)} store: starting values were set when the store was created.</>}
          </p>
        </header>

        <Tabs
          tabs={SETTINGS_TABS}
          active={tab}
          label="Store settings sections"
          hrefFor={(k) => `/admin/settings/${k}`}
          canLeave={(k) => {
            if (!dirty) return true;
            setPendingTab(k as SettingsTab); // stop the switch and ask first
            return false;
          }}
        />

        {isLoading && <p className="store-settings__note">Loading…</p>}
        {isError && <p className="store-settings__note" role="alert">{errorMessage(error)}</p>}

        {fx && (
          <form onSubmit={submit} className="store-settings__form" role="tabpanel" aria-labelledby={`tab-${tab}`}>
            {sections[tab]}

            <div className="store-settings__actions">
              <button type="submit" className="btn btn--primary" disabled={saving || !dirty}>
                {saving ? "Saving…" : "Save settings"}
              </button>
              <button type="button" className="btn" disabled={saving || !dirty} onClick={() => setForm(saved)}>
                Discard changes
              </button>
            </div>
          </form>
        )}

        <ConfirmDialog
          open={pendingTab !== null}
          title="Discard unsaved changes?"
          body={<p>You changed this tab without saving. Discard the changes and switch tabs?</p>}
          confirmLabel="Discard changes"
          danger
          onCancel={() => setPendingTab(null)}
          onConfirm={() => {
            setForm(saved);
            navigate(`/admin/settings/${pendingTab}`);
            setPendingTab(null);
          }}
        />
      </div>
    </AdminLayout>
  );
};

export default StoreSettings;
