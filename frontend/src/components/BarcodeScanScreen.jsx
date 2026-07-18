import { useEffect, useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { lookupBarcodeProduct } from '../api.js'

const FORMATS = ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128']
// Matches the backend's sanity ceiling — a real order is a few packages, not a pallet.
const MAX_SERVINGS = 20

export default function BarcodeScanScreen({ onConfirm, onCancel, onTakePhotoInstead }) {
  const { t } = useLanguage()
  const videoRef = useRef(null)
  const streamRef = useRef(null)
  const zxingReaderRef = useRef(null)
  const detectingRef = useRef(false)
  const [code, setCode] = useState(null)
  const [servings, setServings] = useState(1)
  const [cameraError, setCameraError] = useState(false)
  // Resolved before the servings prompt so the user confirms a quantity
  // against a named product (and the correct unit basis) instead of raw
  // barcode digits — 'loading' | 'error' | { name, unitLabel, perServing }
  const [product, setProduct] = useState(null)

  useEffect(() => {
    if (!code) return undefined
    let cancelled = false
    setProduct('loading')
    lookupBarcodeProduct(code)
      .then((info) => {
        if (!cancelled) setProduct(info)
      })
      .catch(() => {
        if (!cancelled) setProduct('error')
      })
    return () => {
      cancelled = true
    }
  }, [code])

  useEffect(() => {
    // Showing the confirm-servings screen — no camera needed. Also covers the
    // Cancel path back to scanning: re-running this effect on that transition
    // is what actually restarts the stream (see the `[code]` dep below).
    if (code) return undefined

    let cancelled = false
    let rafId = null
    // Reset the in-flight-detect mutex: a prior scan's tick() returns right
    // after calling handleDetected() without clearing this, so a restarted
    // loop would otherwise see it still `true` and never call detect() again.
    detectingRef.current = false

    const stop = () => {
      streamRef.current?.getTracks().forEach((track) => track.stop())
      streamRef.current = null
      zxingReaderRef.current?.reset()
      zxingReaderRef.current = null
      if (rafId) cancelAnimationFrame(rafId)
    }

    async function start() {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
        })
        if (cancelled) {
          stream.getTracks().forEach((track) => track.stop())
          return
        }
        streamRef.current = stream
        if (videoRef.current) {
          videoRef.current.srcObject = stream
          await videoRef.current.play()
        }

        if ('BarcodeDetector' in window) {
          const detector = new window.BarcodeDetector({ formats: FORMATS })
          const tick = async () => {
            if (cancelled || detectingRef.current) return
            detectingRef.current = true
            try {
              const results = await detector.detect(videoRef.current)
              if (results.length > 0 && !cancelled) {
                handleDetected(results[0].rawValue)
                return
              }
            } catch {
              /* transient decode error, keep trying */
            }
            detectingRef.current = false
            if (!cancelled) rafId = requestAnimationFrame(tick)
          }
          rafId = requestAnimationFrame(tick)
        } else {
          // Safari/iOS and older browsers: lazy-load the JS fallback decoder
          // only when it's actually needed, so it never touches the initial bundle.
          const { BrowserMultiFormatReader } = await import('@zxing/library')
          if (cancelled) return
          const reader = new BrowserMultiFormatReader()
          zxingReaderRef.current = reader
          reader.decodeFromVideoElement(videoRef.current, (result, err) => {
            if (result && !cancelled) {
              handleDetected(result.getText())
            }
          })
        }
      } catch {
        if (!cancelled) setCameraError(true)
      }
    }

    function handleDetected(value) {
      if (navigator.vibrate) navigator.vibrate(80)
      stop()
      setCode(value)
    }

    start()
    return () => {
      cancelled = true
      stop()
    }
  }, [code])

  if (code && product === 'loading') {
    return (
      <div className="flex flex-col items-center gap-4 pt-24 text-center">
        <span className="animate-pulse text-5xl">🔖</span>
        <p className="text-sm text-slate-500 dark:text-slate-400">{t('barcodeScan.loadingProduct')}</p>
      </div>
    )
  }

  if (code && product === 'error') {
    return (
      <div className="flex flex-col items-center gap-4 pt-20 text-center">
        <span className="text-5xl">🔖</span>
        <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">{t('error.BARCODE_NOT_FOUND.title')}</h2>
        <p className="max-w-xs text-sm text-slate-500 dark:text-slate-400">{t('error.BARCODE_NOT_FOUND.body')}</p>
        <div className="mt-2 flex gap-3">
          <button
            onClick={() => setCode(null)}
            className="rounded-xl border border-slate-300 px-5 py-2.5 text-sm font-semibold text-slate-600 dark:border-slate-600 dark:text-slate-300"
          >
            {t('barcodeScan.cancel')}
          </button>
          <button
            onClick={onTakePhotoInstead}
            className="rounded-xl bg-grade-aplus px-5 py-2.5 text-sm font-semibold text-white shadow"
          >
            {t('barcodeScan.takePhotoInstead')}
          </button>
        </div>
      </div>
    )
  }

  if (code && product) {
    return (
      <div className="flex flex-col items-center gap-6 pt-16">
        <span className="text-5xl">🔖</span>
        <div className="text-center">
          <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">{product.name}</h2>
          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
            {product.perServing ? t('barcodeScan.confirmServings') : t('barcodeScan.confirmUnits')}
          </p>
        </div>

        <div className="flex items-center gap-4">
          <StepperButton
            label="-"
            onClick={() => setServings((v) => Math.max(0.5, Math.round((v - 0.5) * 10) / 10))}
          />
          <span className="w-16 text-center text-2xl font-bold text-slate-900 dark:text-slate-100">{servings}</span>
          <StepperButton
            label="+"
            onClick={() => setServings((v) => Math.min(MAX_SERVINGS, Math.round((v + 0.5) * 10) / 10))}
          />
        </div>

        <div className="flex w-full max-w-xs flex-col gap-3">
          <button
            onClick={() => onConfirm(code, servings)}
            className="w-full rounded-2xl bg-grade-aplus py-3.5 text-sm font-bold text-white shadow-md active:scale-[0.98]"
          >
            {t('barcodeScan.confirm')}
          </button>
          <button
            onClick={() => setCode(null)}
            className="w-full py-2 text-center text-sm font-medium text-slate-500 dark:text-slate-400"
          >
            {t('barcodeScan.cancel')}
          </button>
        </div>
      </div>
    )
  }

  if (cameraError) {
    return (
      <div className="flex flex-col items-center gap-4 pt-20 text-center">
        <span className="text-5xl">📷</span>
        <p className="max-w-xs text-sm text-slate-500 dark:text-slate-400">{t('barcodeScan.cameraError')}</p>
        <div className="mt-2 flex gap-3">
          <button
            onClick={onCancel}
            className="rounded-xl border border-slate-300 px-5 py-2.5 text-sm font-semibold text-slate-600 dark:border-slate-600 dark:text-slate-300"
          >
            {t('barcodeScan.cancel')}
          </button>
          <button
            onClick={onTakePhotoInstead}
            className="rounded-xl bg-grade-aplus px-5 py-2.5 text-sm font-semibold text-white shadow"
          >
            {t('barcodeScan.takePhotoInstead')}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="relative -mx-4 -mt-2 h-[70vh] overflow-hidden bg-slate-900">
      <video ref={videoRef} muted playsInline className="h-full w-full object-cover" />

      <div className="absolute inset-x-8 top-1/2 h-28 -translate-y-1/2 rounded-xl">
        <div className="absolute -left-0.5 -top-0.5 h-5 w-5 rounded-tl-lg border-l-4 border-t-4 border-grade-aplus" />
        <div className="absolute -right-0.5 -top-0.5 h-5 w-5 rounded-tr-lg border-r-4 border-t-4 border-grade-aplus" />
        <div className="absolute -bottom-0.5 -left-0.5 h-5 w-5 rounded-bl-lg border-b-4 border-l-4 border-grade-aplus" />
        <div className="absolute -bottom-0.5 -right-0.5 h-5 w-5 rounded-br-lg border-b-4 border-r-4 border-grade-aplus" />
      </div>

      <p className="absolute inset-x-0 top-8 text-center text-sm font-medium text-white">{t('barcodeScan.title')}</p>
      <p className="absolute inset-x-0 top-[calc(50%+70px)] text-center text-xs text-slate-300">{t('barcodeScan.hint')}</p>

      <div className="absolute inset-x-0 bottom-6 flex flex-col items-center gap-2">
        <button onClick={onTakePhotoInstead} className="text-xs text-slate-300 underline underline-offset-2">
          {t('barcodeScan.fallback')}
        </button>
        <button onClick={onCancel} className="text-xs font-medium text-white">
          {t('barcodeScan.cancel')}
        </button>
      </div>
    </div>
  )
}

function StepperButton({ label, onClick }) {
  return (
    <button
      onClick={onClick}
      className="flex h-10 w-10 items-center justify-center rounded-full border-2 border-slate-200 text-lg font-bold text-slate-700 active:scale-95 dark:border-slate-600 dark:text-slate-200"
    >
      {label}
    </button>
  )
}
