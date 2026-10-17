import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.tsx'
// Self-hosted fonts: no request to Google on page load (compliance-consent.md §4).
import "@fontsource-variable/inter"
import "@fontsource-variable/bricolage-grotesque/opsz.css"
import "./styles/app.scss"
import { Provider } from 'react-redux'
import { store } from './redux/store.ts'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Provider store={store}>
      <App />
    </Provider>
  </React.StrictMode>,
)
