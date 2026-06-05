import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, AuthCallback } from '@smartretail/auth'
import { StoreDashboard } from './components/StoreDashboard'

const BASE = import.meta.env.BASE_URL.replace(/\/$/, '') || ''

export default function App() {
  const origin = window.location.origin
  return (
    <AuthProvider
      redirectSignIn={`${origin}${BASE}/callback`}
      redirectSignOut={`${origin}${BASE}/logout`}
    >
      <BrowserRouter basename={BASE}>
        <Routes>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<StoreDashboard />} />
          <Route path="/callback" element={<AuthCallback />} />
          <Route path="/logout" element={<div className="p-8 text-gray-500">Signed out.</div>} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
