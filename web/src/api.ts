// contracts/openapi/release-api.yaml 의 스키마를 수동으로 옮긴 타입
export type ToolId =
  | 'gitlab' | 'gitea' | 'jenkins' | 'sonarqube' | 'harbor'
  | 'argocd' | 'dependency-track' | 'k3s' | 'kubernetes'

export interface ReleaseSummary {
  id: number
  tool: ToolId
  version: string | null
  title: string
  url: string
  publishedAt: string
  security: boolean
}

export interface Release extends ReleaseSummary {
  body: string | null
  bodyFormat: 'markdown' | 'html' | 'text' | null
}

export interface Tool {
  id: ToolId
  name: string
  category: string
  latest: ReleaseSummary | null
}

export interface ReleasePage {
  items: ReleaseSummary[]
  page: number
  size: number
  total: number
}

export interface ReleaseQuery {
  tools: ToolId[]
  q: string
  from: string // YYYY-MM-DD (로컬 날짜)
  to: string // YYYY-MM-DD, 해당 날짜 포함
  security: boolean
}

async function get<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, { signal })
  if (!res.ok) {
    let msg = `HTTP ${res.status}`
    try {
      msg = (await res.json()).message ?? msg
    } catch { /* 본문이 JSON이 아니면(프록시 오류 등) 상태 코드만 */ }
    throw new Error(msg)
  }
  return res.json()
}

export const listTools = () => get<Tool[]>('/api/tools')
export const getRelease = (id: number) => get<Release>(`/api/releases/${id}`)

// 날짜 입력은 로컬 자정 기준. to 는 "미만"이므로 다음날 자정
export function dateRange(f: Pick<ReleaseQuery, 'from' | 'to'>) {
  const from = f.from ? new Date(`${f.from}T00:00`) : null
  const to = f.to ? new Date(`${f.to}T00:00`) : null
  if (to) to.setDate(to.getDate() + 1)
  return { from, to }
}

export function listReleases(f: ReleaseQuery, page: number, signal: AbortSignal) {
  const p = new URLSearchParams()
  f.tools.forEach((t) => p.append('tool', t))
  const { from, to } = dateRange(f)
  if (from) p.set('from', from.toISOString())
  if (to) p.set('to', to.toISOString())
  if (f.q) p.set('q', f.q)
  if (f.security) p.set('security', 'true')
  p.set('page', String(page))
  p.set('size', '20')
  return get<ReleasePage>(`/api/releases?${p}`, signal)
}
