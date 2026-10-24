import { AdminPageHeader, AdminPanel, AdminTable } from '../../components/AdminPrimitives'
import { usePreferences } from '../../preferences/PreferencesProvider'
import { t, ui } from '../../preferences/i18n'
import { useCallback, useEffect, useRef, useState } from 'react';
import { listDocuments, uploadDocument, type AdminDocument } from '../../api/admin';
/**
 * 文档上传页（FR-A03：MinIO 上传 + 状态机展示）
 */
export default function DocUploadPage() {
    usePreferences();
    const [docs, setDocs] = useState<AdminDocument[]>([]);
    const [uploading, setUploading] = useState(false);
    const [message, setMessage] = useState('');
    const [error, setError] = useState('');
    const fileRef = useRef<HTMLInputElement>(null);
    const load = useCallback(async () => {
        try {
            setDocs(await listDocuments());
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '加载失败');
        }
    }, []);
    useEffect(() => {
        load();
    }, [load]);
    async function handleUpload() {
        const file = fileRef.current?.files?.[0];
        if (!file) {
            setError('请先选择文件');
            return;
        }
        setUploading(true);
        setError('');
        setMessage('');
        try {
            const doc = await uploadDocument(file);
            setMessage(`上传成功：${doc.fileName}（status=${doc.status}，约 1 秒后流转为 PARSING）`);
            if (fileRef.current)
                fileRef.current.value = '';
            setTimeout(load, 1500); // 等 PARSING 流转后刷新
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '上传失败');
        }
        finally {
            setUploading(false);
        }
    }
    return (<div className="admin-page">
      <AdminPageHeader title="文档上传（知识库）" section="知识库" description="上传知识文档并查看处理状态。" />
      <AdminPanel title="上传文档">
      <div className="admin-upload">
        <input ref={fileRef} type="file" accept=".pdf,.txt,.md,.docx,.html"/>
        <button onClick={handleUpload} disabled={uploading}>
          {ui(uploading ? t("上传中\u2026") : t("上传到 MinIO"))}
        </button>
      </div>
      </AdminPanel>
      {ui(message && <div className="admin-message">{ui(message)}</div>)}
      {ui(error && <div className="admin-error">{ui(error)}</div>)}
      <AdminPanel title="文档列表"><AdminTable>
      <table className="admin-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>{t("文件名")}</th>
            <th>{t("类型")}</th>
            <th>{t("大小")}</th>
            <th>{t("状态")}</th>
            <th>{t("上传时间")}</th>
          </tr>
        </thead>
        <tbody>
          {ui(docs.length === 0 ? (<tr><td colSpan={6} className="admin-empty">{t("暂无文档\uFF08上传第一个 PDF 试试\uFF09")}</td></tr>) : (docs.map((d) => (<tr key={d.id}>
                <td>{ui(d.id)}</td>
                <td>{d.fileName}</td>
                <td>{ui(d.fileType)}</td>
                <td>{ui((d.fileSize / 1024).toFixed(1))} KB</td>
                <td>
                  <span className={`badge status-${d.status.toLowerCase()}`}>{ui(d.status)}</span>
                </td>
                <td>{ui(d.createdAt?.replace('T', ' ').slice(0, 19))}</td>
              </tr>))))}
        </tbody>
      </table>
      </AdminTable></AdminPanel>
    </div>);
}
