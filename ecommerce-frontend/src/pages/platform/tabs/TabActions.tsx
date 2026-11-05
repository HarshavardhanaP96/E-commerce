/** Save / Discard row shared by the workspace tabs, with the API's field error above it. */
const TabActions = ({ dirty, saving, error, onDiscard }: { dirty: boolean; saving: boolean; error: string | null; onDiscard: () => void }) => (
  <div className="span-2 tab-actions">
    {error && (
      <p className="field-error" role="alert">
        {error}
      </p>
    )}
    <button type="button" className="btn" disabled={!dirty || saving} onClick={onDiscard}>
      Discard
    </button>
    <button type="submit" className="btn btn--primary" disabled={!dirty || saving}>
      {saving ? "Saving…" : "Save"}
    </button>
  </div>
);

export default TabActions;
