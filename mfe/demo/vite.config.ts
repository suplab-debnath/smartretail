import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5176,
    proxy: {
      '/api': { target: 'http://127.0.0.1:3099', changeOrigin: true },
    },
  },
})
