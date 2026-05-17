import { useState, useEffect } from 'react'

interface Props {
  dataFreshness: string
  onRefresh: () => void
}

export function DataFreshnessIndicator({ dataFreshness, onRefresh }: Props) {
  const [secondsAgo, setSecondsAgo] = useState(0)

  useEffect(() => {
    const base = new Date(dataFreshness).getTime()
    const tick = () => setSecondsAgo(Math.floor((Date.now() - base) / 1000))
    tick()
    const id = setInterval(tick, 5000)
    return () => clearInterval(id)
  }, [dataFreshness])

  const label = secondsAgo < 60
    ? `${secondsAgo}s ago`
    : `${Math.floor(secondsAgo / 60)}m ago`

  return (
    <div className="flex items-center gap-3 text-sm text-gray-500">
      <span>Last updated {label}</span>
      <button
        onClick={onRefresh}
        className="text-blue-600 hover:text-blue-800 underline text-xs"
      >
        Refresh
      </button>
    </div>
  )
}
