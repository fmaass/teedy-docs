<script setup lang="ts">
import { useAuthStore } from '../../stores/auth'
import TabMenu from 'primevue/tabmenu'
import { ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const items = ref([
  { label: 'Account', route: 'settings-account' },
  ...(auth.isAdmin ? [
    { label: 'Configuration', route: 'settings-config' },
    { label: 'Users', route: 'settings-users' },
    { label: 'Tag Rules', route: 'settings-tag-rules' },
  ] : []),
])

const activeIndex = ref(items.value.findIndex((i) => i.route === route.name))

watch(() => route.name, (name) => {
  activeIndex.value = items.value.findIndex((i) => i.route === name)
})

function onTabChange(e: any) {
  router.push({ name: items.value[e.index].route })
}
</script>

<template>
  <div>
    <h2>Settings</h2>
    <TabMenu :model="items" :activeIndex="activeIndex" @tab-change="onTabChange" />
    <div style="margin-top: 1.5rem">
      <router-view />
    </div>
  </div>
</template>
