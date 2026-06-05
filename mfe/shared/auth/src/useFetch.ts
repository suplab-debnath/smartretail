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

  const headers = new Headers(init?.headers)

  if (config.cognitoPoolId) {
    // AWS mode: add Bearer token and remove local-dev-only headers blocked by CORS.
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
  }

  let res: Response
  try {
    res = await fetch(resolvedUrl, { ...init, headers })
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
