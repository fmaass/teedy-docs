<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  listComments,
  addComment,
  deleteComment,
  gravatarUrl,
  type Comment,
} from '../../api/comment'
import { useAuthStore } from '../../stores/auth'
import Avatar from 'primevue/avatar'
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'
import Skeleton from 'primevue/skeleton'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import EmptyState from '../../components/EmptyState.vue'
import ErrorState from '../../components/ErrorState.vue'
import { injectDocument } from './documentKey'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const auth = useAuthStore()
const queryClient = useQueryClient()

const doc = injectDocument()
const docId = computed(() => doc.value?.id)

const {
  data: comments,
  isLoading: loading,
  isError,
  refetch,
} = useQuery({
  queryKey: computed(() => ['comments', docId.value]),
  queryFn: () => listComments(docId.value as string).then((r) => r.data.comments),
  enabled: computed(() => !!docId.value),
})

const newComment = ref('')
const submitting = ref(false)

// A user may delete a comment if they authored it or hold WRITE on the document
// (the backend enforces the same rule; this only gates showing the button).
function canDelete(comment: Comment): boolean {
  return comment.creator === auth.username || doc.value?.writable === true
}

async function submit() {
  const content = newComment.value.trim()
  if (!content || !docId.value) return
  submitting.value = true
  try {
    await addComment(docId.value, content)
    newComment.value = ''
    await queryClient.invalidateQueries({ queryKey: ['comments', docId.value] })
    toast.add({ severity: 'success', summary: t('document.view.comment_added'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('document.view.comment_add_failed'), life: 3000 })
  } finally {
    submitting.value = false
  }
}

function confirmDelete(comment: Comment) {
  confirmDanger({
    message: t('document.view.delete_comment_message'),
    header: t('document.view.delete_comment_title'),
    accept: async () => {
      try {
        await deleteComment(comment.id)
        await queryClient.invalidateQueries({ queryKey: ['comments', docId.value] })
        toast.add({ severity: 'success', summary: t('document.view.comment_deleted'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('document.view.comment_delete_failed'), life: 3000 })
      }
    },
  })
}

function formatDate(ts: number) {
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div class="comments">
    <!-- Add comment -->
    <form class="comment-form" @submit.prevent="submit">
      <Textarea
        v-model="newComment"
        :placeholder="t('document.view.add_comment')"
        :aria-label="t('document.view.add_comment')"
        rows="3"
        autoResize
        class="comment-input"
        :disabled="submitting"
      />
      <div class="comment-form-actions">
        <Button
          type="submit"
          :label="t('document.view.post_comment')"
          icon="pi pi-send"
          size="small"
          :loading="submitting"
          :disabled="!newComment.trim()"
        />
      </div>
    </form>

    <!-- Loading -->
    <div v-if="loading" class="comment-loading">
      <Skeleton width="100%" height="3rem" class="mb-2" />
      <Skeleton width="100%" height="3rem" />
    </div>

    <!-- Error -->
    <ErrorState v-else-if="isError" :message="t('document.view.error_loading_comments')" @retry="refetch()" />

    <!-- Empty -->
    <EmptyState
      v-else-if="!comments || comments.length === 0"
      icon="pi pi-comments"
      :message="t('document.view.no_comments')"
    />

    <!-- List -->
    <ul v-else class="comment-list">
      <li v-for="comment in comments" :key="comment.id" class="comment-item">
        <Avatar :image="gravatarUrl(comment.creator_gravatar)" shape="circle" size="normal" />
        <div class="comment-body">
          <div class="comment-head">
            <strong class="comment-author">{{ comment.creator }}</strong>
            <span class="comment-date">{{ formatDate(comment.create_date) }}</span>
            <Button
              v-if="canDelete(comment)"
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              :aria-label="t('delete')"
              class="comment-delete"
              @click="confirmDelete(comment)"
            />
          </div>
          <p class="comment-content">{{ comment.content }}</p>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.comments {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.comment-form {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.comment-input {
  width: 100%;
  resize: vertical;
}

.comment-form-actions {
  display: flex;
  justify-content: flex-end;
}

.comment-loading {
  padding: 0.5rem 0;
}

.comment-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.comment-item {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
}

.comment-body {
  flex: 1;
  min-width: 0;
}

.comment-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.comment-author {
  font-size: 0.875rem;
}

.comment-date {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.comment-delete {
  margin-left: auto;
}

.comment-content {
  margin: 0.25rem 0 0;
  font-size: 0.9375rem;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
