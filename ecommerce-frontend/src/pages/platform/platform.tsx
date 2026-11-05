import { lazy, Suspense, useEffect, useState } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import Loader from "../../components/admin/Loader";
import { PLATFORM_SIGNED_OUT, PLATFORM_TOKEN_KEY } from "../../redux/api/platformAPI";
import PlatformLogin from "./PlatformLogin";

const StoreList = lazy(() => import("./StoreList"));
const OnboardStore = lazy(() => import("./OnboardStore"));
const StoreWorkspace = lazy(() => import("./StoreWorkspace"));
const PlatformAudit = lazy(() => import("./PlatformAudit"));

/** Operator console for stores. Reachable on any domain; it signs in with platform credentials only. */
const Platform = () => {
  const [signedIn, setSignedIn] = useState(() => !!localStorage.getItem(PLATFORM_TOKEN_KEY));

  useEffect(() => {
    const out = () => setSignedIn(false);
    window.addEventListener(PLATFORM_SIGNED_OUT, out);
    return () => window.removeEventListener(PLATFORM_SIGNED_OUT, out);
  }, []);

  if (!signedIn) return <PlatformLogin onDone={() => setSignedIn(true)} />;
  return (
    <Suspense fallback={<Loader />}>
      <Routes>
        <Route index element={<Navigate to="stores" replace />} />
        <Route path="stores" element={<StoreList />} />
        <Route path="stores/new" element={<OnboardStore />} />
        <Route path="stores/:slug/:tab?" element={<StoreWorkspace />} />
        <Route path="audit" element={<PlatformAudit />} />
        <Route path="*" element={<Navigate to="stores" replace />} />
      </Routes>
    </Suspense>
  );
};

export default Platform;
