import api from './client'

export interface AclTarget {
  id: string
  name: string
  type: 'USER' | 'GROUP'
}

/**
 * A direct ACL entry as returned by the backend's AclUtil.addAcls on any ACL source
 * (documents, tags, route models): {perm, id (the target id), name, type}. Shared so the
 * AclEditor component and every source's detail typing agree on one shape.
 */
export interface AclEntry {
  perm: 'READ' | 'WRITE'
  id: string
  name: string | null
  type: 'USER' | 'GROUP'
}

export function searchAclTargets(search: string) {
  return api.get<{ users: AclTarget[]; groups: AclTarget[] }>('/acl/target/search', { params: { search } })
}

export function addAcl(sourceId: string, perm: 'READ' | 'WRITE', targetName: string, type: 'USER' | 'GROUP') {
  const params = new URLSearchParams()
  params.set('source', sourceId)
  params.set('perm', perm)
  params.set('target', targetName)
  params.set('type', type)
  return api.put('/acl', params)
}

export function deleteAcl(sourceId: string, perm: 'READ' | 'WRITE', targetId: string) {
  return api.delete(`/acl/${sourceId}/${perm}/${targetId}`)
}
