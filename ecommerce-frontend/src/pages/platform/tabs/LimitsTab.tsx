import { FormEvent, useMemo, useState } from "react";
import { TenantDetails } from "../../../redux/api/platformAPI";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import { bytesFromSize, formatBytes, nullableInt, SizeUnit, sizeFromBytes } from "../workspaceForms";
import TabActions from "./TabActions";
import { useTabSave } from "./useTabSave";

const LimitsTab = ({ details }: { details: TenantDetails }) => {
  const { tenant, defaults, usage } = details;
  const initial = useMemo(() => {
    const size = sizeFromBytes(tenant.limits?.uploadBytes);
    return {
      products: tenant.limits?.products?.toString() ?? "",
      upload: size.value,
      unit: size.unit,
      rpm: tenant.limits?.requestsPerMinute?.toString() ?? "",
    };
  }, [tenant.limits]);
  const [form, setForm] = useState(initial);
  const { save, saving, error } = useTabSave(tenant.id);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await save({
      limits: {
        products: nullableInt(form.products),
        uploadBytes: bytesFromSize(form.upload, form.unit),
        requestsPerMinute: nullableInt(form.rpm),
      },
    });
  };

  return (
    <form className="card form-grid tab-form" onSubmit={submit}>
      <p className="span-2 muted">Leave a field empty to use the platform default shown in it.</p>
      <label>
        Products
        <input
          type="number"
          min={1}
          step={1}
          value={form.products}
          onChange={(e) => setForm({ ...form, products: e.target.value })}
          placeholder={`Default: ${defaults.limits.products}`}
        />
        {usage && <small className="muted">In use: {usage.products}</small>}
      </label>
      <label>
        Photo storage
        <span className="input-with-unit">
          <input
            type="number"
            min={0.1}
            step={0.1}
            value={form.upload}
            onChange={(e) => setForm({ ...form, upload: e.target.value })}
            placeholder={`Default: ${formatBytes(defaults.limits.uploadBytes)}`}
          />
          <select aria-label="Unit" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value as SizeUnit })}>
            <option value="MB">MB</option>
            <option value="GB">GB</option>
          </select>
        </span>
        {usage && <small className="muted">In use: {formatBytes(usage.uploadBytes)}</small>}
      </label>
      <label>
        Requests per minute
        <input
          type="number"
          min={1}
          step={1}
          value={form.rpm}
          onChange={(e) => setForm({ ...form, rpm: e.target.value })}
          placeholder={`Default: ${defaults.limits.requestsPerMinute}`}
        />
      </label>
      <TabActions dirty={dirty} saving={saving} error={error} onDiscard={() => setForm(initial)} />
    </form>
  );
};

export default LimitsTab;
