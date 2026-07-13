import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  base: './',
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // Split the large, stable vendor libraries out of the entry chunk. This
        // keeps the app-shell entry small and lets these rarely-changing deps be
        // cached independently across deploys. Theme presets, pdf.js, quill and
        // chart.js are already lazy-loaded (dynamic import) and are intentionally
        // NOT listed here so they stay in their own on-demand chunks.
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia', '@vue/runtime-core', '@vue/reactivity'],
          'vendor-i18n': ['vue-i18n', '@intlify/core-base', '@intlify/message-compiler'],
          'vendor-query': ['@tanstack/vue-query', '@tanstack/query-core'],
          'vendor-http': ['axios'],
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080/docs-web',
        changeOrigin: true,
      },
    },
  },
})
