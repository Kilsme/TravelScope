/**
 * 认证 API：登录/登出 + token 存取 + 公共 apiFetch helper
 * <p>
 * token 存 localStorage（key: travelscope_token），apiFetch 统一注入
 * Authorization: Bearer <token>；401 时清空 token 并跳 /login。
 * </p>
 */

const TOKEN_KEY = 'travelscope_token'
const ROLE_KEY = 'travelscope_role'
const NICKNAME_KEY = 'travelscope_nickname'

export interface LoginResult {
  token: string
  userId: number
  role: string
  nickname: string
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getRole(): string | null {
  return localStorage.getItem(ROLE_KEY)
}

export function getNickname(): string | null {
  return localStorage.getItem(NICKNAME_KEY)
}

export function isLoggedIn(): boolean {
  return getToken() !== null
}

function saveAuth(result: LoginResult) {
  localStorage.setItem(TOKEN_KEY, result.token)
  localStorage.setItem(ROLE_KEY, result.role)
  localStorage.setItem(NICKNAME_KEY, result.nickname)
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
  localStorage.removeItem(NICKNAME_KEY)
}

/**
 * 公共 fetch helper：统一注入 Bearer token；401 清空并跳登录页
 */
export async function apiFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const resp = await fetch(url, { ...init, headers })
  if (resp.status === 401) {
    clearAuth()
    if (!location.pathname.startsWith('/login')) {
      location.href = '/login'
    }
  }
  return resp
}

/** 登录 */
export async function login(username: string, password: string): Promise<LoginResult> {
  const resp = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ username, password }),
  })
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({ message: '登录失败' }))
    throw new Error(err.message || '登录失败')
  }
  const result = (await resp.json()) as LoginResult
  saveAuth(result)
  return result
}

/** 登出 */
export async function logout(): Promise<void> {
  try {
    await apiFetch('/api/auth/logout', { method: 'POST' })
  } finally {
    clearAuth()
  }
}

/** 注册结果（2026-10-11 邀请码准入；注册成功后需另行登录，不发 token） */
export interface RegisterResult {
  message: string
  userId: number
  username: string
}

/** 注册（仅凭有效邀请码开放；拦截器放行 /auth/register） */
export async function register(
  username: string,
  password: string,
  nickname: string,
  invitationCode: string,
): Promise<RegisterResult> {
  const resp = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ username, password, nickname, invitationCode }),
  })
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({ message: '注册失败' }))
    throw new Error(err.message || '注册失败')
  }
  return resp.json()
}
