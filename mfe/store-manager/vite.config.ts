import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  resolve: {
    alias: {
      '@smartretail/auth': resolve(
        __dirname,
        mode === 'test'
          ? '../shared/auth/src/index.test-stub.ts'
          : '../shared/auth/src/index.ts'
      ),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/v1/dashboard': { target: 'http://localhost:8083', changeOrigin: true },
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
}))
