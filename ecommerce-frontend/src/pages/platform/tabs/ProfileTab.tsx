import { FormEvent, useMemo, useState } from "react";
import { LuX } from "react-icons/lu";
import { StoreTypeKey, TenantDetails } from "../../../redux/api/platformAPI";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import { typeLabel } from "../storeList";
import { ACCENT, addDomain } from "../workspaceForms";
import TabActions from "./TabActions";
import { useTabSave } from "./useTabSave";

const TYPES: StoreTypeKey[] = ["GENERAL", "FASHION", "GROCERY", "ELECTRONICS"];
const STOREFRONT: { key: string; label: string; type?: string; wide?: boolean }[] = [
  { key: "legalName", label: "Legal name" },
  { key: "address", label: "Registered address", wide: true },
  { key: "email", label: "Contact email", type: "email" },
  { key: "phone", label: "Contact phone", type: "tel" },
  { key: "grievanceOfficer", label: "Grievance officer" },
  { key: "grievanceEmail", label: "Grievance email", type: "email" },
  { key: "logoUrl", label: "Logo URL (https or uploads/ path)", wide: true },
];

const ProfileTab = ({ details }: { details: TenantDetails }) => {
  const t = details.tenant;
  const initial = useMemo(
    () => ({
      name: t.name,
      type: t.type,
      domains: t.domains,
      locale: t.locale ?? "",
      currency: t.currency ?? "",
      accent: t.theme[ACCENT] ?? "",
      otherTheme: Object.entries(t.theme)
        .filter(([k]) => k !== ACCENT)
        .map(([k, v]) => `${k}: ${v}`)
        .join("\n"),
      storefront: Object.fromEntries(STOREFRONT.map((f) => [f.key, t.storefront?.[f.key] ?? ""])) as Record<string, string>,
    }),
    [t],
  );
  const [form, setForm] = useState(initial);
  const [newDomain, setNewDomain] = useState("");
  const { save, saving, error } = useTabSave(t.id);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const theme: Record<string, string> = {};
    for (const line of form.otherTheme.split("\n")) {
      const [k, ...v] = line.split(":");
      if (k.trim() && v.join(":").trim()) theme[k.trim()] = v.join(":").trim();
    }
    if (form.accent) theme[ACCENT] = form.accent;
    await save({
      name: form.name,
      type: form.type,
      domains: form.domains,
      locale: form.locale,
      currency: form.currency,
      theme,
      storefront: Object.fromEntries(Object.entries(form.storefront).map(([k, v]) => [k, v.trim() === "" ? null : v.trim()])),
    });
  };

  const addTyped = () => {
    setForm({ ...form, domains: addDomain(form.domains, newDomain) });
    setNewDomain("");
  };

  return (
    <form className="card form-grid tab-form" onSubmit={submit}>
      <label>
        Store name
        <input value={form.name} maxLength={80} required onChange={(e) => setForm({ ...form, name: e.target.value })} />
      </label>
      <label>
        Store type
        <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as StoreTypeKey })}>
          {TYPES.map((k) => (
            <option key={k} value={k}>
              {typeLabel(k)}
            </option>
          ))}
        </select>
        <small className="platform-muted">A label only: changing it does not change any setting.</small>
      </label>

      <fieldset className="span-2">
        <legend>Domains</legend>
        <ul className="domain-chips">
          {form.domains.map((d) => (
            <li key={d}>
              {d}
              <button
                type="button"
                className="icon-btn"
                aria-label={`Remove ${d}`}
                disabled={form.domains.length === 1}
                onClick={() => setForm({ ...form, domains: form.domains.filter((x) => x !== d) })}
              >
                <LuX aria-hidden />
              </button>
            </li>
          ))}
        </ul>
        <span className="input-with-unit">
          <input
            value={newDomain}
            placeholder="shop.example.com"
            aria-label="New domain"
            onChange={(e) => setNewDomain(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault(); // add the domain, do not submit the form
                addTyped();
              }
            }}
          />
          <button type="button" className="btn" onClick={addTyped}>
            Add
          </button>
        </span>
        <small className="platform-muted">A store needs at least one domain.</small>
      </fieldset>

      <label>
        Language (locale)
        <input value={form.locale} maxLength={16} placeholder="en-IN" onChange={(e) => setForm({ ...form, locale: e.target.value })} />
      </label>
      <label>
        Currency
        <input value={form.currency} maxLength={8} placeholder="INR" onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} />
      </label>
      <label>
        Accent colour
        {/* the fallback only seeds the picker; nothing is saved unless a colour is picked */}
        <input type="color" value={form.accent || "#e4572e"} onChange={(e) => setForm({ ...form, accent: e.target.value })} />
      </label>
      <details className="span-2">
        <summary>Advanced theme tokens</summary>
        <textarea
          rows={4}
          value={form.otherTheme}
          spellCheck={false}
          placeholder="--radius-md: 8px"
          aria-label="Other theme tokens"
          onChange={(e) => setForm({ ...form, otherTheme: e.target.value })}
        />
        <small className="platform-muted">
          One token per line, as <code>--name: value</code>.
        </small>
      </details>

      <fieldset className="span-2 form-grid">
        <legend>Storefront and legal entity</legend>
        <p className="span-2 platform-muted">The store owner can also edit these in Store settings.</p>
        {STOREFRONT.map((f) => (
          <label key={f.key} className={f.wide ? "span-2" : undefined}>
            {f.label}
            <input
              type={f.type ?? "text"}
              maxLength={200}
              value={form.storefront[f.key]}
              onChange={(e) => setForm({ ...form, storefront: { ...form.storefront, [f.key]: e.target.value } })}
            />
          </label>
        ))}
      </fieldset>

      <TabActions dirty={dirty} saving={saving} error={error} onDiscard={() => setForm(initial)} />
    </form>
  );
};

export default ProfileTab;
