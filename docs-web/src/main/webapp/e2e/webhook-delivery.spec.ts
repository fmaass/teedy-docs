import { test, expect } from './fixtures'
import { createServer, type Server, type IncomingMessage, type ServerResponse } from 'node:http'
import { AddressInfo } from 'node:net'

// Webhook delivery — OBSERVED, not merely CRUD'd. We stand up a real HTTP listener
// inside the test process, register a DOCUMENT_CREATED webhook in Teedy pointing at
// it, create a document, and assert the container actually POSTed the expected
// payload to our listener. This exercises the full async delivery path
// (WebhookAsyncListener → okhttp POST), not just the settings row.
//
// Reachability: the app runs in a Docker container; the listener runs on the host.
// The harness (scripts/e2e-run.sh) GUARANTEES the topology: it boots the container
// with `--add-host=host.docker.internal:host-gateway` (makes the host alias resolve —
// a no-op on Docker Desktop, required on Linux/CI) and DOCS_WEBHOOK_ALLOW_PRIVATE=true
// (the SSRF guard otherwise refuses the private host-gateway target by design).
// Because the harness promises this, a registration failure is a REAL failure — a
// regression that drops the allow-flag or breaks the alias must turn this spec red,
// never a green skip that would silently eliminate delivery coverage in CI.

const HOST_ALIAS = 'host.docker.internal'
const HOOK_PATH = '/teedy-webhook'

interface Captured {
  method: string
  url: string
  headers: IncomingMessage['headers']
  body: string
}

// Start a one-shot HTTP listener on an ephemeral port. Resolves once the socket is
// actually bound (server.address() is null until the 'listening' event fires) and
// returns the assigned port plus a promise that resolves with the first captured
// request. Every request is answered 200 so the server never appears down to the caller.
async function startListener(): Promise<{
  port: number
  server: Server
  received: Promise<Captured>
}> {
  let resolveReceived!: (c: Captured) => void
  const received = new Promise<Captured>((resolve) => {
    resolveReceived = resolve
  })

  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const chunks: Buffer[] = []
    req.on('data', (c) => chunks.push(c as Buffer))
    req.on('end', () => {
      resolveReceived({
        method: req.method ?? '',
        url: req.url ?? '',
        headers: req.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      })
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end('{"ok":true}')
    })
  })
  // 0.0.0.0 so the container (reaching us via host.docker.internal) can connect, not
  // just loopback. Await the bind so the port is assigned before we return.
  const port = await new Promise<number>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '0.0.0.0', () => resolve((server.address() as AddressInfo).port))
  })
  return { port, server, received }
}

test('a DOCUMENT_CREATED webhook is delivered to a live listener with the expected payload', async ({ request }) => {
  const { port, server, received } = await startListener()
  const hookUrl = `http://${HOST_ALIAS}:${port}${HOOK_PATH}`

  let webhookId: string | undefined
  let documentId: string | undefined
  try {
    // Register the webhook via the admin API. The harness guarantees the topology
    // (host-gateway alias + DOCS_WEBHOOK_ALLOW_PRIVATE), so a rejection here — e.g.
    // the allow-flag dropped or the alias unresolvable — is a hard failure.
    const addRes = await request.put('/api/webhook', {
      form: { event: 'DOCUMENT_CREATED', url: hookUrl },
    })
    expect(
      addRes.ok(),
      `register webhook -> ${hookUrl} (HTTP ${addRes.status()}; the harness must boot the ` +
        `container with --add-host=host.docker.internal:host-gateway and ` +
        `DOCS_WEBHOOK_ALLOW_PRIVATE=true — see scripts/e2e-run.sh)`,
    ).toBeTruthy()

    // Find the webhook id (for teardown).
    const listRes = await request.get('/api/webhook')
    const hooks = (await listRes.json()).webhooks as Array<{ id: string; url: string }>
    webhookId = hooks.find((h) => h.url === hookUrl)?.id

    // Trigger the event: create a document. The DocumentCreatedAsyncEvent fans out to
    // WebhookAsyncListener which POSTs our listener.
    const createRes = await request.put('/api/document', {
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      data: new URLSearchParams([['title', `webhook-doc-${Date.now()}`], ['language', 'eng']]).toString(),
    })
    expect(createRes.ok(), 'create document (fires DOCUMENT_CREATED)').toBeTruthy()
    documentId = (await createRes.json()).id as string

    // OBSERVE the delivery. Race the capture against a timeout so a non-delivery fails
    // loudly instead of hanging.
    const captured = await Promise.race<Captured | 'timeout'>([
      received,
      new Promise<'timeout'>((resolve) => setTimeout(() => resolve('timeout'), 15_000)),
    ])
    expect(captured, 'webhook was delivered within 15s').not.toBe('timeout')

    const { method, url, headers, body } = captured as Captured
    // The delivery is a POST to the exact path we registered, carrying JSON.
    expect(method, 'delivery HTTP method').toBe('POST')
    expect(url, 'delivery request path').toBe(HOOK_PATH)
    expect(headers['content-type'] ?? '').toContain('application/json')
    const payload = JSON.parse(body)
    // The historical payload shape: {"event": "...", "id": "..."} — and the id must be
    // the document we just created, proving THIS event drove the delivery.
    expect(payload.event, 'payload.event').toBe('DOCUMENT_CREATED')
    expect(payload.id, 'payload.id === created document id').toBe(documentId)
  } finally {
    server.close()
    if (documentId) await request.delete(`/api/document/${documentId}`).catch(() => {})
    if (webhookId) await request.delete(`/api/webhook/${webhookId}`).catch(() => {})
  }
})
