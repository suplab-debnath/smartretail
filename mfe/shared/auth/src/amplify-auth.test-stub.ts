/**
 * Minimal stub for aws-amplify/auth used in Vitest environments.
 *
 * Vite's vite:import-analysis plugin must be able to resolve every import at
 * transform time — even dynamic ones — or it throws a hard error.  The
 * /* @vite-ignore * / hint suppresses warnings in production builds but does
 * NOT prevent the hard resolution error in Vitest.
 *
 * The cognitoPoolId guard in useFetch.ts means fetchAuthSession is never
 * actually called during tests (window.SMARTRETAIL_CONFIG is empty), so this
 * stub only needs to satisfy the import graph — not behave like the real thing.
 */
export const fetchAuthSession = async (): Promise<{ tokens: null }> => ({ tokens: null })
