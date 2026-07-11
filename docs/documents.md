# Documents

A document in Teedy is a container: a title, Dublin Core and custom metadata, and
one or more attached files (PDFs, images, Office files, video). This page covers
the document lifecycle beyond simple upload — file versions, relations, viewer
rotation, custom metadata, and the trash.

## Files and versions

A document holds one or more files. When you upload a replacement for an existing
file, Teedy keeps the old copy as a **version** rather than overwriting it, so you
always have the history of a file.

- Upload a new version by uploading a file with `PUT /api/file` and referencing the
  file it replaces (`previousFileId`). The new upload becomes the current version;
  the previous one is retained in the version chain.
- List a file's versions with `GET /api/file/{id}/versions` — it returns every file
  in the same version chain, oldest to newest.

To "revert", upload the older file's content as a new version — the version chain
always moves forward, so the restored copy becomes the new current version.

## Relations

Relations link two documents together (for example an invoice and its matching
purchase order). A relation is directional: from the document you edit, the other
document is either the source or the target of the link.

Edit relations from the document's edit view: search for the other document by
title and add it. Relations round-trip through the document API as a `relations`
form parameter (a list of related document IDs) on document create/update, and are
returned on `GET /api/document/{id}` as an array with each related document's `id`,
`title`, and whether it is the `source` of the relation.

## Viewing and rotation

The built-in viewer renders PDFs and images inline. When a scan comes in sideways,
rotate it in the viewer with the rotate-left and rotate-right controls — each press
turns the view 90°.

<!-- screenshot: the document viewer showing the rotation controls (rotate-left / rotate-right) on a PDF -->

Rotation is a **view-only** adjustment: it changes how the page is displayed in
your current session and is not written back to the stored file. Reopening the
document shows it in its original orientation.

## Metadata

Every document carries Dublin Core metadata plus any **custom metadata fields** an
admin has defined. Custom fields have a type that controls their input:

| Type | Input |
|------|-------|
| `STRING` | Text box |
| `INTEGER` | Whole number |
| `FLOAT` | Decimal number |
| `DATE` | Date picker |
| `BOOLEAN` | Checkbox |
| `VOCABULARY` | Dropdown backed by a [vocabulary](vocabulary.md) |

Admins define custom fields under **Settings → Metadata** (`/api/metadata`
CRUD: `GET`, `PUT` to create, `POST /api/metadata/{id}` to update,
`DELETE /api/metadata/{id}`). On a document, values are set by pairing each
field's `metadata_id` with its `metadata_value`. See
[vocabulary](vocabulary.md#worked-example--a-document-type-dropdown) for a worked
dropdown example.

## Trash / recycle bin

Deleting a document is a **soft delete** — it moves to the trash, not oblivion, so
an accidental delete is always recoverable until it is purged.

<!-- screenshot: the trash view listing deleted documents with restore and permanent-delete actions -->

| Action | Request |
|--------|---------|
| Delete a document (to trash) | `DELETE /api/document/{id}` |
| List trashed documents | `GET /api/document/trash` |
| Restore from trash | `POST /api/document/{id}/restore` |
| Permanently delete one document | `DELETE /api/document/{id}/permanent` |
| Empty the whole trash | `DELETE /api/document/trash` |

Trashed documents are also **auto-purged** after a retention window. Set
`DOCS_TRASH_RETENTION_DAYS` (default `30`) to control it; `0` disables auto-purge
so nothing is ever purged automatically. A background service checks roughly every
hour and purges anything older than the window.

## See also

- [Tags & filtering](tags-and-filtering.md) — organizing and finding documents
- [Vocabulary](vocabulary.md) — dropdown-backed metadata fields
- [Sharing & permissions](sharing-and-permissions.md) — who can read/write a document
- [Workflows](workflows.md) — approval routes on a document
