import { ReactNode } from "react";

type Props = { title: string; description?: string; actions?: ReactNode };

/** The top of every admin page: what this page is, one line on what it's for, and its main actions. */
const PageHeader = ({ title, description, actions }: Props) => (
  <header className="page-header">
    <div>
      <h1>{title}</h1>
      {description && <p>{description}</p>}
    </div>
    {actions && <div className="page-header__actions">{actions}</div>}
  </header>
);

export default PageHeader;
