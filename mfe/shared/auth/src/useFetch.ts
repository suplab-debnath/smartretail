export type FetchError = {
  kind: 'network' | 'server' | 'client'
  status?: number
  message: string
}

export function isFetchError(e: unknown): e is FetchError {
  return typeof e === 'object' && e !== null && 'kind' in e
}

export async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  // In AWS the apiGatewayEndpoint is injected via config.js at deploy time.
  // In local dev it is empty — Vite's proxy handles routing by path.
  const config = (window as any).SMARTRETAIL_CONFIG ?? {}
  const base: string = config.apiGatewayEndpoint ?? ''
  // Strip trailing slash from base to avoid double-slash with leading-slash paths.
  const resolvedUrl = base ? `${base.replace(/\/$/, '')}${url}` : url

  // AWS mode only: mutate headers to add Bearer token and strip X-Dev-Role
  // (blocked by API Gateway CORS). In local/test mode pass init unchanged so
  // callers' plain-object headers are preserved as-is (tests can assert on them).
  let requestInit: RequestInit | undefined = init

  if (config.cognitoPoolId) {
    const headers = new Headers(init?.headers)
    headers.delete('X-Dev-Role')
    try {
      const { fetchAuthSession } = await import('aws-amplify/auth')
      const session = await fetchAuthSession()
      if (session.tokens?.idToken) {
        headers.set('Authorization', `Bearer ${session.tokens.idToken.toString()}`)
      }
    } catch {
      // No active session — proceed without token; API will return 401.
    }
    requestInit = { ...init, headers }
  }

  let res: Response
  try {
    res = await fetch(resolvedUrl, requestInit)
  } catch {
    throw {
      kind: 'network',
      message: 'Could not reach the server. Check your connection.',
    } as FetchError
  }
  if (!res.ok) {
    throw {
      kind: res.status >= 500 ? 'server' : 'client',
      status: res.status,
      message: `HTTP ${res.status}`,
    } as FetchError
  }
  return res.json() as Promise<T>
}
