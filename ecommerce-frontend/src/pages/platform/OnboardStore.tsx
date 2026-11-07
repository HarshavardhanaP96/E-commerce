import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { IconType } from "react-icons";
import { LuCpu, LuShirt, LuShoppingBasket, LuStore } from "react-icons/lu";
import { useNavigate } from "react-router-dom";
import PageHeader from "../../components/admin/PageHeader";
import { errorMessage } from "../../redux/api/baseQuery";
import { StoreTypeKey, useCreateTenantMutation, useStoreTypesQuery } from "../../redux/api/platformAPI";
import { fieldOf, presetSummary, slugFromName } from "./onboarding";
import { PlatformShell } from "./PlatformSidebar";

const ICONS: Record<StoreTypeKey, IconType> = {
  GENERAL: LuStore,
  FASHION: LuShirt,
  GROCERY: LuShoppingBasket,
  ELECTRONICS: LuCpu,
};

/** One page: pick a type, fill in the basics, optionally the first admin. */
const OnboardStore = () => {
  const { data: types, isLoading: typesLoading } = useStoreTypesQuery();
  const [create, { isLoading }] = useCreateTenantMutation();
  const navigate = useNavigate();
  const [type, setType] = useState<StoreTypeKey>("GENERAL");
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [domain, setDomain] = useState("");
  const [trial, setTrial] = useState(false);
  const [admin, setAdmin] = useState({ name: "", email: "", password: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});

  const chosen = types?.types.find((t) => t.key === type);
  const onName = (value: string) => {
    setName(value);
    if (!slugEdited) setSlug(slugFromName(value));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    const res = await create({
      slug: slug.trim(),
      name: name.trim(),
      domains: [domain.trim()],
      trial,
      type,
      admin: admin.email ? admin : undefined,
    });
    if ("error" in res) {
      const message = errorMessage(res.error);
      const field = fieldOf(message)?.split(".")[0]; // "admin.password" shows under the admin block
      if (field) setErrors({ [field === "domains" ? "domain" : field]: message });
      else toast.error(message);
      return;
    }
    toast.success(`${res.data.tenant.name} is ready`);
    navigate(`/platform/stores/${res.data.tenant.slug}`);
  };

  const err = (key: string) =>
    errors[key] && (
      <small className="field-error" role="alert">
        {errors[key]}
      </small>
    );

  return (
    <PlatformShell>
      <PageHeader title="Onboard store" description="Pick what kind of store it is; you can change everything later." />
      <form className="platform-scroll onboard" onSubmit={submit}>
        <fieldset className="card">
          <legend>Store type</legend>
          {typesLoading && <p>Loading…</p>}
          <div className="type-cards" role="radiogroup" aria-label="Store type">
            {types?.types.map((t) => {
              const Icon = ICONS[t.key];
              return (
                <label key={t.key} className={`type-card${type === t.key ? " type-card--active" : ""}`}>
                  <input type="radio" name="type" value={t.key} checked={type === t.key} onChange={() => setType(t.key)} />
                  <Icon aria-hidden className="type-card__icon" />
                  <strong>{t.label}</strong>
                  <span>{t.description}</span>
                </label>
              );
            })}
          </div>
          {chosen && <p className="onboard__summary">Sets: {presetSummary(chosen)}</p>}
          {err("type")}
        </fieldset>

        <fieldset className="card form-grid">
          <legend>Basics</legend>
          <label>
            Store name
            <input value={name} onChange={(e) => onName(e.target.value)} maxLength={80} required />
            {err("name")}
          </label>
          <label>
            Slug
            <input
              value={slug}
              onChange={(e) => {
                setSlug(e.target.value);
                setSlugEdited(true);
              }}
              pattern="[a-z0-9]([a-z0-9\-]{0,38}[a-z0-9])?"
              title="lowercase letters, digits, dashes"
              required
            />
            {err("slug")}
          </label>
          <label className="span-2">
            Domain
            <input value={domain} onChange={(e) => setDomain(e.target.value)} placeholder="shop.example.com" required />
            {err("domain")}
          </label>
          <label className="span-2 check">
            <input type="checkbox" checked={trial} onChange={(e) => setTrial(e.target.checked)} />
            Start as a trial (suspended when the trial ends unless activated)
          </label>
        </fieldset>

        <fieldset className="card form-grid">
          <legend>First admin (optional)</legend>
          <label>
            Name
            <input value={admin.name} onChange={(e) => setAdmin({ ...admin, name: e.target.value })} />
          </label>
          <label>
            Email
            <input type="email" value={admin.email} onChange={(e) => setAdmin({ ...admin, email: e.target.value })} />
          </label>
          <label className="span-2">
            Password (12+ characters)
            <input
              type="password"
              value={admin.password}
              minLength={12}
              autoComplete="new-password"
              onChange={(e) => setAdmin({ ...admin, password: e.target.value })}
              required={!!admin.email}
            />
          </label>
          {errors.admin && <div className="span-2">{err("admin")}</div>}
        </fieldset>

        <div className="onboard__actions">
          <button type="button" className="btn" onClick={() => navigate("/platform/stores")}>
            Cancel
          </button>
          <button className="btn btn--primary" disabled={isLoading}>
            {isLoading ? "Creating…" : "Create store"}
          </button>
        </div>
      </form>
    </PlatformShell>
  );
};

export default OnboardStore;
