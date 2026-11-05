import { lazy, Suspense, useState } from "react";
import toast from "react-hot-toast";
import {
  LuCreditCard,
  LuExternalLink,
  LuGauge,
  LuLayoutDashboard,
  LuMail,
  LuScale,
  LuStore,
  LuToggleRight,
} from "react-icons/lu";
import { Link, Navigate, useParams } from "react-router-dom";
import ConfirmDialog from "../../components/admin/ConfirmDialog";
import DetailLayout from "../../components/admin/DetailLayout";
import Tabs, { TabDef } from "../../components/admin/Tabs";
import { errorMessage } from "../../redux/api/baseQuery";
import { downloadTenantExport, useSetTenantStatusMutation, useTenantQuery, useTenantsQuery } from "../../redux/api/platformAPI";
import PlatformSidebar, { PlatformShell } from "./PlatformSidebar";
import { MOVE_COPY, StatusAction, statusMoves } from "./statusMoves";
import { findBySlug, typeLabel } from "./storeList";
import OverviewTab from "./tabs/OverviewTab";

const ProfileTab = lazy(() => import("./tabs/ProfileTab"));
const FeaturesTab = lazy(() => import("./tabs/FeaturesTab"));
const LimitsTab = lazy(() => import("./tabs/LimitsTab"));
const PaymentsTab = lazy(() => import("./tabs/SimpleTabs").then((m) => ({ default: m.PaymentsTab })));
const EmailTab = lazy(() => import("./tabs/SimpleTabs").then((m) => ({ default: m.EmailTab })));
const LegalTab = lazy(() => import("./tabs/SimpleTabs").then((m) => ({ default: m.LegalTab })));

const WORKSPACE_TABS: TabDef[] = [
  { key: "overview", label: "Overview", Icon: LuLayoutDashboard },
  { key: "profile", label: "Profile", Icon: LuStore },
  { key: "features", label: "Features", Icon: LuToggleRight },
  { key: "payments", label: "Payments", Icon: LuCreditCard },
  { key: "email", label: "Email", Icon: LuMail },
  { key: "legal", label: "Legal", Icon: LuScale },
  { key: "limits", label: "Limits", Icon: LuGauge },
];

/** One store: header actions, status moves and linkable configuration tabs. */
const StoreWorkspace = () => {
  const { slug, tab = "overview" } = useParams();
  const { data: list, isLoading: listLoading } = useTenantsQuery();
  const summary = findBySlug(list?.tenants, slug);
  const { data: details, isLoading, isError, error } = useTenantQuery(summary?.id ?? "", { skip: !summary });
  const [setStatus, { isLoading: moving }] = useSetTenantStatusMutation();
  const [move, setMove] = useState<StatusAction | null>(null);

  if (!WORKSPACE_TABS.some((t) => t.key === tab)) return <Navigate to={`/platform/stores/${slug}`} replace />;
  if (listLoading || (summary && isLoading)) {
    return (
      <PlatformShell>
        <p>Loading…</p>
      </PlatformShell>
    );
  }
  if (!summary || summary.status === "DELETED") {
    return (
      <PlatformShell>
        <div className="card">
          <h1>Store not found</h1>
          <p>
            <Link to="/platform/stores">Back to stores</Link>
          </p>
        </div>
      </PlatformShell>
    );
  }
  if (isError || !details) {
    return (
      <PlatformShell>
        <p role="alert">{errorMessage(error)}</p>
      </PlatformShell>
    );
  }

  const t = details.tenant;
  const moves = statusMoves(t);
  const confirmMove = async () => {
    if (!move) return;
    const res = await setStatus({ id: t.id, action: move });
    if ("error" in res) toast.error(errorMessage(res.error));
    else toast.success(res.data.message);
    setMove(null);
  };
  const exportStore = async () => {
    try {
      await downloadTenantExport(t);
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  const panels: Record<string, JSX.Element> = {
    overview: <OverviewTab details={details} />,
    profile: <ProfileTab details={details} />,
    features: <FeaturesTab details={details} />,
    payments: <PaymentsTab details={details} />,
    email: <EmailTab details={details} />,
    legal: <LegalTab details={details} />,
    limits: <LimitsTab details={details} />,
  };

  return (
    <DetailLayout
      sidebar={<PlatformSidebar />}
      title={t.name}
      parent={{ to: "/platform/stores", label: "Stores" }}
      subtitle={
        <span>
          {t.slug} · <span className="badge">{typeLabel(t.type)}</span> ·{" "}
          <span className={`badge badge--${t.status.toLowerCase()}`}>{t.status.toLowerCase().replace(/_/g, " ")}</span>
        </span>
      }
      actions={
        <>
          {t.domains[0] && (
            <a className="btn" href={`https://${t.domains[0]}`} target="_blank" rel="noreferrer">
              <LuExternalLink aria-hidden /> Open storefront
            </a>
          )}
          <button className="btn" onClick={exportStore}>
            Export
          </button>
          {moves.length > 0 && (
            <select className="btn" aria-label="Change status" value="" onChange={(e) => setMove(e.target.value as StatusAction)}>
              <option value="" disabled>
                Status…
              </option>
              {moves.map((m) => (
                <option key={m} value={m}>
                  {MOVE_COPY[m].label}
                </option>
              ))}
            </select>
          )}
        </>
      }
    >
      <Tabs
        tabs={WORKSPACE_TABS}
        active={tab}
        label="Store sections"
        hrefFor={(k) => `/platform/stores/${t.slug}${k === "overview" ? "" : `/${k}`}`}
      />
      <div role="tabpanel" aria-labelledby={`tab-${tab}`} className="platform-scroll">
        {/* key: a different store or tab starts its form fresh from the loaded values */}
        <Suspense key={`${t.id}-${tab}`} fallback={<p>Loading…</p>}>
          {panels[tab]}
        </Suspense>
      </div>
      {move && (
        <ConfirmDialog
          open
          title={MOVE_COPY[move].title}
          body={<p>{MOVE_COPY[move].body(t.name)}</p>}
          confirmLabel={MOVE_COPY[move].label}
          danger={MOVE_COPY[move].danger}
          requireText={move === "offboard" ? t.slug : undefined}
          busy={moving}
          onConfirm={confirmMove}
          onCancel={() => setMove(null)}
        />
      )}
    </DetailLayout>
  );
};

export default StoreWorkspace;
