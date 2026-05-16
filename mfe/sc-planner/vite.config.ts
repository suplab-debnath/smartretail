import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/v1/dashboard': { target: 'http://localhost:8083', changeOrigin: true },
      '/v1/inventory': { target: 'http://localhost:8081', changeOrigin: true },
      '/v1/replenishment': { target: 'http://localhost:8082', changeOrigin: true },
      '/v1/forecast': { target: 'http://localhost:8084', changeOrigin: true },
      '/v1/supplier': { target: 'http://localhost:8085', changeOrigin: true },
    },
  },
})
