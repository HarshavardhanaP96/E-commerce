import { FormEvent, useMemo, useState } from "react";
import { TenantDetails } from "../../../redux/api/platformAPI";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import { FeatureChoice, featureChoice, featuresPayload } from "../workspaceForms";
import TabActions from "./TabActions";
import { useTabSave } from "./useTabSave";

// Add a row when the backend Feature enum grows (it rejects unknown keys, so a missing row only hides a switch).
const FEATURES: { key: string; label: string; help: string }[] = [
  { key: "variants", label: "Product variants", help: "Products with sizes, colours or other options." },
  { key: "marketing", label: "Marketing", help: "Admin → Marketing: Google and Meta tags, product feed, server events." },
];

const CHOICES: FeatureChoice[] = ["default", "on", "off"];

const FeaturesTab = ({ details }: { details: TenantDetails }) => {
  const { tenant, defaults } = details;
  const initial = useMemo(
    () => Object.fromEntries(FEATURES.map((f) => [f.key, featureChoice(tenant.features, f.key)])) as Record<string, FeatureChoice>,
    [tenant.features],
  );
  const [choices, setChoices] = useState(initial);
  const { save, saving, error } = useTabSave(tenant.id);
  const dirty = JSON.stringify(choices) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await save({ features: featuresPayload(choices) });
  };

  return (
    <form className="card tab-form" onSubmit={submit}>
      {FEATURES.map((f) => (
        <fieldset key={f.key} className="feature-row">
          <legend>{f.label}</legend>
          <p className="muted">{f.help}</p>
          <div className="segmented" role="radiogroup" aria-label={f.label}>
            {CHOICES.map((c) => (
              <label key={c} className={`segmented__opt${choices[f.key] === c ? " segmented__opt--active" : ""}`}>
                <input type="radio" name={f.key} checked={choices[f.key] === c} onChange={() => setChoices({ ...choices, [f.key]: c })} />
                {c === "default" ? `Platform default (${defaults.features[f.key] ? "on" : "off"})` : c === "on" ? "On" : "Off"}
              </label>
            ))}
          </div>
        </fieldset>
      ))}
      <TabActions dirty={dirty} saving={saving} error={error} onDiscard={() => setChoices(initial)} />
    </form>
  );
};

export default FeaturesTab;
