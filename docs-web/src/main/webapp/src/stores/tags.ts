import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listTags, type Tag } from '../api/tag'

export const useTagStore = defineStore('tags', () => {
  const tags = ref<Tag[]>([])

  async function fetchTags() {
    const { data } = await listTags()
    tags.value = data.tags
  }

  return { tags, fetchTags }
})
