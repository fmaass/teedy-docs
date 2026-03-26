<script setup lang="ts">
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
</script>

<template>
  <div>
    <h3>Account</h3>
    <p>Logged in as <strong>{{ auth.username }}</strong></p>
    <p v-if="auth.user?.email">Email: {{ auth.user.email }}</p>
  </div>
</template>
