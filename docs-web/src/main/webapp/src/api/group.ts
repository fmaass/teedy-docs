import api from './client'

export interface GroupListItem {
  name: string
  parent: string | null
}

export interface GroupDetail {
  name: string
  parent?: string
  members: string[]
}

export function listGroups() {
  return api.get<{ groups: GroupListItem[] }>('/group', { params: { sort_column: 1, asc: true } })
}

export function getGroup(name: string) {
  return api.get<GroupDetail>(`/group/${name}`)
}

export function createGroup(name: string, parent?: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  if (parent) params.set('parent', parent)
  return api.put('/group', params)
}

export function updateGroup(currentName: string, name: string, parent?: string) {
  const params = new URLSearchParams()
  params.set('name', name)
  if (parent) params.set('parent', parent)
  return api.post(`/group/${currentName}`, params)
}

export function deleteGroup(name: string) {
  return api.delete(`/group/${name}`)
}

export function addGroupMember(name: string, username: string) {
  const params = new URLSearchParams()
  params.set('username', username)
  return api.put(`/group/${name}`, params)
}

export function removeGroupMember(name: string, username: string) {
  return api.delete(`/group/${name}/${username}`)
}
