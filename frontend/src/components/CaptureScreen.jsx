import { useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import Dialog from './Dialog.jsx'

// Touch-primary devices (phones/tablets) don't have a mouse to drag files with,
// so the drop-target hint only makes sense on pointer-fine (desktop) devices.
const supportsDragDrop =
  typeof window !== 'undefined' && window.matchMedia?.('(pointer: fine)').matches

export default function CaptureScreen({ online, onPhoto, onScanBarcode, onScanMenu }) {
  const { t } = useLanguage()
  // Two inputs because mobile browsers behave differently: with capture= the
  // camera opens directly, without it many phones jump straight to the file
  // picker. Our own chooser sheet lets the user decide explicitly.
  const cameraInputRef = useRef(null)
  const galleryInputRef = useRef(null)
  const [dragging, setDragging] = useState(false)
  const [showChooser, setShowChooser] = useState(false)
  // Menu scanning is just a photo routed differently — reuses the same camera/
  // gallery inputs above instead of adding a third pair, so this remembers
  // which handler the next picked file is actually for.
  const [pendingTarget, setPendingTarget] = useState('photo') // 'photo' | 'menu'

  const pick = (files) => {
    const file = files?.[0]
    if (file && file.type.startsWith('image/')) {
      if (pendingTarget === 'menu' && onScanMenu) {
        onScanMenu(file)
      } else {
        onPhoto(file)
      }
    }
  }

  const openPickerFor = (target) => {
    setPendingTarget(target)
    if (supportsDragDrop) {
      // Desktop: no camera to speak of — straight to the file dialog.
      galleryInputRef.current?.click()
    } else {
      setShowChooser(true)
    }
  }

  const openPicker = () => openPickerFor('photo')

  // Dismissing the sheet without picking anything shouldn't leave a stale
  // "menu" intent primed for the next time it's opened from the main circle.
  const closeChooser = () => {
    setShowChooser(false)
    setPendingTarget('photo')
  }

  return (
    <div className="flex flex-col items-center gap-6 pt-10">
      {!online && (
        <div className="w-full rounded-xl bg-amber-50 px-4 py-3 text-center text-sm text-amber-700 dark:bg-amber-900/20 dark:text-amber-400">
          {t('capture.offline')}
        </div>
      )}

      <button
        disabled={!online}
        onClick={openPicker}
        onDragOver={(e) => {
          if (!supportsDragDrop) return
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          if (!supportsDragDrop) return
          e.preventDefault()
          setDragging(false)
          // Dropping onto the main circle is always a meal photo — handled
          // directly rather than through pick()/pendingTarget, since setState
          // just before a synchronous read in the same handler wouldn't be
          // visible yet (stale closure), even though it works fine everywhere
          // else pick() is called (those all happen on a later render/tick).
          const file = e.dataTransfer.files?.[0]
          if (file && file.type.startsWith('image/')) {
            onPhoto(file)
          }
        }}
        className={`flex h-64 w-64 flex-col items-center justify-center gap-3 rounded-full border-4 shadow-xl transition
          ${
            online
              ? dragging
                ? 'border-grade-aplus bg-green-50 scale-105 dark:bg-green-900/30'
                : 'border-grade-aplus bg-white active:scale-95 dark:bg-slate-800'
              : 'cursor-not-allowed border-slate-200 bg-slate-100 opacity-60 dark:border-slate-700 dark:bg-slate-800'
          }`}
      >
        <span className="text-6xl">🍛</span>
        <span className="text-lg font-bold text-slate-800 dark:text-slate-100">{t('capture.snap')}</span>
        <span className="px-6 text-center text-xs text-slate-500 dark:text-slate-400">
          {t('capture.hint')}
          {online && supportsDragDrop ? t('capture.hintDrop') : ''}
        </span>
      </button>

      {/* capture="environment" -> rear camera opens directly */}
      <input
        ref={cameraInputRef}
        type="file"
        accept="image/*"
        capture="environment"
        className="hidden"
        onChange={(e) => {
          pick(e.target.files)
          e.target.value = ''
        }}
      />
      {/* no capture attribute -> gallery / file picker */}
      <input
        ref={galleryInputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(e) => {
          pick(e.target.files)
          e.target.value = ''
        }}
      />

      {/* The chooser sheet below (with its own barcode option) never opens on
          pointer-fine devices — openPicker() jumps straight to the file
          dialog there — so without this, desktop users have no way to reach
          barcode scanning at all. Shown everywhere for discoverability. */}
      <div className="flex flex-wrap justify-center gap-2">
        {onScanBarcode && (
          <button
            onClick={onScanBarcode}
            className="flex items-center gap-1.5 rounded-full border border-green-200 bg-green-50 px-4 py-2 text-xs font-semibold text-green-800 active:scale-95 dark:border-green-900/40 dark:bg-green-900/10 dark:text-green-400"
          >
            🔖 {t('capture.scanBarcode')}
          </button>
        )}
        {onScanMenu && (
          <button
            disabled={!online}
            onClick={() => openPickerFor('menu')}
            className={`flex items-center gap-1.5 rounded-full border px-4 py-2 text-xs font-semibold active:scale-95 ${
              online
                ? 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900/40 dark:bg-amber-900/10 dark:text-amber-400'
                : 'cursor-not-allowed border-slate-200 bg-slate-100 text-slate-400 opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-500'
            }`}
          >
            📋 {t('capture.scanMenu')}
          </button>
        )}
      </div>

      <p className="max-w-xs text-center text-xs leading-relaxed text-slate-500 dark:text-slate-400">
        {t('capture.footer')}
      </p>

      {showChooser && (
        <Dialog
          onClose={closeChooser}
          ariaLabel={pendingTarget === 'menu' ? t('capture.scanMenu') : t('capture.chooserTitle')}
          closeOnBackdrop
          overlayClassName="fixed inset-0 z-30 flex items-end justify-center bg-black/40"
          panelClassName="w-full max-w-md rounded-t-3xl bg-white p-5 pb-8 dark:bg-slate-800"
        >
            <div className="mx-auto mb-4 h-1 w-10 rounded-full bg-slate-200 dark:bg-slate-600" />
            <p className="mb-3 text-center text-sm font-semibold text-slate-800 dark:text-slate-100">
              {pendingTarget === 'menu' ? t('capture.scanMenu') : t('capture.chooserTitle')}
            </p>
            {onScanBarcode && pendingTarget !== 'menu' && (
              <button
                onClick={() => {
                  setShowChooser(false)
                  onScanBarcode()
                }}
                className="mb-3 flex w-full items-center gap-3 rounded-2xl border border-green-200 bg-green-50 px-4 py-3 text-left active:scale-[0.99] dark:border-green-900/40 dark:bg-green-900/10"
              >
                <span className="text-2xl">🔖</span>
                <span>
                  <span className="block text-sm font-bold text-green-800 dark:text-green-400">
                    {t('capture.scanBarcode')}
                  </span>
                  <span className="block text-xs text-green-700/70 dark:text-green-500/70">
                    {t('capture.scanBarcodeHint')}
                  </span>
                </span>
              </button>
            )}
            {onScanMenu && pendingTarget !== 'menu' && (
              <button
                onClick={() => setPendingTarget('menu')}
                className="mb-3 flex w-full items-center gap-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-left active:scale-[0.99] dark:border-amber-900/40 dark:bg-amber-900/10"
              >
                <span className="text-2xl">📋</span>
                <span>
                  <span className="block text-sm font-bold text-amber-800 dark:text-amber-400">
                    {t('capture.scanMenu')}
                  </span>
                  <span className="block text-xs text-amber-700/70 dark:text-amber-500/70">
                    {t('capture.scanMenuHint')}
                  </span>
                </span>
              </button>
            )}
            <div className="grid grid-cols-2 gap-3">
              <button
                onClick={() => {
                  setShowChooser(false)
                  cameraInputRef.current?.click()
                }}
                className="flex flex-col items-center gap-2 rounded-2xl border border-slate-200 py-5 text-sm font-semibold text-slate-700 active:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:active:bg-slate-700"
              >
                <span className="text-3xl">📷</span>
                {t('capture.takePhoto')}
              </button>
              <button
                onClick={() => {
                  setShowChooser(false)
                  galleryInputRef.current?.click()
                }}
                className="flex flex-col items-center gap-2 rounded-2xl border border-slate-200 py-5 text-sm font-semibold text-slate-700 active:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:active:bg-slate-700"
              >
                <span className="text-3xl">🖼️</span>
                {t('capture.fromGallery')}
              </button>
            </div>
            <button
              onClick={closeChooser}
              className="mt-3 w-full py-2 text-center text-sm font-medium text-slate-500 dark:text-slate-400"
            >
              {t('capture.cancel')}
            </button>
        </Dialog>
      )}
    </div>
  )
}
