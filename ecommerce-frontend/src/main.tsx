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
