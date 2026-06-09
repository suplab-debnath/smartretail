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
      ...(mode === 'test' && {
        'aws-amplify/auth': resolve(__dirname, '../shared/auth/src/amplify-auth.test-stub.ts'),
      }),
    },
    dedupe: ['react', 'react-dom', 'react-router-dom'],
  },
  server: {
    port: 5177,
    proxy: {
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
        lines: 70,
        functions: 70,
        branches: 60,
        statements: 70,
      },
      include: ['src/components/**', 'src/hooks/**'],
      exclude: ['src/main.tsx', 'src/App.tsx'],
    },
  },
}))
