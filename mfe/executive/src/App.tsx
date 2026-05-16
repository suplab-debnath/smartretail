import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ExecutiveDashboard } from './components/ExecutiveDashboard'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<ExecutiveDashboard />} />
        <Route path="/logout" element={<div className="p-8 text-gray-500">Signed out.</div>} />
      </Routes>
    </BrowserRouter>
  )
}
