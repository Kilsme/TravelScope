import type { CachedTurn, Outbox, TurnRequest, TurnView } from './turnTypes'

const name = 'travelscope-reconnect-v1'
let opening: Promise<IDBDatabase> | undefined
function database() {
  opening ??= new Promise<IDBDatabase>((resolve, reject) => {
    const req = indexedDB.open(name, 1)
    req.onupgradeneeded = () => {
      for (const store of ['outbox', 'turns'])
        if (!req.result.objectStoreNames.contains(store))
          req.result.createObjectStore(store, { keyPath: 'key' })
    }
    req.onsuccess = () => {
      req.result.onversionchange = () => {
        req.result.close()
        opening = undefined
      }
      resolve(req.result)
    }
    req.onerror = () => {
      opening = undefined
      reject(req.error)
    }
    req.onblocked = () => {
      opening = undefined
      reject(new Error('请关闭旧页面后重试本地恢复存储'))
    }
  })
  return opening
}
async function write(store: string, action: (s: IDBObjectStore) => void) {
  const db = await database()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(store, 'readwrite')
    tx.oncomplete = () => resolve()
    tx.onerror = tx.onabort = () =>
      reject(tx.error ?? new Error('无法保存本地恢复状态'))
    try {
      action(tx.objectStore(store))
    } catch (e) {
      tx.abort()
      reject(e)
    }
  })
}
async function all<T>(store: string): Promise<T[]> {
  const db = await database()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, 'readonly')
    const req = tx.objectStore(store).getAll()
    req.onsuccess = () => resolve(req.result as T[])
    req.onerror = () => reject(req.error)
  })
}
export const cacheKey = (user: number, turn: string) => `${user}:${turn}`
export async function saveOutbox(
  userId: number,
  request: TurnRequest,
): Promise<Outbox> {
  const entry = {
    key: cacheKey(userId, request.turnId),
    userId,
    request: structuredClone(request),
    createdAt: new Date().toISOString(),
  }
  await write('outbox', (s) => s.put(entry))
  return entry
}
export const deleteOutbox = (entry: Outbox) =>
  write('outbox', (s) => s.delete(entry.key))
export const listOutbox = async (user: number) =>
  (await all<Outbox>('outbox')).filter((e) => e.userId === user)
export const saveTurn = (userId: number, view: TurnView) =>
  write('turns', (s) =>
    s.put({ key: cacheKey(userId, view.turnId), userId, view }),
  )
export const listTurns = async (user: number) =>
  (await all<CachedTurn>('turns'))
    .filter((e) => e.userId === user)
    .map((e) => e.view)
export async function clearRecovery(user: number) {
  for (const store of ['outbox', 'turns']) {
    const entries = (await all<{ key: string; userId: number }>(store)).filter(
      (e) => e.userId === user,
    )
    await write(store, (s) => entries.forEach((e) => s.delete(e.key)))
  }
}
