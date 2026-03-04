import { useState, useEffect } from 'react'
import { getWorkspaceFiles, getWorkspaceFile, putWorkspaceFile, WorkspaceFile } from '../api'
import styles from './Workspace.module.css'

export default function Workspace() {
  const [files, setFiles] = useState<WorkspaceFile[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  useEffect(() => { getWorkspaceFiles().then(setFiles).catch(() => {}) }, [])

  async function selectFile(name: string) {
    setSelected(name)
    try {
      const { content: c } = await getWorkspaceFile(name)
      setContent(c)
    } catch {
      setContent('')
    }
    setSaved(false)
  }

  async function save() {
    if (!selected) return
    setSaving(true)
    try {
      await putWorkspaceFile(selected, content)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    } catch {
      // save failed — user can retry
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={styles.layout}>
      <aside className={styles.sidebar}>
        <div className={styles.sidebarHeader}>Files</div>
        {files.map(f => (
          <div
            key={f.name}
            className={`${styles.fileItem} ${selected === f.name ? styles.selected : ''}`}
            onClick={() => selectFile(f.name)}
          >
            <span className={`${styles.exists} ${f.exists ? styles.present : styles.absent}`} />
            {f.name}
          </div>
        ))}
      </aside>
      <div className={styles.editor}>
        {selected ? (
          <>
            <div className={styles.editorHeader}>
              <span className={styles.filename}>{selected}</span>
              <button className={styles.saveBtn} onClick={save} disabled={saving}>
                {saved ? '✓ Saved' : saving ? 'Saving…' : 'Save'}
              </button>
            </div>
            <textarea
              className={styles.textarea}
              value={content}
              onChange={e => { setContent(e.target.value); setSaved(false) }}
              spellCheck={false}
            />
          </>
        ) : (
          <div className={styles.placeholder}>Select a file to edit</div>
        )}
      </div>
    </div>
  )
}
