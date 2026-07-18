import { expectTypeOf } from 'vitest'
import type { DocumentDetail } from './document'

// The document detail's file list gained the additive fields the enriched file view needs:
// the zero-based version, the create date, and the current-version uploader (creator). The file
// panel and organizer consume these, so pin their presence and types at the API boundary.
type DocumentFile = NonNullable<DocumentDetail['files']>[number]

expectTypeOf<DocumentFile>().toHaveProperty('version').toEqualTypeOf<number>()
expectTypeOf<DocumentFile>().toHaveProperty('create_date').toEqualTypeOf<number>()
expectTypeOf<DocumentFile>().toHaveProperty('creator').toEqualTypeOf<string>()
