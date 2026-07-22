const MAX_CONCURRENT = 4

interface QueueItem {
  fileId: string
  size: 'web' | 'thumb' | 'content' | undefined
  priority: number
  controller: AbortController
  resolve: (blob: Blob | null) => void
  shareId?: string
  rotation?: number
}

export interface PreviewQueue {
  enqueue(
    fileId: string,
    size: 'web' | 'thumb' | 'content' | undefined,
    priority: number,
    shareId?: string,
    rotation?: number,
  ): Promise<Blob | null>
  cancel(): void
  reprioritize(fileId: string, priority: number): void
}

export function usePreviewQueue(): PreviewQueue {
  const pending: QueueItem[] = []
  const running: Set<QueueItem> = new Set()

  function buildUrl(item: QueueItem): string {
    const query = new URLSearchParams()
    if (item.size) query.set('size', item.size)
    if (item.shareId) query.set('share', item.shareId)
    if ((item.size === 'web' || item.size === 'thumb') && item.rotation)
      query.set('v', String(item.rotation))
    const suffix = query.toString()
    return `api/file/${item.fileId}/data${suffix ? `?${suffix}` : ''}`
  }

  function drain() {
    while (running.size < MAX_CONCURRENT && pending.length > 0) {
      pending.sort((a, b) => a.priority - b.priority)
      const item = pending.shift()!
      running.add(item)
      run(item)
    }
  }

  async function run(item: QueueItem) {
    try {
      const response = await fetch(buildUrl(item), {
        signal: item.controller.signal,
        credentials: 'include',
      })
      if (!response.ok) {
        item.resolve(null)
        return
      }
      item.resolve(await response.blob())
    } catch {
      item.resolve(null)
    } finally {
      running.delete(item)
      drain()
    }
  }

  function enqueue(
    fileId: string,
    size: 'web' | 'thumb' | 'content' | undefined,
    priority: number,
    shareId?: string,
    rotation?: number,
  ): Promise<Blob | null> {
    return new Promise<Blob | null>((resolve) => {
      const controller = new AbortController()
      pending.push({ fileId, size, priority, controller, resolve, shareId, rotation })
      drain()
    })
  }

  function cancel() {
    for (const item of pending.splice(0)) {
      item.controller.abort()
      item.resolve(null)
    }
    for (const item of running) {
      item.controller.abort()
    }
  }

  function reprioritize(fileId: string, priority: number) {
    for (const item of pending) {
      if (item.fileId === fileId) item.priority = priority
    }
  }

  return { enqueue, cancel, reprioritize }
}
