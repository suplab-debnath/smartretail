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
  const base: string = (window as any).SMARTRETAIL_CONFIG?.apiGatewayEndpoint ?? ''
  const resolvedUrl = base && url.startsWith('/') ? `${base}${url}` : url
  let res: Response
  try {
    res = await fetch(resolvedUrl, init)
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
