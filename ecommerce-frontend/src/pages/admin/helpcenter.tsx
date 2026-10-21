import { useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { LuArrowLeft, LuArrowRight, LuLightbulb, LuSearch, LuAlertTriangle } from "react-icons/lu";
import AdminLayout from "../../components/admin/AdminLayout";
import { GUIDE_GROUPS, GUIDES, Guide, GuideBlock, guideBySlug } from "../../help/adminGuides";

const text = (g: Guide) =>
  [g.title, g.summary, ...g.body.map((b) => ("items" in b ? b.items.join(" ") : b.text))].join(" ").toLowerCase();

const Block = ({ block }: { block: GuideBlock }) => {
  switch (block.type) {
    case "p":
      return <p>{block.text}</p>;
    case "steps":
      return (
        <div>
          {block.title && <h3>{block.title}</h3>}
          <ol>
            {block.items.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ol>
        </div>
      );
    case "tip":
      return (
        <p className="kb-note kb-note--tip">
          <LuLightbulb aria-hidden />
          <span>{block.text}</span>
        </p>
      );
    case "warn":
      return (
        <p className="kb-note kb-note--warn">
          <LuAlertTriangle aria-hidden />
          <span>{block.text}</span>
        </p>
      );
  }
};

const Index = () => {
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const matches = useMemo(() => GUIDES.filter((g) => !q || text(g).includes(q)), [q]);

  return (
    <div className="kb">
      <header className="kb__head">
        <h1>Help &amp; guides</h1>
        <p>How-to guides for running the store from this admin panel.</p>
        <label className="kb__search">
          <LuSearch aria-hidden />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search guides…"
            aria-label="Search guides"
          />
        </label>
      </header>

      {matches.length === 0 ? (
        <p className="kb__empty">No guide matches “{query}”.</p>
      ) : (
        GUIDE_GROUPS.map((group) => {
          const items = matches.filter((g) => g.group === group);
          if (items.length === 0) return null;
          return (
            <section key={group} className="kb__group">
              <h3>{group}</h3>
              <div className="kb__cards">
                {items.map((g) => (
                  <Link key={g.slug} to={`/admin/help/${g.slug}`} className="kb__card">
                    <strong>{g.title}</strong>
                    <span>{g.summary}</span>
                  </Link>
                ))}
              </div>
            </section>
          );
        })
      )}
    </div>
  );
};

const Article = ({ guide }: { guide: Guide }) => (
  <div className="kb kb--article">
    <Link to="/admin/help" className="kb__back">
      <LuArrowLeft aria-hidden /> All guides
    </Link>
    <div className="kb__layout">
      <article className="card">
        <p className="kb__group-label">{guide.group}</p>
        <h1>{guide.title}</h1>
        <p className="kb__summary">{guide.summary}</p>
        {guide.body.map((block, i) => (
          <Block key={i} block={block} />
        ))}
        {guide.openLabel && (
          <Link to={guide.openLabel.to} className="btn btn--primary kb__open">
            {guide.openLabel.label} <LuArrowRight aria-hidden />
          </Link>
        )}
      </article>

      {guide.related.length > 0 && (
        <aside className="kb__group kb__related">
          <h3>Related guides</h3>
          <div className="kb__cards">
            {guide.related.map((slug) => {
              const g = guideBySlug(slug);
              return (
                g && (
                  <Link key={slug} to={`/admin/help/${slug}`} className="kb__card">
                    <strong>{g.title}</strong>
                    <span>{g.summary}</span>
                  </Link>
                )
              );
            })}
          </div>
        </aside>
      )}
    </div>
  </div>
);

const HelpCenter = () => {
  const { slug } = useParams();
  const guide = guideBySlug(slug);
  if (slug && !guide) return <Navigate to="/admin/help" replace />;

  return <AdminLayout>{guide ? <Article guide={guide} /> : <Index />}</AdminLayout>;
};

export default HelpCenter;
