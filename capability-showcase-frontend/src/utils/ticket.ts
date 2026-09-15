/** 与 conversation-service Ticket record 对齐的只读视图；缺字段不臆造。 */
export interface TicketView {
  title?: string
  priority?: string
  category?: string
  summary?: string
  nextSteps?: string[]
}

export function parseTicket(data: unknown): TicketView | null {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null
  const o = data as Record<string, unknown>
  const title = typeof o.title === 'string' && o.title.trim() ? o.title : undefined
  const priority = typeof o.priority === 'string' && o.priority.trim() ? o.priority : undefined
  const category = typeof o.category === 'string' && o.category.trim() ? o.category : undefined
  const summary = typeof o.summary === 'string' && o.summary.trim() ? o.summary : undefined
  const nextSteps = Array.isArray(o.nextSteps)
    ? o.nextSteps.filter((x): x is string => typeof x === 'string' && x.trim().length > 0)
    : undefined
  if (!title && !priority && !category && !summary && !nextSteps?.length) return null
  return { title, priority, category, summary, nextSteps }
}
