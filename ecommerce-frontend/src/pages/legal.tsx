import { Link, Navigate, useParams } from "react-router-dom";
import { POLICIES, POLICY_LINKS, PolicySlug, policyPath } from "../legal/policies";

const formatDate = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "long", year: "numeric" });

const Legal = () => {
  const { slug } = useParams();
  const policy = POLICIES[slug as PolicySlug];
  if (!policy) return <Navigate to={policyPath("terms")} replace />;

  return (
    <div className="legal">
      <nav aria-label="Policies">
        {POLICY_LINKS.map((s) => (
          <Link key={s} to={policyPath(s)} aria-current={s === policy.slug ? "page" : undefined}>
            {POLICIES[s].title}
          </Link>
        ))}
      </nav>
      <article>
        <h1>{policy.title}</h1>
        <p className="legal__updated">Last updated {formatDate(policy.updated)}</p>
        <p>{policy.intro}</p>
        {policy.sections.map((section) => (
          <section key={section.heading}>
            <h2>{section.heading}</h2>
            {section.body.length === 1 ? (
              <p>{section.body[0]}</p>
            ) : (
              <ul>
                {section.body.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            )}
          </section>
        ))}
      </article>
    </div>
  );
};

export default Legal;
