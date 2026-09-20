import { useCallback, useEffect, useRef, useState } from 'react'
import { listDocuments, uploadDocument, type AdminDocument } from '../../api/admin'

/**
 * 文档上传页（FR-A03：MinIO 上传 + 状态机展示）
 */
export default function DocUploadPage() {
  const [docs, setDocs] = useState<AdminDocument[]>([])
  const [uploading, setUploading] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    try {
      setDocs(await listDocuments())
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function handleUpload() {
    const file = fileRef.current?.files?.[0]
    if (!file) {
      setError('请先选择文件')
      return
    }
    setUploading(true)
    setError('')
    setMessage('')
    try {
      const doc = await uploadDocument(file)
      setMessage(`上传成功：${doc.fileName}（status=${doc.status}，约 1 秒后流转为 PARSING）`)
      if (fileRef.current) fileRef.current.value = ''
      setTimeout(load, 1500) // 等 PARSING 流转后刷新
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败')
    } finally {
      setUploading(false)
    }
  }

  return (
    <div className="admin-page">
      <h2>文档上传（知识库）</h2>
      <div className="admin-upload">
        <input ref={fileRef} type="file" accept=".pdf,.txt,.md,.docx,.html" />
        <button onClick={handleUpload} disabled={uploading}>
          {uploading ? '上传中…' : '上传到 MinIO'}
        </button>
      </div>
      {message && <div className="admin-message">{message}</div>}
      {error && <div className="admin-error">{error}</div>}
      <table className="admin-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>文件名</th>
            <th>类型</th>
            <th>大小</th>
            <th>状态</th>
            <th>上传时间</th>
          </tr>
        </thead>
        <tbody>
          {docs.length === 0 ? (
            <tr><td colSpan={6} className="admin-empty">暂无文档（上传第一个 PDF 试试）</td></tr>
          ) : (
            docs.map((d) => (
              <tr key={d.id}>
                <td>{d.id}</td>
                <td>{d.fileName}</td>
                <td>{d.fileType}</td>
                <td>{(d.fileSize / 1024).toFixed(1)} KB</td>
                <td>
                  <span className={`badge status-${d.status.toLowerCase()}`}>{d.status}</span>
                </td>
                <td>{d.createdAt?.replace('T', ' ').slice(0, 19)}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
