import { useState, useEffect } from 'react'
import type { HealthStatus } from '../types'

const DEFAULT: HealthStatus = { sis: 'down', ims: 'down', re: 'down', ars: 'down', dfs: 'down', sup: 'down' }

export function useServiceHealth(intervalMs = 30_000) {
  const [health, setHealth] = useState<HealthStatus>(DEFAULT)

  useEffect(() => {
    async function check() {
      try {
        const res = await fetch('/api/health')
        if (res.ok) setHealth(await res.json())
      } catch { /* demo-server not running */ }
    }

    check()
    const id = setInterval(check, intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])

  return health
}
