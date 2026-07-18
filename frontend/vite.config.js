import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['pwa-192.png', 'pwa-512.png', 'pwa-512-maskable.png'],
      manifest: {
        name: 'DuaBiskutTelur',
        short_name: 'DuaBiskutTelur',
        description: 'Snap your meal, get it graded — calories, macros and friendly advice.',
        theme_color: '#15803d',
        background_color: '#f8fafc',
        display: 'standalone',
        orientation: 'portrait',
        icons: [
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: 'pwa-512-maskable.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Cache the app shell; API calls are always network (analyze needs connectivity)
        globPatterns: ['**/*.{js,css,html,png,svg,ico}'],
        // The ZXing barcode fallback (~415 KB) is only needed on browsers without
        // native BarcodeDetector (iOS Safari) and is already lazy-imported for
        // that reason — excluding it here stops the SW install from eagerly
        // downloading it for every installed PWA regardless of platform.
        globIgnores: ['**/zxing-vendor-*.js'],
        runtimeCaching: [
          {
            urlPattern: /zxing-vendor-.*\.js$/,
            handler: 'CacheFirst',
            options: {
              cacheName: 'zxing-chunk',
              expiration: { maxEntries: 2 },
            },
          },
        ],
        navigateFallback: 'index.html',
        // Never serve the cached SPA for backend routes — the OAuth login/callback
        // and API are full-page/network requests that must reach the server, not
        // be intercepted by the service worker.
        navigateFallbackDenylist: [/^\/api\//, /^\/oauth2\//, /^\/login\/oauth2\//, /^\/logout$/],
      },
    }),
  ],
  build: {
    rollupOptions: {
      output: {
        // Gives the ZXing chunk a stable, predictable filename prefix so the
        // Workbox globIgnores/runtimeCaching rules above can target it —
        // Vite's default chunking derives the name from the import graph and
        // isn't guaranteed to stay matchable across builds otherwise.
        manualChunks(id) {
          if (id.includes('@zxing/library')) return 'zxing-vendor'
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/oauth2': 'http://localhost:8080',
      '/login/oauth2': 'http://localhost:8080',
    },
  },
})
