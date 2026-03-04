import { useState, useEffect } from 'react'
import { getAgents, Agent } from '../api'
import styles from './Agents.module.css'

export default function Agents() {
  const [agents, setAgents] = useState<Agent[]>([])

  useEffect(() => { getAgents().then(setAgents).catch(() => {}) }, [])

  return (
    <div className={styles.page}>
      <h2 className={styles.title}>Agents</h2>
      <div className={styles.grid}>
        {agents.length === 0 && <p className={styles.muted}>No agents configured.</p>}
        {agents.map(a => (
          <div key={a.name} className={styles.card}>
            <div className={styles.cardHeader}>
              <span className={styles.dot} />
              <span className={styles.name}>{a.name}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.label}>Channels</span>
              <span>{a.channels.length ? a.channels.join(', ') : 'default'}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.label}>DB</span>
              <span className={styles.mono}>{a.dbPath}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
