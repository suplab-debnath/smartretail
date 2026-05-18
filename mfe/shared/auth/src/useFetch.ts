export type FetchError = {
  kind: 'network' | 'server' | 'client'
  status?: number
  message: string
}

export function isFetchError(e: unknown): e is FetchError {
  return typeof e === 'object' && e !== null && 'kind' in e
}

export async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(url, init)
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
