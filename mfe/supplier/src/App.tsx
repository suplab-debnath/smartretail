import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, AuthCallback } from '@smartretail/auth'
import { SupplierPortal } from './components/SupplierPortal'

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Navigate to="/portal" replace />} />
          <Route path="/portal" element={<SupplierPortal />} />
          <Route path="/callback" element={<AuthCallback />} />
          <Route path="/logout" element={<div className="p-8 text-gray-500">Signed out.</div>} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
