import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RelationSortDirection } from '../utils/relationSort'

// #296 — the reader's chosen order for "Related documents", held once for the whole session.
//
// Why a store and not a component ref: the reporter's complaint is about re-deciding the order
// on every document, so the choice has to outlive a single DocumentViewContent mount. Pinia is
// where this app already keeps cross-view state (auth, tagFilter), and one pinia instance lives
// exactly as long as the SPA does.
//
// Why NOT localStorage — deliberate, and the difference from the file-view mode next door
// (`teedy_file_view_mode`): that one is a lasting workspace preference, this is a way of LOOKING
// at one list right now. It resets on the next reload, so nothing a reader flips here quietly
// re-frames the document view weeks later.
//
// `null` is the DEFAULT and is not a direction: it means "leave the server's order alone". The
// backend already orders by title, but in the DATABASE's collation — re-collating that in the
// browser on every mount would silently overrule it on exactly the case/accent pairs where the
// two disagree. So the collator is applied only after the reader asks for a direction.
export const useRelationSortStore = defineStore('relationSort', () => {
  const direction = ref<RelationSortDirection | null>(null)
  return { direction }
})
