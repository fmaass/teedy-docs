# Vocabularies (controlled value lists)

A vocabulary is an admin-managed list of allowed values — for example a fixed set
of document types. Bind a vocabulary to a custom metadata field and that field
becomes a dropdown instead of a free-text box, so everyone tags documents with the
same spelling and set of values instead of inventing their own.

## Concepts

A vocabulary is identified by a **name** (its namespace, e.g. `doc-types`) and
holds an ordered list of **entries**, each a single **value** (e.g. `Invoice`)
with an order. Vocabularies are consumed by custom metadata fields of type
`VOCABULARY`, which Teedy renders as a Select (dropdown) on the document form.

The metadata field types are:

| Type | Rendered as |
|------|-------------|
| `STRING` | Text box |
| `INTEGER` | Whole-number input |
| `FLOAT` | Decimal input |
| `DATE` | Date picker |
| `BOOLEAN` | Checkbox |
| `VOCABULARY` | Dropdown backed by a vocabulary |

## Managing vocabularies

Vocabularies are edited in **Settings → Vocabulary** (administrator only). Add a
vocabulary name, then add ordered entries under it.

<!-- screenshot: SettingsVocabulary showing the doc-types vocabulary with the Invoice / Contract / Report entries -->

### API reference

The vocabulary API uses `application/x-www-form-urlencoded` bodies:

| Action | Request |
|--------|---------|
| List entries | `GET /api/vocabulary?name=<name>` |
| Add an entry | `PUT /api/vocabulary` with form params `name`, `value`, `order` |
| Update an entry | `POST /api/vocabulary/{id}` |
| Delete an entry | `DELETE /api/vocabulary/{id}` |
| Check usage before rename/delete | `GET /api/vocabulary/{id}/usage` |

### Usage check before rename or delete

Before you rename or delete an entry, check how many documents already reference
it. The usage endpoint returns that count, and the UI warns you with the number of
documents affected so you do not silently orphan metadata on existing documents.

## Worked example — a "Document Type" dropdown

1. In **Settings → Vocabulary**, create a vocabulary named `doc-types`.
2. Add three ordered entries: `Invoice`, `Contract`, `Report`.
3. In **Settings → Metadata**, create a custom metadata field named
   `Document Type` of type `VOCABULARY`, bound to the `doc-types` vocabulary.
4. On any document, the `Document Type` field now shows a dropdown with
   `Invoice` / `Contract` / `Report` instead of a free-text box.

## See also

- [Documents](documents.md#metadata) — attaching custom metadata to documents
- [Workflows](workflows.md) — approval routes that can be driven off document type
