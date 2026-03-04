import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router-dom'
import { isAuthenticated } from './auth'
import Sidebar from './components/Sidebar'
import Login from './views/Login'
import Chat from './views/Chat'
import Memory from './views/Memory'
import Monitoring from './views/Monitoring'
import Workspace from './views/Workspace'
import Agents from './views/Agents'

function ProtectedLayout() {
  if (!isAuthenticated()) return <Navigate to="/login" replace />
  return (
    <div style={{ display: 'flex', height: '100vh' }}>
      <Sidebar />
      <main style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        <Outlet />
      </main>
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<ProtectedLayout />}>
          <Route path="/chat" element={<Chat />} />
          <Route path="/memory" element={<Memory />} />
          <Route path="/monitoring" element={<Monitoring />} />
          <Route path="/workspace" element={<Workspace />} />
          <Route path="/agents" element={<Agents />} />
          <Route path="*" element={<Navigate to="/chat" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
