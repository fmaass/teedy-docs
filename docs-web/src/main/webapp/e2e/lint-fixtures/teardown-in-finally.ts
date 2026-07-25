// LINT FIXTURE — this file is EXPECTED TO FAIL `eslint`.
//
// It is the executable proof that the `no-restricted-syntax` guardrail added for #187
// actually rejects a newly-written teardown-in-`finally`, in every shape it takes —
// deliberately using INLINE cleanup calls rather than the `deleteDoc` helper, because a
// rule keyed on one helper name is evaded by the next person who inlines the call.
//
// It is excluded from `npm run lint` (see the `--ignore-pattern` in package.json) and
// asserted the other way round by `npm run lint:teardown-rule`, which fails if ESLint
// stops reporting every case below. Nothing imports it and Playwright never collects it
// (it is not a `*.spec.ts`).
//
// EXPECTED: 11 `no-restricted-syntax` errors — one per numbered case, none for `ok()`.

declare const page: { request: { delete(url: string): Promise<unknown> } }
declare const request: { post(url: string, init: unknown): Promise<unknown> }
declare const expect: (actual: unknown) => { toBe(v: unknown): Promise<void> }
declare const ids: string[]
declare const id: string
declare const other: string
declare function guardedTeardown(label: string, fn: () => unknown): Promise<void>

// 1. The plain awaited inline cleanup call.
export async function inlineAwaitedCall(): Promise<void> {
  try {
    /* body */
  } finally {
    await page.request.delete(`/api/document/${id}`)
  }
}

// 2. Fire-and-forget (not awaited) — still teardown, still masks by hanging the worker.
export async function unawaitedCall(): Promise<void> {
  try {
    /* body */
  } finally {
    page.request.delete(`/api/document/${id}`)
  }
}

// 3. Guarded by a conditional, which is how most real teardown is written.
export async function conditionalCleanup(): Promise<void> {
  try {
    /* body */
  } finally {
    if (id) await page.request.delete(`/api/document/${id}`)
  }
}

// 4. A loop over seeded entities.
export async function loopingCleanup(): Promise<void> {
  try {
    /* body */
  } finally {
    for (const each of ids) await page.request.delete(`/api/document/${each}`)
  }
}

// 5. An assertion in the finalizer — it supersedes the body's failure outright.
export async function assertionInFinally(): Promise<void> {
  try {
    /* body */
  } finally {
    await expect(1).toBe(1)
  }
}

// 6. Config restore, the non-document teardown class.
export async function configRestoreInFinally(): Promise<void> {
  try {
    /* body */
  } finally {
    await request.post('/api/app/guest_login', { form: { enabled: false } })
  }
}

// --- Expression wrappers. Each of these is a real cleanup call that an earlier form of the
// --- rule let through, because the call was no longer a DIRECT child of the statement.

// 7. Short-circuit guard — the idiomatic one-line replacement for case 3's `if`.
export async function logicalGuardCleanup(): Promise<void> {
  try {
    /* body */
  } finally {
    id && page.request.delete(`/api/document/${id}`)
  }
}

// 8. The same guard, awaited: the wrapper sits between the `await` and the call.
export async function awaitedLogicalGuardCleanup(): Promise<void> {
  try {
    /* body */
  } finally {
    await (id && page.request.delete(`/api/document/${id}`))
  }
}

// 9. Ternary — teardown chosen between two branches.
export async function conditionalExpressionCleanup(): Promise<void> {
  try {
    /* body */
  } finally {
    await (id ? page.request.delete(`/api/document/${id}`) : Promise.resolve())
  }
}

// 10. Comma operator — two cleanups packed into one statement.
export async function sequenceCleanup(): Promise<void> {
  try {
    /* body */
  } finally {
    page.request.delete(`/api/document/${id}`), page.request.delete(`/api/document/${other}`)
  }
}

// 11. `void` — the "I know it floats" spelling of case 2.
export async function voidedCleanup(): Promise<void> {
  try {
    /* body */
  } finally {
    void page.request.delete(`/api/document/${id}`)
  }
}

// OK — the single sanctioned exemption: the designated guarded wrapper, which cannot
// throw out of the finalizer and therefore cannot supersede the body's error.
export async function ok(): Promise<void> {
  try {
    /* body */
  } finally {
    await guardedTeardown('delete seeded document', () => page.request.delete(`/api/document/${id}`))
  }
}
