// Test entry point — re-exports only modules that have no axios/aws-amplify dependencies.
// Used by MFE vite configs in test mode so CI does not need shared/auth/node_modules.
export * from './useFetch.js'
export * from './ErrorBanner.js'
export * from './Tooltip.js'
export * from './glossary.js'
export * from './config.js'

// Stubs for aws-amplify/axios-dependent exports.
// These are always vi.mock'd in component tests and unused in hook tests.
export type AuthState = Record<string, unknown>
export function AuthProvider(props: { children: unknown }): unknown { return props.children }
export function useAuth(): never { throw new Error('useAuth: add vi.mock in this test') }
export function AuthCallback(): null { return null }
export function createApiClient(): never { throw new Error('createApiClient: not available in tests') }
