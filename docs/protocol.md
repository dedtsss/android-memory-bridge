# Memory Gateway protocol (MVP, provisional)

Memory Bridge is a client, not a memory engine. The following HTTP/JSON contract is the only required backend integration. An implementation may be backed by any memory product, as long as it presents this contract.

## Transport

- Base URL is configured by the user.
- Production clients require HTTPS.
- Every request sends `Authorization: Bearer <token>`, `Accept: application/json`, and a generated opaque `X-Request-Id` UUID.
- Requests time out after 10 seconds to connect and 20 seconds to read.
- The app does not log a token, request body, selected text, response body, or URL.
- Responses larger than 60,000 characters are rejected; input above 20,000 characters is rejected before transport.

The server should derive namespace, user identity, and permissions from the bearer token rather than requiring those fields from Android.

## `GET /v1/health`

Used by the settings connection check. Any 2xx response is healthy; no response body is required.

## `POST /v1/memory/save`

```json
{
  "text": "Exact selected text",
  "note": "Optional explanation",
  "source": {
    "platform": "android",
    "action": "process_text",
    "source_app": "com.openai.chatgpt"
  }
}
```

`source_app` is optional and only supplied when Android exposes a caller package. For Shares, `action` is `share`.

```json
{
  "status": "saved",
  "memory_id": "string-or-null",
  "operation_id": "string-or-null",
  "title": "string-or-null",
  "project": "string-or-null"
}
```

`status` must equal `saved`. `operation_id` allows the client to offer Undo; absence means no Undo control is shown.

## `POST /v1/memory/recall`

```json
{
  "query": "What did we decide about the secrets panel?",
  "max_tokens": 1200,
  "source": {
    "platform": "android",
    "action": "process_text"
  }
}
```

```json
{
  "context": "Ready-to-use compact context-pack",
  "project": "string-or-null",
  "sources": [],
  "truncated": false
}
```

`context` is required and is returned verbatim to the user (inside the documented Process Text wrapper). The client does no embedding, classification, normalization, or local persistence.

## `POST /v1/memory/undo`

```json
{ "operation_id": "string" }
```

Any 2xx response means the operation was undone. The endpoint should be idempotent if feasible.

## Errors

The app maps 401, 403, 404, 429, and 5xx to clear user-facing messages without exposing server bodies. Other non-2xx codes are generic request failures. Offline and timeout are separate errors. Backends should return standard status codes and should not echo bearer tokens in errors.
