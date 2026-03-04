import { useState, useEffect, useCallback } from 'react'
import { getMemory, deleteMemory, MemoryChunk } from '../api'
import styles from './Memory.module.css'

export default function Memory() {
  const [rows, setRows] = useState<MemoryChunk[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await getMemory(query))
    } catch {
      // keep existing rows on error
    } finally {
      setLoading(false)
    }
  }, [query])

  useEffect(() => { load() }, [load])

  async function handleDelete(id: number) {
    try {
      await deleteMemory(id)
      setRows(r => r.filter(x => x.id !== id))
    } catch {
      // deletion failed — leave row in place, user can retry
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h2 className={styles.title}>Memory</h2>
        <input
          className={styles.search}
          placeholder="Search memory…"
          value={query}
          onChange={e => setQuery(e.target.value)}
        />
      </div>
      {loading ? <p className={styles.muted}>Loading…</p> : (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Text</th>
              <th>Session</th>
              <th>Date</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.id}>
                <td className={styles.textCell}>{r.text}</td>
                <td className={styles.meta}>{r.sessionId.length > 24 ? r.sessionId.slice(0, 24) + '…' : r.sessionId}</td>
                <td className={styles.meta}>{new Date(r.createdAt).toLocaleDateString()}</td>
                <td><button className={styles.del} onClick={() => handleDelete(r.id)}>×</button></td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={4} className={styles.muted}>No results</td></tr>}
          </tbody>
        </table>
      )}
    </div>
  )
}
