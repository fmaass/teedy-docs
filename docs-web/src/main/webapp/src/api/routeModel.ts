import api from './client'

// Route models (workflow templates) admin API. All mutating endpoints require ADMIN;
// the list GET is authenticated + READ-ACL filtered. Steps are transported as a JSON
// *string* on the wire (both directions) — see serializeSteps/parseSteps in
// utils/routeModelSteps.ts for the strict shape the backend's validateRouteModelSteps
// enforces.

export interface RouteModelListItem {
  id: string
  name: string
  create_date: number
  // Derived server-side: true when a step target no longer resolves to an active user/group.
  incomplete: boolean
}

// An ACL entry as returned by AclUtil.addAcls: {perm, id (targetId), name, type}.
export interface RouteModelAcl {
  perm: 'READ' | 'WRITE'
  id: string
  name: string | null
  type: 'USER' | 'GROUP'
}

// GET /routemodel/{id}. `steps` is a JSON STRING — parse it with parseSteps.
export interface RouteModelDetail {
  id: string
  name: string
  create_date: number
  steps: string
  acls: RouteModelAcl[]
  writable: boolean
}

export function listRouteModels(sortColumn?: number, asc?: boolean) {
  const params: Record<string, unknown> = {}
  if (sortColumn != null) params.sort_column = sortColumn
  if (asc != null) params.asc = asc
  return api.get<{ routemodels: RouteModelListItem[] }>('/routemodel', { params })
}

export function getRouteModel(id: string) {
  return api.get<RouteModelDetail>(`/routemodel/${id}`)
}

export function createRouteModel(name: string, steps: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('steps', steps)
  return api.put<{ id: string }>('/routemodel', params)
}

export function updateRouteModel(id: string, name: string, steps: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  params.set('steps', steps)
  return api.post<{ status: string }>(`/routemodel/${id}`, params)
}

export function deleteRouteModel(id: string) {
  return api.delete<{ status: string }>(`/routemodel/${id}`)
}

// Query keys local to this module (per phase convention — not in the shared queryKeys.ts).
export const routeModelKeys = {
  all: () => ['route-models'] as const,
  detail: (id: string) => ['route-model', id] as const,
}
