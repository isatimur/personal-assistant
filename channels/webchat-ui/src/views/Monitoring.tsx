import { useState, useEffect } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts'
import { getMetrics, Metrics } from '../api'
import styles from './Monitoring.module.css'

function Card({ label, value }: { label: string; value: number }) {
  return (
    <div className={styles.card}>
      <div className={styles.cardLabel}>{label}</div>
      <div className={styles.cardValue}>{value.toLocaleString()}</div>
    </div>
  )
}

export default function Monitoring() {
  const [metrics, setMetrics] = useState<Metrics | null>(null)

  useEffect(() => { getMetrics().then(setMetrics).catch(() => {}) }, [])

  const chartData = metrics ? [
    { name: 'Input', tokens: metrics.inputTokens },
    { name: 'Output', tokens: metrics.outputTokens },
  ] : []

  return (
    <div className={styles.page}>
      <h2 className={styles.title}>Monitoring</h2>
      {metrics && (
        <>
          <div className={styles.cards}>
            <Card label="Total Tokens" value={metrics.totalTokens} />
            <Card label="Input Tokens" value={metrics.inputTokens} />
            <Card label="Output Tokens" value={metrics.outputTokens} />
            <Card label="Sessions" value={metrics.sessions} />
          </div>
          <div className={styles.chart}>
            <div className={styles.chartTitle}>Token Breakdown</div>
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                <XAxis dataKey="name" stroke="var(--muted)" tick={{ fill: 'var(--muted)', fontSize: 12 }} />
                <YAxis stroke="var(--muted)" tick={{ fill: 'var(--muted)', fontSize: 12 }} />
                <Tooltip contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text)' }} />
                <Bar dataKey="tokens" fill="var(--accent)" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </div>
  )
}
