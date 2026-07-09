import { useConfirm } from 'primevue/useconfirm'

/**
 * Options for a destructive confirmation dialog.
 *
 * Mirrors the subset of PrimeVue `ConfirmationOptions` that every destructive
 * confirm in the app actually sets. `acceptProps`/`rejectProps` are NOT exposed —
 * they are fixed to the shared danger styling so every "are you sure you want to
 * delete this" dialog looks and behaves identically.
 */
export interface ConfirmDangerOptions {
  /** Dialog title. */
  header: string
  /** Dialog body prompt. */
  message: string
  /** Called when the user confirms the destructive action. */
  accept: () => void | Promise<void>
  /**
   * Header icon. Defaults to the trash icon; pass a different `pi pi-*` for
   * non-delete destructive actions (e.g. revoke a share, disable 2FA, reindex).
   */
  icon?: string
  /** Optional handler for the reject/cancel path. */
  reject?: () => void
}

/**
 * A confirm helper that wraps `useConfirm` with the shared destructive-action
 * styling: a danger-severity accept button and a secondary-outlined reject
 * button. Extracted because this exact 7-line block was copy-pasted across ~18
 * call sites in ~15 views — a single seam keeps the danger affordance consistent
 * and lets a future restyle land in one place.
 *
 * Call sites pass only their own header/message/accept (and an icon override for
 * non-trash actions); the danger props are applied here.
 */
export function useConfirmDanger(): { confirmDanger: (opts: ConfirmDangerOptions) => void } {
  const confirm = useConfirm()

  function confirmDanger(opts: ConfirmDangerOptions): void {
    confirm.require({
      header: opts.header,
      message: opts.message,
      icon: opts.icon ?? 'pi pi-trash',
      acceptProps: { severity: 'danger' },
      rejectProps: { severity: 'secondary', outlined: true },
      accept: opts.accept,
      ...(opts.reject ? { reject: opts.reject } : {}),
    })
  }

  return { confirmDanger }
}
