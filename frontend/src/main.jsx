import React from 'react'
import ReactDOM from 'react-dom/client'
import { registerSW } from 'virtual:pwa-register'
import App from './App.jsx'
import { runWhenIdle } from './swUpdateGate.js'
import './index.css'

// registerType: 'autoUpdate' (vite.config.js) only silences the native
// "install new SW?" browser prompt — it doesn't make an already-open tab pick
// up the new code by itself. Without calling registerSW() here, updates were
// installing in the background but never reaching tabs that were already
// open, so users could be stuck on a stale build indefinitely after a deploy.
const updateSW = registerSW({
  onNeedRefresh() {
    runWhenIdle(() => updateSW(true))
  },
})

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
