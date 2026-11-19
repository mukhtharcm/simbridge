# simbridge API (draft 0)

Status: **draft, implemented**. Everything below works on the phone in the README's table. Names may change
before a v1.

## Transport

One WebSocket: `ws://<bind-address>:<port>/v0?token=<token>`.

- **Bind address** is one interface, never `0.0.0.0`. Default `127.0.0.1`; set it to the Tailscale address to
  reach the bridge from other devices.
- **Token** is required on every connection. Anything that reaches this API can dial and text from the SIM.
- **Text frames** carry JSON: events from the bridge, commands to it.
- **Binary frames** carry call audio, both directions: 16 kHz, mono, signed 16-bit little-endian,
  20 ms per frame (640 bytes). Audio only flows while a call is `active`.

One client at a time owns call audio. Others may connect for events and SMS. A client that connects during a
call is sent that call's latest `call` event straight away.

If the last client disconnects during a call, the bridge hangs up.

## Events (bridge → client)

```json
{"event": "call", "id": "c1", "state": "ringing", "direction": "in", "number": "+15550100123", "name": "Ada Lovelace"}
{"event": "call", "id": "c1", "state": "active"}
{"event": "call", "id": "c1", "state": "ended", "reason": "remote"}
{"event": "sms", "id": "m42", "thread": "t7", "number": "+15550100123", "text": "hi", "at": 1795000000, "out": false, "read": false}
{"event": "sms_status", "id": "m43", "ref": 2, "status": "sent"}
```

`state` is one of `ringing`, `dialing`, `active`, `ended`. `active` is sent once call audio can flow, which is
about two seconds after the call is answered; audio sent earlier is not played.
`reason` is one of `local`, `remote`, `rejected`, `missed`, `busy`, `error`. An incoming call that was never
answered or declined ends as `missed`.

`name` appears on calls, messages and call history whenever the phone's contacts have one for the number.
`status` is `sent` or `failed`, and `ref` is the one given to `sms_send`. There are no delivery reports yet.

An `sms` event is sent for every message that enters the phone's message store, received or sent (`out`),
including ones sent from the phone's own messaging app.

simbridge must be the phone's default SMS app. It stores incoming and sent messages in Android's own message
store, so another SMS app that takes over later finds the full history. A message being sent appears at once
with `out: true`; `failed: true` is set if the network refuses it. `number` is the other party; `at` is in seconds.

## Commands (client → bridge)

Every command may carry a `ref`; the reply repeats it.

```json
{"cmd": "dial", "number": "+15550100123", "ref": 1}
{"cmd": "answer", "id": "c1"}
{"cmd": "hangup", "id": "c1"}
{"cmd": "dtmf", "id": "c1", "digits": "12#"}
{"cmd": "sms_send", "to": "+15550100123", "text": "hello", "ref": 2}
{"cmd": "sms_threads", "ref": 3}
{"cmd": "sms_messages", "thread": "t7", "before": "m42", "limit": 50, "ref": 4}
{"cmd": "sms_read", "thread": "t7"}
{"cmd": "calls", "limit": 100}
{"cmd": "contacts"}
{"cmd": "contact_save", "number": "+15550100123", "name": "Ada Lovelace"}
{"cmd": "contacts_import", "contacts": [{"name": "Grace Hopper", "numbers": ["+15550100456"]}]}
{"cmd": "push_key"}
{"cmd": "push_subscribe", "subscription": {"endpoint": "https://fcm.googleapis.com/…", "keys": {"p256dh": "…", "auth": "…"}}}
{"cmd": "push_test"}
```

Replies:

```json
{"ref": 1, "ok": true}
{"ref": 2, "ok": false, "error": "bad_number"}
{"ref": 3, "ok": true, "threads": [{"id": "m42", "thread": "t7", "number": "+15550100123", "text": "hi", "at": 1795000000, "out": false, "read": false}]}
{"ref": 4, "ok": true, "messages": []}
```

`sms_threads` lists conversations newest first, each as its latest message. `sms_messages` lists one
conversation newest first; `before` and `limit` (at most 200) page backwards. `sms_send` replies with the
stored message's `id` as soon as the message is handed to the phone; the outcome follows as an `sms_status`
event. `sms_read` marks a conversation's received messages as read.

**Calls and contacts.** `calls` returns the phone's call history, newest first (`limit` at most 200), as
`{"id", "number", "name"?, "direction", "missed", "rejected", "at", "duration"}` with `duration` in seconds.
`contacts` returns every saved number as `{"name", "number"}`. `contact_save` gives a number a name, renaming
the contact that has it or adding one. `contacts_import` adds contacts whose numbers the phone does not know
yet (at most 5000 per command) and replies with how many it `added`. Contacts are stored in the phone's own
contacts, without an account.

**Push notifications** (Web Push). `push_key` returns the bridge's public key, which a browser needs to
subscribe; `push_subscribe` takes the subscription the browser returns (`PushSubscription.toJSON()`). The
bridge then sends a notification for each incoming call, missed call and incoming text, as
`{"title", "body", "tag", "sticky"}`: `tag` is `call` for calls and `sms-<thread>` for texts, and `sticky` is
true for a ringing call. `push_test` sends one test notification to every subscription and replies with how
many there are (`sent`). Only the push services of Chrome, Edge, Firefox and Safari are accepted.

Errors: `bad_request`, `unknown_command`, `bad_number`, `bad_text`, `bad_digits`, `busy`, `no_such_call`,
`no_permission`, `bad_name`, `too_many`, `bad_endpoint`.

`dial` and `sms_send` accept `^\+?[0-9]{3,20}$` only. Codes with `*` or `#` (call forwarding, USSD) are refused.

## Not in v0

Conference and hold, multiple SIMs, video, delivery reports, deleting messages.

**MMS is not supported, and while simbridge is the default SMS app an incoming MMS is not fetched by anything.**
