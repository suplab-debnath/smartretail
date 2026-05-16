import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, AuthCallback } from '@smartretail/auth'
import { ExecutiveDashboard } from './components/ExecutiveDashboard'

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<ExecutiveDashboard />} />
          <Route path="/callback" element={<AuthCallback />} />
          <Route path="/logout" element={<div className="p-8 text-gray-500">Signed out.</div>} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
