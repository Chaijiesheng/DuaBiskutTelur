// Bridges React's "am I mid-action" phase state to the service-worker update
// handler in main.jsx (which lives outside the component tree) — a pending
// update only applies once the app is back in a safe phase, so a background
// deploy never yanks the page out from under an in-flight photo analysis or
// an open barcode scan.
let busy = false
let onIdle = null

export function setUpdateGateBusy(value) {
  busy = value
  if (!busy && onIdle) {
    const fn = onIdle
    onIdle = null
    fn()
  }
}

export function runWhenIdle(fn) {
  if (busy) {
    onIdle = fn
  } else {
    fn()
  }
}
