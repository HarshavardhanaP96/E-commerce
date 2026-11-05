import { KeyboardEvent, MouseEvent, useRef } from "react";
import { IconType } from "react-icons";
import { Link, useNavigate } from "react-router-dom";
import { nextTabIndex } from "./tabsKeyboard";

export type TabDef = { key: string; label: string; Icon?: IconType };

type Props = {
  tabs: TabDef[];
  active: string;
  hrefFor: (key: string) => string;
  /** Accessible name of the tab list, e.g. "Store settings sections". */
  label: string;
  /** Return false to stay on the current tab; receives the tab the user asked for (e.g. to ask about unsaved changes). */
  canLeave?: (key: string) => boolean;
};

/** Linkable tabs: each tab is a URL, so Back and deep links work. The caller renders the matching tabpanel. */
const Tabs = ({ tabs, active, hrefFor, label, canLeave }: Props) => {
  const navigate = useNavigate();
  const refs = useRef<(HTMLAnchorElement | null)[]>([]);
  const current = Math.max(0, tabs.findIndex((t) => t.key === active));

  const go = (index: number) => {
    const tab = tabs[index];
    if (tab.key === active || (canLeave && !canLeave(tab.key))) return;
    navigate(hrefFor(tab.key));
    refs.current[index]?.focus();
  };

  const onKeyDown = (e: KeyboardEvent) => {
    const next = nextTabIndex(e.key, current, tabs.length);
    if (next === null) return;
    e.preventDefault();
    go(next);
  };

  const onClick = (e: MouseEvent, key: string) => {
    if (key !== active && canLeave && !canLeave(key)) e.preventDefault();
  };

  return (
    <div className="tabs" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
      {tabs.map(({ key, label: text, Icon }, i) => (
        <Link
          key={key}
          ref={(el) => {
            refs.current[i] = el;
          }}
          id={`tab-${key}`}
          role="tab"
          aria-selected={key === active}
          tabIndex={key === active ? 0 : -1}
          className={`tabs__tab${key === active ? " tabs__tab--active" : ""}`}
          to={hrefFor(key)}
          onClick={(e) => onClick(e, key)}
        >
          {Icon && <Icon aria-hidden />}
          {text}
        </Link>
      ))}
    </div>
  );
};

export default Tabs;
