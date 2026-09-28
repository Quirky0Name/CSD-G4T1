import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// Storage Management and Updating send no CORS headers, so dev requests are
// proxied through Vite instead of hitting them directly from the browser.
// See docs/LOCAL_STORAGE_DB.md, "Calling it from the frontend".
const STORAGE_MANAGEMENT_URL = 'http://localhost:8081'
const UPDATING_URL = 'http://localhost:8001'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': {
        target: STORAGE_MANAGEMENT_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
      '/updating': {
        target: UPDATING_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/updating/, ''),
      },
    },
  },
})
