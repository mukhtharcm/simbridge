# simbridge API (draft 0)

Status: **draft**. Only the audio spike exists; nothing below is implemented yet.
The spike is built toward this page, and the page changes when the spike teaches us something.

## Transport

One WebSocket: `ws://<bind-address>:<port>/v0?token=<token>`.

- **Bind address** is one interface, never `0.0.0.0`. Default `127.0.0.1`; set it to the Tailscale address to
  reach the bridge from other devices.
- **Token** is required on every connection. Anything that reaches this API can dial and text from the SIM.
- **Text frames** carry JSON: events from the bridge, commands to it.
- **Binary frames** carry call audio, both directions: 16 kHz, mono, signed 16-bit little-endian,
  20 ms per frame (640 bytes). Audio only flows while a call is `active`.

One client at a time owns call audio. Others may connect for events and SMS.

## Events (bridge → client)

```json
{"event": "call", "id": "c1", "state": "ringing", "direction": "in", "number": "+911234567890"}
{"event": "call", "id": "c1", "state": "active"}
{"event": "call", "id": "c1", "state": "ended", "reason": "remote"}
{"event": "sms", "id": "m42", "thread": "t7", "from": "+911234567890", "text": "hi", "at": 1795000000}
{"event": "sms_status", "id": "m43", "status": "sent"}
```

`state` is one of `ringing`, `dialing`, `active`, `ended`. `active` is sent once call audio can flow, which is
about two seconds after the call is answered; audio sent earlier is not played.
`reason` is one of `local`, `remote`, `rejected`, `missed`, `busy`, `error`.
`status` is one of `sent`, `delivered`, `failed`.

## Commands (client → bridge)

Every command may carry a `ref`; the reply repeats it.

```json
{"cmd": "dial", "number": "+911234567890", "ref": 1}
{"cmd": "answer", "id": "c1"}
{"cmd": "hangup", "id": "c1"}
{"cmd": "dtmf", "id": "c1", "digits": "12#"}
{"cmd": "sms_send", "to": "+911234567890", "text": "hello", "ref": 2}
{"cmd": "sms_threads", "ref": 3}
{"cmd": "sms_messages", "thread": "t7", "before": "m42", "limit": 50, "ref": 4}
```

Replies:

```json
{"ref": 1, "ok": true}
{"ref": 2, "ok": false, "error": "bad_number"}
```

`dial` and `sms_send` accept `^\+?[0-9]{3,20}$` only. Codes with `*` or `#` (call forwarding, USSD) are refused.

## Not in v0

Conference and hold, MMS, multiple SIMs, video.
