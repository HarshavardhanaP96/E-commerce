import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { LuChevronDown, LuSearch } from "react-icons/lu";
import { HELP_ARTICLES, HELP_CATEGORIES, HelpCategory } from "../help/articles";
import { BUSINESS } from "../legal/policies";

/** Searchable FAQ. Native <details> keeps it keyboard- and screen-reader-friendly without extra state. */
const Help = () => {
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<HelpCategory | "all">("all");

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    return HELP_ARTICLES.filter(
      (a) =>
        (category === "all" || a.category === category) &&
        (!q || a.question.toLowerCase().includes(q) || a.answer.some((line) => line.toLowerCase().includes(q)))
    );
  }, [query, category]);

  return (
    <div className="help">
      <header className="help__head">
        <h1>How can we help?</h1>
        <label className="help__search">
          <LuSearch aria-hidden />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search orders, payments, shipping…"
            aria-label="Search help articles"
          />
        </label>
      </header>

      <div className="help__chips" role="group" aria-label="Topic">
        {[{ id: "all" as const, label: "All" }, ...HELP_CATEGORIES].map((c) => (
          <button
            key={c.id}
            type="button"
            aria-pressed={category === c.id}
            className={category === c.id ? "is-active" : undefined}
            onClick={() => setCategory(c.id)}
          >
            {c.label}
          </button>
        ))}
      </div>

      <div className="help__list" aria-live="polite">
        {results.length === 0 ? (
          <p className="help__empty">
            Nothing matches “{query}”. Try a different word, or <Link to="/legal/contact">contact us</Link>.
          </p>
        ) : (
          results.map((a) => (
            <details key={a.id} className="help__item">
              <summary>
                <span>{a.question}</span>
                <LuChevronDown aria-hidden />
              </summary>
              <div className="help__answer">
                {a.answer.map((line) => (
                  <p key={line}>{line}</p>
                ))}
                {a.link && <Link to={a.link.to}>{a.link.label} →</Link>}
              </div>
            </details>
          ))
        )}
      </div>

      <aside className="help__contact">
        <div>
          <h2>Still need help?</h2>
          <p>Email us at <a href={`mailto:${BUSINESS.email}`}>{BUSINESS.email}</a> and include your order ID if you have one.</p>
        </div>
        <a className="help__mail" href={`mailto:${BUSINESS.email}`}>Email support</a>
      </aside>
    </div>
  );
};

export default Help;
