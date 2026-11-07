import { FormEvent, useMemo, useState } from "react";
import { TenantDetails } from "../../../redux/api/platformAPI";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import TabActions from "./TabActions";
import { useTabSave } from "./useTabSave";

type Field = { key: string; label: string; hint?: string; maxLength?: number };

/** A group of optional text fields saved as one object (`payment`, `mail` or `legal`). Empty sends null. */
const GroupForm = ({ details, group, fields, note }: { details: TenantDetails; group: "payment" | "mail" | "legal"; fields: Field[]; note: string }) => {
  const values = details.tenant[group];
  const initial = useMemo(() => Object.fromEntries(fields.map((f) => [f.key, values?.[f.key] ?? ""])), [values, fields]);
  const [form, setForm] = useState<Record<string, string>>(initial);
  const { save, saving, error } = useTabSave(details.tenant.id);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await save({ [group]: Object.fromEntries(Object.entries(form).map(([k, v]) => [k, v.trim() === "" ? null : v.trim()])) });
  };

  return (
    <form className="card form-grid tab-form" onSubmit={submit}>
      <p className="span-2 platform-muted">{note}</p>
      {fields.map((f) => (
        <label key={f.key} className="span-2">
          {f.label}
          <input value={form[f.key]} maxLength={f.maxLength ?? 200} onChange={(e) => setForm({ ...form, [f.key]: e.target.value })} />
          {f.hint && <small className="platform-muted">{f.hint}</small>}
        </label>
      ))}
      <TabActions dirty={dirty} saving={saving} error={error} onDiscard={() => setForm(initial)} />
    </form>
  );
};

const PAYMENT_FIELDS: Field[] = [
  { key: "provider", label: "Provider", hint: "razorpay (or fake in development)", maxLength: 20 },
  { key: "keyId", label: "Razorpay key id", maxLength: 64 },
  { key: "secretRef", label: "Secret reference", hint: "Letters, digits and _ only; the server reads TENANT_SECRET_<this>.", maxLength: 40 },
];
const MAIL_FIELDS: Field[] = [{ key: "baseUrl", label: "Link base", hint: "e.g. https://shop.example.com" }];
const LEGAL_FIELDS: Field[] = [
  { key: "termsVersion", label: "Terms version", maxLength: 32 },
  { key: "privacyVersion", label: "Privacy version", maxLength: 32 },
  { key: "adminAupVersion", label: "Admin agreement version", maxLength: 32 },
];

export const PaymentsTab = ({ details }: { details: TenantDetails }) => (
  <GroupForm
    details={details}
    group="payment"
    fields={PAYMENT_FIELDS}
    note="The store's own Razorpay account. The secret never comes here: it lives in the server environment as TENANT_SECRET_<reference>."
  />
);

export const EmailTab = ({ details }: { details: TenantDetails }) => (
  <GroupForm details={details} group="mail" fields={MAIL_FIELDS} note="Links in verification and password-reset emails start with this address." />
);

export const LegalTab = ({ details }: { details: TenantDetails }) => (
  <GroupForm
    details={details}
    group="legal"
    fields={LEGAL_FIELDS}
    note="Raising a version makes shoppers (Terms, Privacy) or store admins (admin agreement) accept again."
  />
);
