# Netboot Artifact Mailroom

Mailroom stores short-lived netboot artifacts under one rendezvous session id.
The session id identifies a boot/write attempt; the artifact label names the
payload inside that session. Publication is anonymous and rate-limited; the high-entropy
room identifier is the temporary bearer capability used for retrieval.

```text
PUT /netboot/v1/rendezvous/<rendezvous-id>/<artifact-label>
GET /netboot/v1/rendezvous/<rendezvous-id>/<artifact-label>
```

Mailroom also exposes compact aliases for bootloaders and installers with tight
URL budgets:

```text
POST https://mailroom.metalstrapper.net/compact/v1/artifacts
GET http://wri7e.net/<artifact-id>
```

Compact publish returns a 7-character Base62 artifact id and bearer read URL.
Use the canonical `/netboot/v1/rendezvous/<id>/<label>` API for ordinary
netboot artifacts and compact artifacts only when URL length matters, such as
a Debian preseed `url=` kernel argument.

Storage keys use the `netboot` namespace:

```text
netboot:<rendezvous-id>:<artifact-label>
```

`<rendezvous-id>` is a 43-character base64url token matching
`[A-Za-z0-9_-]{43}`.

`<artifact-label>` is a short safe name matching
`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`.

MetalStrapper publishes these canonical artifact labels for a netboot session:

```text
boot
answer
firstboot
```

Example:

```text
PUT https://mailroom.example/netboot/v1/rendezvous/abc...xyz/answer
GET https://mailroom.example/netboot/v1/rendezvous/abc...xyz/answer
```

Current behavior:

- In `MAILROOM_MODE=public`, PUT requires HTTPS.
- Accepted PUT content types: `text/plain`, `application/x-ipxe`, or
  `application/octet-stream`.
- Maximum payload size: 16 KiB.
- GET response content type: `text/plain; charset=utf-8`.
- PUT is anonymous and subject to per-source rate limiting and the global daily
  capacity ceiling.
- GET requires only the rendezvous URL; there is no separate proof header.
- TTL: 3600 seconds from successful PUT.
- A duplicate live artifact key returns `409 Conflict`.
- Missing or malformed keys return `404 Not Found`.
- Burst-rate PUT/GET responses are `429 Too Many Requests`; daily quota
  exhaustion returns `503 Daily Capacity Exhausted`.
- Responses use `Cache-Control: no-store`.
- The public MetalStrapper deployment is for demonstration and testing only,
  has no SLA, and should not be used as durable recovery storage.
