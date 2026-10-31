import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.tsx'
// Self-hosted fonts: no request to Google on page load (compliance-consent.md §4).
import "@fontsource-variable/inter"
import "@fontsource-variable/bricolage-grotesque/opsz.css"
import "./styles/app.scss"
import { Provider } from 'react-redux'
import { store } from './redux/store.ts'
import { loadTenantConfig } from './tenant/tenant.ts'
import { surfaceFor } from './utils/routes.ts'
import { captureAttribution } from './utils/attribution.ts'

// Tag the surface before anything paints, so a hard refresh on /admin never shows store colours.
document.documentElement.dataset.surface = surfaceFor(window.location.pathname)
captureAttribution()

// Which store is this? Load its name, theme and business details before the first render, so nothing flashes.
loadTenantConfig().finally(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <Provider store={store}>
        <App />
      </Provider>
    </React.StrictMode>,
  )
})
