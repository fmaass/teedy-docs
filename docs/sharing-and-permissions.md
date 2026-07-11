# Sharing & permissions

Teedy controls who can see and edit each document through access-control lists
(ACLs). This page covers ACLs, groups, guest access, public share links, and
comments.

## ACLs (access-control lists)

Every document (and tag) carries a list of ACL entries. Each entry grants one
**permission** to one **target**:

| Permission | Grants |
|------------|--------|
| `READ` | View the document, its files, and comments |
| `WRITE` | Edit the document, upload files, manage its ACLs |

A target is one of three types:

| Target type | Meaning |
|-------------|---------|
| `USER` | A specific user |
| `GROUP` | A [group](#groups) of users |
| `SHARE` | A public [share link](#public-share-links) |

Add and remove ACL entries via the API:

| Action | Request |
|--------|---------|
| Grant an ACL | `PUT /api/acl` with form params `source` (document/tag ID), `perm`, `target`, `type` |
| Revoke an ACL | `DELETE /api/acl/{sourceId}/{perm}/{targetId}` |

### Inherited permissions from tags

ACLs set on a **tag** flow down to the documents that carry that tag. On
`GET /api/document/{id}` these appear as `inherited_acls` (with the source tag's ID,
name, and color) separately from the document's own `acls`, so you can see which
permissions come from a tag versus from the document itself.

## Groups

A group bundles users so you can grant permissions (or assign
[workflow steps](workflows.md)) to many people at once. Groups are **hierarchical**:
each group may have a parent group.

| Action | Request |
|--------|---------|
| List groups | `GET /api/group/list` |
| Create a group | `PUT /api/group` with form params `name`, `parent` |
| Update a group | `POST /api/group/{id}` with form params `name`, `parent` |
| Delete a group | `DELETE /api/group/{id}` |

`name` is alphanumeric (1–50 characters) and must be unique; `parent` is the
**name** of an existing parent group (omit for a top-level group).

## Guest access

Teedy has a built-in `guest` account for anonymous, read-only access to documents
that have been shared with it. An administrator can enable or disable guest login;
when enabled, visitors can browse without signing in and see whatever the `guest`
user has been granted `READ` on.

## Public share links

A share link exposes a single document to anyone with the URL, without a Teedy
account.

| Action | Request |
|--------|---------|
| Create a share | `PUT /api/share` with form params `id` (document ID), `name` (optional) |
| Delete a share | `DELETE /api/share/{shareId}` |

Creating a share adds a `SHARE`-type ACL granting `READ` on the document and
returns a share ID. The public URL embeds both the document ID and the share ID;
opening it serves the document read-only to an anonymous visitor. Delete the share
to revoke the link.

## Comments

Users with `READ` on a document can leave comments on it.

| Action | Request |
|--------|---------|
| List comments | `GET /api/comment/{id}` (document ID; optional `share` for a shared view) |
| Add a comment | `PUT /api/comment` with form params `id` (document ID), `content` |
| Delete a comment | `DELETE /api/comment/{id}` |

A comment records its author, content, and timestamp. The comment author, or anyone
with `WRITE` on the document, can delete it.

## See also

- [Workflows](workflows.md) — assign approval steps to the groups defined here
- [Documents](documents.md) — the documents these permissions protect
- [Admin guide](admin-guide.md) — managing users and groups
- [Authentication](authentication.md) — how users get accounts in the first place
