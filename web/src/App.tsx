import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiError, createSummary, dateRange, getRelease, getSummary, listReleases, listTools,
  type Release, type ReleaseQuery, type ReleaseSummary, type Summary, type Tool, type ToolId,
} from './api'

const SANITIZE = {
  FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'iframe', 'object', 'embed'],
  FORBID_ATTR: ['style'],
}

const isHttp = (u: string) => {
  try {
    return ['http:', 'https:'].includes(new URL(u).protocol)
  } catch {
    return false
  }
}

// 본문 링크는 http(s)만, 항상 새 탭 + noopener. 그 외 href는 제거해 텍스트로 남김
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    if (!isHttp(node.getAttribute('href') ?? '')) node.removeAttribute('href')
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

const readUrl = (): ReleaseQuery => {
  const p = new URLSearchParams(location.search)
  return {
    tools: p.getAll('tool') as ToolId[],
    q: (p.get('q') ?? '').trim(),
    from: p.get('from') ?? '',
    to: p.get('to') ?? '',
    security: p.get('security') === 'true',
  }
}

const writeUrl = (f: ReleaseQuery) => {
  const p = new URLSearchParams()
  f.tools.forEach((t) => p.append('tool', t))
  if (f.q) p.set('q', f.q)
  if (f.from) p.set('from', f.from)
  if (f.to) p.set('to', f.to)
  if (f.security) p.set('security', 'true')
  const s = p.toString()
  history.replaceState(null, '', s ? `?${s}` : location.pathname)
}

const rtf = new Intl.RelativeTimeFormat('ko', { numeric: 'auto' })
function relTime(iso: string) {
  const sec = (new Date(iso).getTime() - Date.now()) / 1000
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31536000], ['month', 2592000], ['day', 86400], ['hour', 3600], ['minute', 60],
  ]
  for (const [u, s] of units) if (Math.abs(sec) >= s) return rtf.format(Math.trunc(sec / s), u)
  return rtf.format(Math.trunc(sec), 'second')
}

// SSE 로 받은 항목이 현재 필터에 맞는지. q 는 본문을 모르므로 제목·버전만 검사한다
// ponytail: 본문에만 q가 있는 신규 항목은 실시간 추가에서 빠짐, 새로고침하면 나타남
function matches(r: ReleaseSummary, f: ReleaseQuery) {
  if (f.tools.length && !f.tools.includes(r.tool)) return false
  if (f.security && !r.security) return false
  const { from, to } = dateRange(f)
  const t = new Date(r.publishedAt)
  if (from && t < from) return false
  if (to && t >= to) return false
  if (f.q && !`${r.title} ${r.version ?? ''}`.toLowerCase().includes(f.q.toLowerCase())) return false
  return true
}

export default function App() {
  const [filter, setFilter] = useState(readUrl)
  const [qInput, setQInput] = useState(filter.q)
  const [tools, setTools] = useState<Tool[]>([])
  const [toolsError, setToolsError] = useState('')
  const [items, setItems] = useState<ReleaseSummary[]>([])
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  const [fresh, setFresh] = useState<Set<number>>(new Set())
  const [live, setLive] = useState(false)
  const filterRef = useRef(filter)
  const itemsRef = useRef(items)

  // 검색어 디바운스
  useEffect(() => {
    const q = qInput.trim()
    const t = setTimeout(() => setFilter((f) => (f.q === q ? f : { ...f, q })), 300)
    return () => clearTimeout(t)
  }, [qInput])

  useEffect(() => {
    filterRef.current = filter
    writeUrl(filter)
  }, [filter])

  useEffect(() => {
    itemsRef.current = items
  }, [items])

  useEffect(() => {
    listTools().then((ts) => {
      setTools(ts)
      // URL의 알 수 없는 tool 값은 버린다
      setFilter((f) => {
        const known = f.tools.filter((id) => ts.some((t) => t.id === id))
        return known.length === f.tools.length ? f : { ...f, tools: known }
      })
    }).catch((e: Error) => setToolsError(e.message))
  }, [])

  // 필터가 바뀌거나 페이지를 넘기면 조회. 0페이지는 교체, 이후는 이어붙임
  useEffect(() => {
    const ac = new AbortController()
    setLoading(true)
    setError('')
    listReleases(filter, page, ac.signal)
      .then((r) => {
        setItems((prev) => (page === 0 ? r.items : [...prev, ...r.items.filter((x) => !prev.some((p) => p.id === x.id))]))
        setTotal(r.total)
        setLoading(false)
      })
      .catch((e: Error) => {
        if (e.name === 'AbortError') return
        setError(e.message)
        setLoading(false)
      })
    return () => ac.abort()
  }, [filter, page])

  const changeFilter = (patch: Partial<ReleaseQuery>) => {
    setPage(0)
    setFilter((f) => ({ ...f, ...patch }))
  }

  // 실시간 수신. 연결 끊기면 EventSource가 알아서 재연결
  useEffect(() => {
    const es = new EventSource('/api/releases/stream')
    es.onopen = () => setLive(true)
    es.onerror = () => setLive(false)
    es.addEventListener('release', (e) => {
      let r: ReleaseSummary
      try {
        r = JSON.parse((e as MessageEvent).data)
      } catch {
        return
      }
      setTools((ts) =>
        ts.map((t) => (t.id === r.tool && (!t.latest || r.publishedAt > t.latest.publishedAt) ? { ...t, latest: r } : t)),
      )
      if (!matches(r, filterRef.current)) return
      if (itemsRef.current.some((p) => p.id === r.id)) return
      itemsRef.current = [r, ...itemsRef.current]
      setItems(itemsRef.current)
      setTotal((n) => n + 1)
      setFresh((s) => new Set(s).add(r.id))
      setTimeout(() => setFresh((s) => { const n = new Set(s); n.delete(r.id); return n }), 3000)
    })
    return () => es.close()
  }, [])

  const toolName = useMemo(() => new Map(tools.map((t) => [t.id, t.name])), [tools])
  const categories = useMemo(() => {
    const m = new Map<string, Tool[]>()
    tools.forEach((t) => m.set(t.category, [...(m.get(t.category) ?? []), t]))
    return [...m]
  }, [tools])

  const toggleTool = (id: ToolId) =>
    changeFilter({ tools: filter.tools.includes(id) ? filter.tools.filter((t) => t !== id) : [...filter.tools, id] })

  return (
    <div className={`app${selected !== null ? ' has-detail' : ''}`}>
      <header>
        <h1>
          Patch Radar{' '}
          <span className={`dot${live ? ' on' : ''}`} role="status" aria-label={live ? '실시간 연결됨' : '실시간 연결 끊김'} title={live ? '실시간 연결됨' : '연결 끊김, 재연결 중'} />
        </h1>
        <p>DevOps OSS 공식 패치 뉴스를 한 화면에서.</p>
      </header>

      <section aria-label="도구">
        {toolsError && <p className="msg error" role="alert">도구 목록을 불러오지 못했습니다: {toolsError}</p>}
        {categories.map(([cat, ts]) => (
          <div className="cat" key={cat}>
            <span className="cat-name">{cat}</span>
            {ts.map((t) => (
              <button key={t.id} type="button" className="chip" aria-pressed={filter.tools.includes(t.id)} onClick={() => toggleTool(t.id)}>
                {t.name}
                <small>{t.latest ? (t.latest.version ?? t.latest.title) : '-'}</small>
              </button>
            ))}
          </div>
        ))}
      </section>

      <form className="filters" role="search" onSubmit={(e) => e.preventDefault()}>
        <label>검색<input type="search" maxLength={100} value={qInput} onChange={(e) => setQInput(e.target.value)} placeholder="제목·본문" /></label>
        <label>시작일<input type="date" value={filter.from} max={filter.to || undefined} onChange={(e) => changeFilter({ from: e.target.value })} /></label>
        <label>종료일<input type="date" value={filter.to} min={filter.from || undefined} onChange={(e) => changeFilter({ to: e.target.value })} /></label>
        <label className="check"><input type="checkbox" checked={filter.security} onChange={(e) => changeFilter({ security: e.target.checked })} />보안 관련만</label>
      </form>

      <div className="main">
        <section aria-label="릴리스 목록" aria-busy={loading}>
          {error && <p className="msg error" role="alert">릴리스를 불러오지 못했습니다. release-svc 상태를 확인하세요. ({error})</p>}
          {!error && !loading && items.length === 0 && <p className="msg">조건에 맞는 릴리스가 없습니다.</p>}
          <ul className="list">
            {items.map((r) => (
              <li key={r.id} className={fresh.has(r.id) ? 'fresh' : undefined}>
                <button type="button" className="item" aria-current={selected === r.id} onClick={() => setSelected(r.id)}>
                  <span className="tool">{toolName.get(r.tool) ?? r.tool}</span>
                  <span className="ver">{r.version ?? r.title}</span>
                  {r.security && <span className="badge">보안</span>}
                  <time dateTime={r.publishedAt} title={new Date(r.publishedAt).toLocaleString('ko-KR')}>{relTime(r.publishedAt)}</time>
                </button>
              </li>
            ))}
          </ul>
          {loading && <p className="msg">불러오는 중…</p>}
          {!loading && !error && items.length < total && (
            <button type="button" className="more" onClick={() => setPage(page + 1)}>더 보기 ({items.length}/{total})</button>
          )}
        </section>
        {selected !== null && <Detail key={selected} id={selected} onClose={() => setSelected(null)} />}
      </div>
    </div>
  )
}

const md = (s: string) => DOMPurify.sanitize(marked.parse(s, { async: false }) as string, SANITIZE)

function summaryError(e: unknown) {
  if (!(e instanceof ApiError)) return '요약 생성 중 오류가 발생했습니다.'
  if (e.status === 429) return `요약 요청이 많습니다. ${e.retryAfter ?? ''}초 후 다시 시도하세요`.replace('  ', ' ')
  if (e.status === 503) return '요약 기능이 설정되지 않았습니다'
  if (e.status === 502) return '요약 생성에 실패했습니다. 잠시 후 다시 시도하세요'
  return '요약 생성 중 오류가 발생했습니다.'
}

function SummaryCard({ id }: { id: number }) {
  const [sum, setSum] = useState<Summary | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const ac = useRef<AbortController | null>(null)

  // Detail이 id로 key되어 있어 id가 바뀌면 언마운트된다. 조회 실패는 조용히 버튼만
  useEffect(() => {
    const c = new AbortController()
    getSummary(id, c.signal).then(setSum).catch(() => {})
    return () => { c.abort(); ac.current?.abort() }
  }, [id])

  const generate = () => {
    ac.current?.abort()
    const c = (ac.current = new AbortController())
    setBusy(true)
    setErr('')
    createSummary(id, c.signal)
      .then((s) => { setSum(s); setBusy(false) })
      .catch((e: unknown) => {
        if (c.signal.aborted) return
        setErr(summaryError(e))
        setBusy(false)
      })
  }

  const html = useMemo(() => (sum ? md(sum.summary) : ''), [sum])

  if (!sum) {
    return (
      <div className="summary-actions">
        <button type="button" className="more" disabled={busy} onClick={generate}>핵심요약</button>
        {busy && <p className="msg" role="status">요약 생성 중… (최대 1분 정도)</p>}
        {err && <p className="msg error" role="alert">{err}</p>}
      </div>
    )
  }
  return (
    <section className="summary" aria-label="핵심요약">
      <h3>핵심요약</h3>
      <div className="body" dangerouslySetInnerHTML={{ __html: html }} />
      <small>AI({sum.model})로 생성 · AI 요약은 틀릴 수 있으니 원문을 확인하세요 · <time dateTime={sum.createdAt}>{new Date(sum.createdAt).toLocaleString('ko-KR')}</time></small>
    </section>
  )
}

function Detail({ id, onClose }: { id: number; onClose: () => void }) {
  const [rel, setRel] = useState<Release | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let stale = false
    getRelease(id).then((r) => !stale && setRel(r)).catch((e: Error) => !stale && setError(e.message))
    return () => { stale = true }
  }, [id])

  const html = useMemo(() => {
    if (!rel?.body || (rel.bodyFormat !== 'markdown' && rel.bodyFormat !== 'html')) return null
    const raw = rel.bodyFormat === 'markdown' ? (marked.parse(rel.body, { async: false }) as string) : rel.body
    return DOMPurify.sanitize(raw, SANITIZE)
  }, [rel])

  return (
    <aside className="detail" aria-label="릴리스 상세">
      <button type="button" className="close" onClick={onClose}>닫기</button>
      {error && <p className="msg error" role="alert">상세를 불러오지 못했습니다. ({error})</p>}
      {!rel && !error && <p className="msg">불러오는 중…</p>}
      {rel && (
        <>
          <h2>{rel.title}</h2>
          <p>{isHttp(rel.url)
            ? <a href={rel.url} target="_blank" rel="noopener noreferrer">원문 보기 ↗</a>
            : <span>{rel.url}</span>}</p>
          <SummaryCard id={id} />
          {html !== null ? <div className="body" dangerouslySetInnerHTML={{ __html: html }} />
            : rel.body ? <pre className="body">{rel.body}</pre>
            : <p className="msg">본문이 없습니다. 원문 링크를 확인하세요.</p>}
        </>
      )}
    </aside>
  )
}
