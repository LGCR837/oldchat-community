# MetroChat WebSocket Protocol (Draft)

## Connection
- URL: `GET /v1/ws`
- Auth: `Authorization: Bearer <access_token>` or query `?token=<access_token>`
- Heartbeat: server sends WebSocket Ping every ~54s; client should respond with Pong.

## Envelope
All server-sent events follow a common JSON envelope:

```
{
  "type": "event_type",
  "data": { ... }
}
```

## Events

### direct_message
```
{
  "type": "direct_message",
  "data": {
    "id": "...",
    "thread_id": "...",
    "from_uid": "USR-XXXX",
    "body": "text",
    "created_at": 1700000000
  }
}
```

### group_message
```
{
  "type": "group_message",
  "data": {
    "id": "...",
    "group_id": "GRP-XXXX",
    "from_uid": "USR-XXXX",
    "body": "text",
    "msg_type": "text",
    "media_url": "",
    "thumb_url": "",
    "duration_ms": 0,
    "created_at": 1700000000
  }
}
```

### system_notification
```
{
  "type": "system_notification",
  "data": {
    "id": "...",
    "title": "...",
    "body": "...",
    "important": false,
    "created_at": 1700000000000
  }
}
```

## Notes
- `created_at` is a Unix timestamp (seconds) for chat events.
- `system_notification.created_at` uses Unix milliseconds to match `/v1/notifications`.
- Client should fallback to HTTP pull (`/v1/direct/messages`, `/v1/groups/messages`) when offline.
