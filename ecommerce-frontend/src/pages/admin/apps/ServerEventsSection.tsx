import { InputHTMLAttributes, KeyboardEvent, useEffect, useMemo, useState } from "react";
import toast from "react-hot-toast";
import { LuAlertCircle } from "react-icons/lu";
import { errorMessage } from "../../../redux/api/baseQuery";
import {
  Delivery, Integration, Provider, TestVerdict, useLazyDeliveriesQuery, useSaveServerSettingsMutation, useSendTestEventMutation,
} from "../../../redux/api/marketingAPI";
import { formatDateTime } from "../../../utils/features";
import { DELIVERY_LABEL, SERVER, ServerDraft, serverDraftFrom, serverFieldError, toServerChange, validateServer } from "./serverEventsForm";

// The section sits inside the browser-tag <form>: Enter must not submit that form.
const noSubmit = (e: KeyboardEvent) => {
  if (e.key === "Enter") e.preventDefault();
};

/** Server-side conversions for one provider card (marketing phase 3). Renders nothing for a phase-1 server. */
const ServerEventsSection = ({ provider, integration }: { provider: Provider; integration?: Integration }) => {
  const def = SERVER[provider];
  const saved = useMemo(() => serverDraftFrom(integration), [integration]);
  const [draft, setDraft] = useState<ServerDraft>(saved);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [top, setTop] = useState<string | null>(null);
  const [verdict, setVerdict] = useState<TestVerdict | null>(null);
  const [more, setMore] = useState<{ rows: Delivery[]; page: number; totalPage: number } | null>(null);
  const [save, { isLoading: saving }] = useSaveServerSettingsMutation();
  const [sendTest, { isLoading: testing }] = useSendTestEventMutation();
  const [loadPage, { isFetching: loading }] = useLazyDeliveriesQuery();

  useEffect(() => setDraft(saved), [saved]);

  const server = integration?.server;
  if (!integration || !server) return null;

  const id = (key: string) => `${provider}-server-${key}`;
  const hasId = !!integration.browser[def.idKey];
  const dirty = draft.enabled !== saved.enabled || draft.secret !== "" || draft.testEventCode !== saved.testEventCode;
  const canTest = server.hasSecret && (!def.testCode || !!server.testEventCode);
  const rows = more?.rows ?? integration.deliveries ?? [];
  const canLoadMore = more ? more.page < more.totalPage : rows.length >= 10;

  const submit = async () => {
    const found = validateServer(draft, def, server.hasSecret);
    setErrors(found);
    setTop(null);
    if (Object.keys(found).length) return;
    const res = await save({ provider, server: toServerChange(draft, def) });
    if ("error" in res) {
      const e = serverFieldError(errorMessage(res.error));
      return e.key ? setErrors({ [e.key]: e.text }) : setTop(e.text);
    }
    toast.success("Server settings saved");
  };

  const test = async () => {
    setVerdict(null);
    setTop(null);
    const res = await sendTest(provider);
    if ("error" in res) return setTop(errorMessage(res.error));
    setVerdict(res.data.test);
  };

  const loadMore = async () => {
    const page = more ? more.page + 1 : 1;
    const res = await loadPage({ provider, page });
    if (res.data) setMore({ rows: page === 1 ? res.data.deliveries : [...rows, ...res.data.deliveries], page, totalPage: res.data.totalPage });
    else if (res.error) setTop(errorMessage(res.error));
  };

  const input = (key: "secret" | "testEventCode", label: string, extra: InputHTMLAttributes<HTMLInputElement>) => (
    <label htmlFor={id(key)}>
      {label}
      <input id={id(key)} value={draft[key]} autoComplete="off" spellCheck={false} onKeyDown={noSubmit}
        aria-invalid={!!errors[key]} aria-describedby={errors[key] ? id(`${key}-error`) : undefined}
        onChange={(e) => setDraft((d) => ({ ...d, [key]: e.target.value }))} {...extra} />
      {errors[key] && <span id={id(`${key}-error`)} className="field-error"><LuAlertCircle aria-hidden /> {errors[key]}</span>}
    </label>
  );

  return (
    <fieldset className="span-2 server-events" aria-labelledby={id("title")}>
      <h3 id={id("title")}>Server events</h3>
      {!server.available ? (
        <p className="store-settings__note">Sending purchases from the server isn't available on this platform yet.</p>
      ) : !hasId ? (
        <p className="store-settings__note">Save your {def.idLabel} first. Then you can also send purchases from your server.</p>
      ) : (
        <>
          <p className="store-settings__hint">{def.intro}</p>
          {integration.status === "ERROR" && integration.lastError && (
            <p className="field-error" role="alert">
              <LuAlertCircle aria-hidden /> {integration.lastError.message} ({formatDateTime(integration.lastError.at)})
            </p>
          )}
          {top && <p className="field-error" role="alert"><LuAlertCircle aria-hidden /> {top}</p>}
          <label className="check" htmlFor={id("enabled")}>
            <input id={id("enabled")} type="checkbox" checked={draft.enabled}
              onChange={(e) => setDraft((d) => ({ ...d, enabled: e.target.checked }))} />
            Also send purchases from the server
          </label>
          {input("secret", def.secretLabel, { type: "password", maxLength: 512,
            placeholder: server.hasSecret ? "Saved. Paste a new one to replace it" : "" })}
          {def.testCode && input("testEventCode", "Test event code (optional)", { maxLength: 32, placeholder: "TEST12345" })}
          <div className="store-settings__actions">
            <button type="button" className="btn btn--primary" disabled={saving || !dirty} onClick={submit}>
              {saving ? "Saving…" : "Save server settings"}
            </button>
            <button type="button" className="btn" disabled={testing || !canTest} onClick={test}>
              {testing ? "Sending…" : "Send test event"}
            </button>
            {integration.lastSuccessAt && <span className="store-settings__note">Last sent {formatDateTime(integration.lastSuccessAt)}</span>}
          </div>
          {verdict && <p role="status" className={verdict.ok ? "store-settings__note" : "field-error"}>{verdict.message}</p>}
          <h4>Recent purchases sent</h4>
          {rows.length === 0 ? (
            <p className="store-settings__note">Nothing sent yet. Paid orders from shoppers who allowed {def.consent} cookies appear here.</p>
          ) : (
            <table className="server-events__log">
              <thead><tr><th scope="col">Order</th><th scope="col">Status</th><th scope="col">Tries</th><th scope="col">Queued</th><th scope="col">Note</th></tr></thead>
              <tbody>
                {rows.map((d) => (
                  <tr key={d.eventId}>
                    <td>{d.orderNumber ?? d.eventId}</td><td>{DELIVERY_LABEL[d.status]}</td><td>{d.attempts}</td>
                    <td>{formatDateTime(d.createdAt)}</td><td>{d.error?.message ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {canLoadMore && (
            <button type="button" className="btn" disabled={loading} onClick={loadMore}>{loading ? "Loading…" : "Show more"}</button>
          )}
        </>
      )}
    </fieldset>
  );
};

export default ServerEventsSection;
