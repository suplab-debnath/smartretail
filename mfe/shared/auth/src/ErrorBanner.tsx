import type { FetchError } from './useFetch.js'

interface Props {
  error: FetchError | null
  onRetry?: () => void
}

export function ErrorBanner({ error, onRetry }: Props) {
  if (!error) return null

  const message =
    error.kind === 'network'
      ? 'Could not reach the server. Check your connection or try again later.'
      : error.kind === 'server'
        ? `The server encountered an error (HTTP ${error.status}). Data is temporarily unavailable.`
        : `Request failed (HTTP ${error.status}).`

  return (
    <div className="mb-4 flex items-center gap-3 rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
      <span className="shrink-0 text-amber-500">⚠</span>
      <span className="flex-1">{message}</span>
      {onRetry && (
        <button
          onClick={onRetry}
          className="shrink-0 rounded border border-amber-300 bg-white px-3 py-1 text-xs font-medium text-amber-700 hover:bg-amber-50"
        >
          Retry
        </button>
      )}
    </div>
  )
}
