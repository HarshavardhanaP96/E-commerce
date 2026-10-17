import { useEffect, useRef, useState } from "react";
import toast from "react-hot-toast";
import { useDispatch } from "react-redux";
import { ConsentPurpose, useDecideConsentMutation, useMyConsentQuery } from "../redux/api/consentAPI";
import { errorMessage } from "../redux/api/baseQuery";
import { userNotExist } from "../redux/reducer/userReducer";
import { POLICIES, policyPath } from "../legal/policies";

const POLICY_OF: Record<ConsentPurpose, keyof typeof POLICIES | null> = {
  terms: "terms",
  privacy: "privacy",
  marketing_email: null,
};

/**
 * Blocks a signed-in user until they accept the current Terms and Privacy Policy (compliance-consent.md C4).
 * Shown after a material policy change, and once to accounts created before consent was recorded.
 */
const ConsentPrompt = ({ userId }: { userId: string }) => {
  const dispatch = useDispatch();
  const { data } = useMyConsentQuery(userId);
  const [decide, { isLoading }] = useDecideConsentMutation();
  const [agreed, setAgreed] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const pending = data?.pending ?? [];
  const firstTime = !!data && Object.keys(data.consents).length === 0;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (pending.length > 0 && dialog && !dialog.open) dialog.showModal();
  }, [pending.length]);

  if (pending.length === 0) return null;

  const accept = async () => {
    try {
      for (const purpose of pending) {
        await decide({ purpose, granted: true, source: "reprompt" }).unwrap();
      }
      dialogRef.current?.close();
      toast.success("Thanks, you're all set");
    } catch (err) {
      toast.error(errorMessage(err, "Could not save your choice"));
    }
  };

  return (
    // Escape is disabled: the only ways out are accepting or signing out.
    <dialog ref={dialogRef} className="consent-prompt" onCancel={(e) => e.preventDefault()}
            aria-labelledby="consent-prompt-title">
      <h2 id="consent-prompt-title">{firstTime ? "Please review our policies" : "We've updated our policies"}</h2>
      <p>
        {firstTime
          ? "Before you continue, please read and accept the following:"
          : "Please read the updated versions before you continue:"}
      </p>
      <ul>
        {pending.map((purpose) => {
          const slug = POLICY_OF[purpose];
          return slug ? (
            <li key={purpose}>
              <a href={policyPath(slug)} target="_blank" rel="noreferrer">{POLICIES[slug].title}</a>
              {" "}(last updated {POLICIES[slug].updated})
            </li>
          ) : null;
        })}
      </ul>
      <label className="checkbox">
        <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
        <span>I have read and agree to the documents above.</span>
      </label>
      <div className="consent-prompt__actions">
        <button type="button" className="consent-prompt__secondary" onClick={() => dispatch(userNotExist())}>
          Sign out
        </button>
        <button type="button" disabled={!agreed || isLoading} onClick={accept}>
          {isLoading ? "Saving…" : "Accept and continue"}
        </button>
      </div>
    </dialog>
  );
};

export default ConsentPrompt;
