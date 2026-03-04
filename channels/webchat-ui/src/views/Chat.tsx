import { useState, useEffect, useRef, useCallback } from 'react'
import { getSessions } from '../api'
import { getToken } from '../auth'
import styles from './Chat.module.css'

// Minimal markdown: wrap code blocks, bold, newlines
function renderMarkdown(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, m => `<pre><code>${m.slice(3, -3).replace(/^[^\n]*\n/, '')}</code></pre>`)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br/>')
}

interface Msg { role: 'user' | 'bot'; text: string }
interface Session { id: string; messages: number; lastActivity: number }

export default function Chat() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [messages, setMessages] = useState<Msg[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const chatRef = useRef<HTMLDivElement>(null)
  const botTextRef = useRef('')

  const connect = useCallback(() => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const token = getToken() ?? ''
    const ws = new WebSocket(`${proto}//${location.host}/chat?token=${token}`)

    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.token !== undefined) {
        botTextRef.current += msg.token
        setMessages(prev => {
          const last = prev[prev.length - 1]
          if (last?.role === 'bot') {
            return [...prev.slice(0, -1), { role: 'bot', text: botTextRef.current }]
          }
          return [...prev, { role: 'bot', text: botTextRef.current }]
        })
        setTimeout(() => { chatRef.current?.scrollTo(0, chatRef.current.scrollHeight) }, 0)
      } else if (msg.done) {
        botTextRef.current = ''
        setSending(false)
      } else if (msg.error) {
        setMessages(prev => [...prev, { role: 'bot', text: `⚠️ ${msg.error}` }])
        setSending(false)
      }
    }

    ws.onclose = () => setTimeout(connect, 2000)
    ws.onerror = () => ws.close()
    wsRef.current = ws
  }, [])

  useEffect(() => {
    getSessions().then(setSessions)
    connect()
    return () => wsRef.current?.close()
  }, [connect])

  function send() {
    const text = input.trim()
    if (!text || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return
    setMessages(prev => [...prev, { role: 'user', text }])
    wsRef.current.send(JSON.stringify({ text }))
    setInput('')
    setSending(true)
    botTextRef.current = ''
    setTimeout(() => { chatRef.current?.scrollTo(0, chatRef.current.scrollHeight) }, 0)
  }

  return (
    <div className={styles.layout}>
      <aside className={styles.sessions}>
        <div className={styles.sessionsHeader}>Sessions</div>
        {sessions.map(s => (
          <div key={s.id} className={styles.sessionItem}>
            <span className={styles.sessionId}>{s.id.slice(0, 20)}…</span>
            <span className={styles.sessionMeta}>{s.messages} msgs</span>
          </div>
        ))}
      </aside>
      <div className={styles.chatArea}>
        <div className={styles.messages} ref={chatRef}>
          {messages.map((m, i) => (
            <div key={i} className={`${styles.msg} ${styles[m.role]}`}>
              {m.role === 'bot'
                ? <div dangerouslySetInnerHTML={{ __html: renderMarkdown(m.text) }} />
                : m.text}
              {m.role === 'bot' && sending && i === messages.length - 1 && (
                <span className={styles.cursor} />
              )}
            </div>
          ))}
        </div>
        <div className={styles.footer}>
          <textarea
            className={styles.input}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
            placeholder="Message… (Shift+Enter for newline)"
            rows={1}
          />
          <button className={styles.sendBtn} onClick={send} disabled={sending}>Send</button>
        </div>
      </div>
    </div>
  )
}
