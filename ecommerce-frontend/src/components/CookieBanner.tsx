import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { policyPath } from "../legal/policies";
import { brand } from "../tenant/tenant";
import {
  ALL,
  COOKIE_SETTINGS_EVENT,
  ConsentCategory,
  ConsentChoice,
  NONE,
  currentChoice,
  saveConsent,
} from "../utils/consent";
import { surfaceFor } from "../utils/routes";

const OPTIONS: { key: ConsentCategory; label: string; hint: string }[] = [
  { key: "functional", label: "Functional", hint: "remembers preferences such as display settings." },
  { key: "analytics", label: "Analytics", hint: "counts visits and what is viewed so we can improve the shop (Google Analytics)." },
  { key: "marketing", label: "Marketing", hint: "measures our ads with Google and Meta, who may use it to show you our ads elsewhere." },
];

/**
 * Opt-in cookie banner (compliance-consent.md §5, C6): a bottom bar with no overlay, so the shop stays usable.
 * Reject all, Customise and Accept all carry equal weight; nothing is ticked by default.
 */
const CookieBanner = () => {
  const { pathname } = useLocation();
  const [open, setOpen] = useState(() => currentChoice() === null);
  const [customising, setCustomising] = useState(false);
  const [draft, setDraft] = useState<ConsentChoice>(() => currentChoice() ?? NONE);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusOnOpen = useRef(false);

  useEffect(() => {
    const reopen = () => {
      setDraft(currentChoice() ?? NONE);
      setCustomising(true);
      setOpen(true);
      focusOnOpen.current = true;
    };
    window.addEventListener(COOKIE_SETTINGS_EVENT, reopen);
    return () => window.removeEventListener(COOKIE_SETTINGS_EVENT, reopen);
  }, []);

  // Reopened from the footer: move focus here so keyboard and screen-reader users land on it.
  useEffect(() => {
    if (open && focusOnOpen.current) {
      focusOnOpen.current = false;
      headingRef.current?.focus();
    }
  }, [open, customising]);

  if (!open || surfaceFor(pathname) === "admin") return null;

  const decide = (choice: ConsentChoice) => {
    saveConsent(choice);
    setOpen(false);
    setCustomising(false);
  };

  return (
    <section className="cookie-banner" aria-labelledby="cookie-banner-title">
      <h2 id="cookie-banner-title" ref={headingRef} tabIndex={-1}>Cookies on {brand.name}</h2>
      <p>
        We store what the shop needs to work. With your permission we&apos;d also use analytics and marketing
        cookies. You can change your choice at any time under Cookie settings at the bottom of every page.{" "}
        <Link to={policyPath("cookies")}>Cookie notice</Link>
      </p>

      {customising && (
        <fieldset className="cookie-banner__options">
          <legend className="sr-only">Cookie categories</legend>
          <label className="checkbox">
            <input type="checkbox" checked disabled />
            <span><strong>Strictly necessary</strong>: sign-in, cart and this choice. Always on.</span>
          </label>
          {OPTIONS.map(({ key, label, hint }) => (
            <label key={key} className="checkbox">
              <input
                type="checkbox"
                checked={draft[key]}
                onChange={(e) => setDraft((d) => ({ ...d, [key]: e.target.checked }))}
              />
              <span><strong>{label}</strong>: {hint}</span>
            </label>
          ))}
        </fieldset>
      )}

      <div className="cookie-banner__actions">
        <button type="button" onClick={() => decide(NONE)}>Reject all</button>
        {customising ? (
          <button type="button" onClick={() => decide(draft)}>Save choices</button>
        ) : (
          <button type="button" onClick={() => setCustomising(true)}>Customise</button>
        )}
        <button type="button" onClick={() => decide(ALL)}>Accept all</button>
      </div>
    </section>
  );
};

export default CookieBanner;
