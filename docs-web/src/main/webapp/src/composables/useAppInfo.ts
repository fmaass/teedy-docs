import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { getAppInfo, type AppInfo } from '../api/app'
import { queryKeys } from '../api/queryKeys'

/**
 * Single source of truth for GET /api/app across the app.
 *
 * The app-info payload (running version, OIDC/guest flags, trash retention, the
 * admin-editable config fields) was fetched under two different query keys
 * (`['app-config']`, `['app-info']`) plus ad-hoc axios calls in Login, AboutDialog
 * and DocumentEdit. Divergent keys meant a config mutation could invalidate one
 * cache while another served stale data. This composable routes every consumer
 * through the ONE key (`queryKeys.app()`), so a single invalidation keeps them all
 * in sync. `staleTime: Infinity` matches the previous DocumentTrash behaviour —
 * app info rarely changes within a session and is refetched explicitly on config
 * mutations.
 */
export function useAppInfo() {
  return useQuery<AppInfo>({
    queryKey: queryKeys.app(),
    queryFn: () => getAppInfo(),
    staleTime: Infinity,
  })
}

/**
 * Invalidate the shared app-info cache. Config mutations (save settings, toggle
 * OCR, reindex) call this so the ensuing refetch reconciles every consumer.
 */
export function useInvalidateAppInfo() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: queryKeys.app() })
}
