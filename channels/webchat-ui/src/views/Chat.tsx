import { useState, useEffect, useRef, useCallback } from 'react'
import { getSessions } from '../api'
import { getToken } from '../auth'
import styles from './Chat.module.css'

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

// Minimal markdown: wrap code blocks, bold, newlines
function renderMarkdown(text: string): string {
  // Replace fenced code blocks first (so inner content isn't markdown-processed)
  let remaining = text.replace(/```([^`]*?)```/gs, (_, inner) => {
    const lang = inner.match(/^[^\n]*/)?.[0] ?? ''
    const code = inner.replace(/^[^\n]*\n?/, '')
    return `<pre><code>${escapeHtml(code)}</code></pre>`
  })

  // For the remaining text, escape HTML then apply inline markdown
  // Split by the pre/code blocks we just created
  const result = remaining
    .split(/(<pre><code>[\s\S]*?<\/code><\/pre>)/g)
    .map((part, i) => {
      if (i % 2 === 1) return part // already processed code block
      return escapeHtml(part)
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br/>')
    })
    .join('')

  return result
}

interface Msg { role: 'user' | 'bot'; id: number; text: string }
interface Session { id: string; messages: number; lastActivity: number }

export default function Chat() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [messages, setMessages] = useState<Msg[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const chatRef = useRef<HTMLDivElement>(null)
  const botTextRef = useRef('')
  const mountedRef = useRef(true)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const connect = useCallback(() => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const token = getToken() ?? ''
    const ws = new WebSocket(`${proto}//${location.host}/chat?token=${token}`)

    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data)
        if (msg.token !== undefined) {
          botTextRef.current += msg.token
          setMessages(prev => {
            const last = prev[prev.length - 1]
            if (last?.role === 'bot') {
              return [...prev.slice(0, -1), { role: 'bot', id: last.id, text: botTextRef.current }]
            }
            return [...prev, { role: 'bot', id: Date.now(), text: botTextRef.current }]
          })
          setTimeout(() => { chatRef.current?.scrollTo(0, chatRef.current.scrollHeight) }, 0)
        } else if (msg.done) {
          botTextRef.current = ''
          setSending(false)
        } else if (msg.error) {
          setMessages(prev => [...prev, { role: 'bot', id: Date.now(), text: `⚠️ ${escapeHtml(String(msg.error))}` }])
          setSending(false)
        }
      } catch {
        setMessages(prev => [...prev, { role: 'bot', id: Date.now(), text: '⚠️ Received invalid message from server' }])
        setSending(false)
      }
    }

    ws.onclose = () => {
      if (mountedRef.current) {
        reconnectTimerRef.current = setTimeout(connect, 2000)
      }
    }
    ws.onerror = () => ws.close()
    wsRef.current = ws
  }, [])

  useEffect(() => {
    mountedRef.current = true
    getSessions().then(setSessions).catch(() => {})
    connect()
    return () => {
      mountedRef.current = false
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current)
      wsRef.current?.close()
    }
  }, [connect])

  function send() {
    const text = input.trim()
    if (!text || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return
    setMessages(prev => [...prev, { role: 'user', id: Date.now(), text }])
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
          {messages.map((m) => (
            <div key={m.id} className={`${styles.msg} ${styles[m.role]}`}>
              {m.role === 'bot'
                ? <div dangerouslySetInnerHTML={{ __html: renderMarkdown(m.text) }} />
                : m.text}
              {m.role === 'bot' && sending && m.id === messages[messages.length - 1]?.id && (
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
