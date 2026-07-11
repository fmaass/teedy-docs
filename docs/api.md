# REST API

Teedy exposes a full REST API under `/api/*`. Everything the web UI does is a call
to this API, so anything you can do in the browser you can automate.

## Interactive API docs (coming in v3.4)

> **Version note:** A browsable, interactive API reference at `/apidoc` is planned
> for **v3.4** and is not present in the current release. Until it ships, use the
> endpoint references embedded throughout this documentation (each feature page
> lists its endpoints) and the examples below.

## Authentication

Two mechanisms authenticate an API request:

### Session cookie

A normal browser login sets a session cookie. Scripts can log in via
`POST /api/user/login` (form params `username`, `password`) and reuse the returned
cookie for subsequent calls.

### API key (recommended for scripts)

Create API keys in **Settings → API Keys**. Send the key in the `Authorization`
header:

```
Authorization: Bearer tdapi_<your-key>
```

An API key acts as the creating user and has exactly that user's permissions. The
raw key is shown **only once** at creation — store it securely.

## Request format

Mutations use `application/x-www-form-urlencoded` bodies (form parameters), not
JSON. File uploads use `multipart/form-data` on `PUT /api/file`.

## Key resources

| Resource | Purpose |
|----------|---------|
| `/api/document` | Documents (CRUD, search, trash) |
| `/api/file` | Files attached to documents (upload, versions) |
| `/api/tag` | Tags |
| `/api/user` | Users, login, password |
| `/api/apikey` | API keys |
| `/api/acl` | Access-control entries (sharing/permissions) |
| `/api/group` | Groups |
| `/api/comment` | Comments |
| `/api/metadata` | Custom metadata fields |
| `/api/vocabulary` | Controlled vocabularies |
| `/api/route`, `/api/routemodel` | Workflows (routes and route models) |
| `/api/tagmatchrule` | Auto-tagging rules |
| `/api/webhook` | Webhooks |
| `/api/auditlog` | Audit log |
| `/api/theme` | Custom theme CSS (admin) |
| `/api/oidc` | OIDC login/callback |
| `/api/app` | Application info (version, status) |

## Examples

Log in and reuse the cookie:

```bash
curl -c cookies.txt -X POST https://teedy.example.com/api/user/login \
  -d "username=admin" \
  -d "password=changeme"
```

Create a document with an API key:

```bash
curl -X PUT https://teedy.example.com/api/document \
  -H "Authorization: Bearer tdapi_<your-key>" \
  -d "title=Invoice 2026-001" \
  -d "language=eng"
```

Upload a file to that document:

```bash
curl -X PUT https://teedy.example.com/api/file \
  -H "Authorization: Bearer tdapi_<your-key>" \
  -F "id=<document-id>" \
  -F "file=@invoice.pdf"
```

Check the running version:

```bash
curl https://teedy.example.com/api/app
```

## See also

- [Workflows](workflows.md#api-reference) — route and route-model endpoints
- [Vocabulary](vocabulary.md#api-reference) — vocabulary endpoints
- [Admin guide](admin-guide.md) — webhooks, users, audit log
- [Authentication](authentication.md) — session vs API-key auth
