import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      // Point to source so tests (and dev) don't require a pre-built dist/
      '@smartretail/auth': resolve(__dirname, '../shared/auth/src/index.ts'),
    },
  },
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
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov', 'html'],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 70,
        statements: 80,
      },
      include: ['src/components/**', 'src/hooks/**', 'src/utils/**'],
      exclude: ['src/main.tsx', 'src/App.tsx'],
    },
  },
})
