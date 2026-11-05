import { ReactNode, useEffect, useRef, useState } from "react";

type Props = {
  open: boolean;
  title: string;
  body: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  /** When set, the confirm button stays disabled until this exact text is typed (e.g. the store's slug). */
  requireText?: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

/** A modal confirm built on <dialog>: focus is trapped, Escape cancels. Replaces window.confirm in the consoles. */
const ConfirmDialog = ({ open, title, body, confirmLabel, danger, requireText, busy, onConfirm, onCancel }: Props) => {
  const ref = useRef<HTMLDialogElement>(null);
  const [typed, setTyped] = useState("");

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      setTyped("");
      dialog.showModal();
    }
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const blocked = busy || (requireText !== undefined && typed !== requireText);

  return (
    <dialog
      ref={ref}
      className="confirm-dialog"
      aria-labelledby="confirm-dialog-title"
      onCancel={(e) => {
        e.preventDefault();
        onCancel();
      }}
    >
      <h2 id="confirm-dialog-title">{title}</h2>
      <div className="confirm-dialog__body">{body}</div>
      {requireText !== undefined && (
        <label className="confirm-dialog__check">
          Type <code>{requireText}</code> to confirm
          <input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" spellCheck={false} />
        </label>
      )}
      <div className="confirm-dialog__actions">
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
        <button type="button" className={`btn ${danger ? "btn--danger" : "btn--primary"}`} onClick={onConfirm} disabled={blocked}>
          {busy ? "Working…" : confirmLabel}
        </button>
      </div>
    </dialog>
  );
};

export default ConfirmDialog;
