import { apiFetch } from './auth'

/**
 * 管理端 API（FR-A01 Token 用量 / FR-A02 用户管理 / FR-A03 文档上传）
 */

const BASE = '/api/admin'

export interface TokenUsageRow {
  userId: number
  username: string
  date: string
  modelName: string
  messageCount: number
  totalTokens: number
}

export interface AdminUser {
  id: number
  username: string
  nickname: string
  role: string
  status: number
  createdAt: string
  updatedAt: string
}

export interface AdminDocument {
  id: number
  title: string
  fileName: string
  fileType: string
  fileSize: number
  status: string
  filePath: string
  createdAt: string
  updatedAt: string
}

/** Token 用量报表（按用户/按天/按模型聚合） */
export async function getTokenUsage(params?: {
  userId?: number
  dateFrom?: string
  dateTo?: string
}): Promise<TokenUsageRow[]> {
  const qs = new URLSearchParams()
  if (params?.userId != null) qs.set('userId', String(params.userId))
  if (params?.dateFrom) qs.set('dateFrom', params.dateFrom)
  if (params?.dateTo) qs.set('dateTo', params.dateTo)
  const url = qs.toString() ? `${BASE}/token-usage?${qs}` : `${BASE}/token-usage`
  const resp = await apiFetch(url)
  if (!resp.ok) throw new Error('查询 Token 用量失败')
  return resp.json()
}

/** 用户列表 */
export async function listUsers(): Promise<AdminUser[]> {
  const resp = await apiFetch(`${BASE}/users`)
  if (!resp.ok) throw new Error('加载用户列表失败')
  return resp.json()
}

/** 禁用用户 */
export async function disableUser(id: number): Promise<void> {
  const resp = await apiFetch(`${BASE}/users/${id}/disable`, { method: 'PUT' })
  if (!resp.ok) throw new Error('禁用用户失败')
}

/** 启用用户 */
export async function enableUser(id: number): Promise<void> {
  const resp = await apiFetch(`${BASE}/users/${id}/enable`, { method: 'PUT' })
  if (!resp.ok) throw new Error('启用用户失败')
}

/** 修改角色 */
export async function setUserRole(id: number, role: 'user' | 'admin'): Promise<void> {
  const resp = await apiFetch(`${BASE}/users/${id}/role?role=${role}`, { method: 'PUT' })
  if (!resp.ok) throw new Error('修改角色失败')
}

/** 上传文档 */
export async function uploadDocument(file: File): Promise<AdminDocument> {
  const form = new FormData()
  form.append('file', file)
  const resp = await apiFetch(`${BASE}/documents`, {
    method: 'POST',
    body: form,
  })
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({ message: '上传失败' }))
    throw new Error(err.message || '上传失败')
  }
  return resp.json()
}

/** 文档列表 */
export async function listDocuments(): Promise<AdminDocument[]> {
  const resp = await apiFetch(`${BASE}/documents`)
  if (!resp.ok) throw new Error('加载文档列表失败')
  return resp.json()
}
