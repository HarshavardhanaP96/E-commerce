import { FormEvent, useEffect, useMemo, useState } from "react";
import toast from "react-hot-toast";
import { LuAlertCircle } from "react-icons/lu";
import { Link } from "react-router-dom";
import AdminLayout from "../../../components/admin/AdminLayout";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import {
  Integration,
  useDisconnectIntegrationMutation,
  useMarketingQuery,
  useSaveIntegrationMutation,
} from "../../../redux/api/marketingAPI";
import { formatDateTime } from "../../../utils/features";
import { PROVIDERS, ProviderDef, draftFrom, fieldError, toBrowser, validate } from "./marketingForm";
import FeedCard from "./FeedCard";
import ServerEventsSection from "./ServerEventsSection";

const HELP = "/admin/help/marketing";

/** One provider: status, its tag IDs with inline checks, Save and Disconnect. */
const ProviderCard = ({ def, integration }: { def: ProviderDef; integration?: Integration }) => {
  const [save, { isLoading: saving }] = useSaveIntegrationMutation();
  const [disconnect, { isLoading: removing }] = useDisconnectIntegrationMutation();
  const saved = useMemo(() => draftFrom(integration, def.fields), [integration, def.fields]);
  const [draft, setDraft] = useState(saved);
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [server, setServer] = useState<{ key?: string; text: string } | null>(null);

  useEffect(() => setDraft(saved), [saved]);

  const errors = validate(draft, def.fields);
  const errorFor = (key: string) => (checked[key] ? errors[key] : undefined) ?? (server?.key === key ? server.text : undefined);
  const dirty = JSON.stringify(draft) !== JSON.stringify(saved);
  const connected = !!integration && integration.status !== "NOT_CONNECTED";
  const busy = saving || removing;
  const titleId = `marketing-${def.provider}`;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setChecked(Object.fromEntries(def.fields.map((f) => [f.key, true])));
    setServer(null);
    if (Object.keys(errors).length > 0) return;
    const res = await save({ provider: def.provider, browser: toBrowser(draft, def.fields) });
    if ("error" in res) return setServer(fieldError(errorMessage(res.error), def.fields));
    setChecked({});
    toast.success(`${def.title} saved`);
  };

  const remove = async () => {
    if (!confirm(`Disconnect ${def.title}? Its tags stop loading on your store straight away.`)) return;
    setServer(null);
    const res = await disconnect(def.provider);
    if ("error" in res) return setServer({ text: errorMessage(res.error) });
    toast.success(`${def.title} disconnected`);
  };

  return (
    <form className="card form-grid marketing-card" aria-labelledby={titleId} onSubmit={submit} noValidate>
      <div className="span-2 marketing-card__head">
        <h2 id={titleId}>{def.title}</h2>
        <StatusBadge status={integration?.status ?? "NOT_CONNECTED"} />
      </div>
      <p className="span-2 store-settings__hint">
        {def.intro} <Link to={HELP}>Where do I find these IDs?</Link>
      </p>
      {server && !server.key && (
        <p className="span-2 field-error" role="alert">
          <LuAlertCircle aria-hidden /> {server.text}
        </p>
      )}

      {def.fields.map((f) => {
        const id = `${def.provider}-${f.key}`;
        const error = errorFor(f.key);
        return (
          <label key={f.key} htmlFor={id}>
            {f.label}
            <input
              id={id}
              value={draft[f.key]}
              placeholder={f.example}
              maxLength={40}
              spellCheck={false}
              autoComplete="off"
              aria-invalid={!!error}
              aria-describedby={error ? `${id}-error` : undefined}
              onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
              onBlur={() => setChecked((c) => ({ ...c, [f.key]: true }))}
            />
            {error && (
              <span id={`${id}-error`} className="field-error">
                <LuAlertCircle aria-hidden /> {error}
              </span>
            )}
          </label>
        );
      })}

      <div className="span-2 store-settings__actions">
        <button type="submit" className="btn btn--primary" disabled={busy || !dirty}>
          {saving ? "Saving…" : "Save"}
        </button>
        {connected && (
          <button type="button" className="btn btn--danger" disabled={busy} onClick={remove}>
            {removing ? "Disconnecting…" : "Disconnect"}
          </button>
        )}
        {integration?.updatedAt && (
          <span className="store-settings__note">Last saved {formatDateTime(integration.updatedAt)}</span>
        )}
      </div>
      <ServerEventsSection provider={def.provider} integration={integration} />
    </form>
  );
};

/** Admin → Marketing: the store's Google and Meta tag IDs. Shown only when the store's `marketing` feature is on. */
const Marketing = () => {
  const { data, isLoading, isError, error } = useMarketingQuery();

  return (
    <AdminLayout
      title="Marketing"
      description="Measure visits, add-to-carts and sales from your Google and Meta ads. Tags load only for shoppers who accept cookies."
    >
      <div className="store-settings">
        {isLoading && <p className="store-settings__note">Loading…</p>}
        {isError && <p className="store-settings__note" role="alert">{errorMessage(error)}</p>}
        {data &&
          PROVIDERS.map((def) => (
            <ProviderCard key={def.provider} def={def} integration={data.integrations.find((i) => i.provider === def.provider)} />
          ))}
        {data?.feed && <FeedCard feed={data.feed} />}
      </div>
    </AdminLayout>
  );
};

export default Marketing;
