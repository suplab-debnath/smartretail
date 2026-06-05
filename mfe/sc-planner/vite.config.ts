import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  base: process.env.VITE_BASE_PATH ?? '/',
  resolve: {
    alias: {
      '@smartretail/auth': resolve(
        __dirname,
        mode === 'test'
          ? '../shared/auth/src/index.test-stub.ts'
          : '../shared/auth/src/index.ts'
      ),
    },
    dedupe: ['react', 'react-dom', 'react-router-dom'],
  },
  server: {
    port: 5174,
    proxy: {
      '/v1/ingest':        { target: 'http://localhost:8080', changeOrigin: true },
      '/v1/dashboard':     { target: 'http://localhost:8083', changeOrigin: true },
      '/v1/inventory':     { target: 'http://localhost:8081', changeOrigin: true },
      '/v1/replenishment': { target: 'http://localhost:8082', changeOrigin: true },
      '/v1/forecast':      { target: 'http://localhost:8084', changeOrigin: true },
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
      exclude: ['src/main.tsx', 'src/App.tsx', 'src/components/DemoTab.tsx'],
    },
  },
}))
