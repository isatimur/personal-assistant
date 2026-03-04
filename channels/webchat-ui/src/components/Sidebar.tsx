import { NavLink, useNavigate } from 'react-router-dom'
import { clearToken } from '../auth'
import styles from './Sidebar.module.css'

const NAV = [
  { to: '/chat',       label: 'Chat',        icon: '💬' },
  { to: '/memory',     label: 'Memory',      icon: '🧠' },
  { to: '/monitoring', label: 'Monitoring',  icon: '📊' },
  { to: '/workspace',  label: 'Workspace',   icon: '📝' },
  { to: '/agents',     label: 'Agents',      icon: '🤖' },
]

export default function Sidebar() {
  const navigate = useNavigate()

  function handleLogout() {
    clearToken()
    navigate('/login')
  }

  return (
    <nav className={styles.sidebar}>
      <div className={styles.logo}>Assistant</div>
      <ul className={styles.nav}>
        {NAV.map(({ to, label, icon }) => (
          <li key={to}>
            <NavLink to={to} className={({ isActive }) => `${styles.link} ${isActive ? styles.active : ''}`}>
              <span className={styles.icon}>{icon}</span>
              <span className={styles.label}>{label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
      <button className={styles.logout} onClick={handleLogout}>Sign out</button>
    </nav>
  )
}
