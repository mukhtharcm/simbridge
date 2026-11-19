#!/usr/bin/env node
// A test client for the simbridge API. Needs Node 22 or newer (built-in WebSocket).
//
//   node scripts/client.mjs [--answer] [--dial NUMBER] [--play FILE.wav] [--hangup-after SECONDS]
//
// Prints every event, records the caller to recordings/<time>.wav, and with --play sends a
// 16 kHz mono 16-bit WAV into the call once it is active. Reads BIND, PORT and TOKEN from secrets/api.env.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const value = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);

const env = Object.fromEntries(
  readFileSync(new URL('../secrets/api.env', import.meta.url), 'utf8')
    .split('\n').filter((l) => l.includes('=')).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]),
);
const FRAME_BYTES = 640; // 20 ms at 16 kHz, 16-bit mono
const wav = value('--play') ? readFileSync(value('--play')) : null;
const toPlay = wav && wav.subarray(wav.indexOf('data') + 8); // past the WAV header, whatever its length
const heard = [];
let callId = null;
let sender = null;

const ws = new WebSocket(`ws://${env.BIND}:${env.PORT}/v0?token=${env.TOKEN}`);
ws.binaryType = 'arraybuffer';
const send = (cmd) => { console.log('>', JSON.stringify(cmd)); ws.send(JSON.stringify(cmd)); };

ws.onopen = () => {
  console.log('connected');
  if (value('--dial')) send({ cmd: 'dial', number: value('--dial'), ref: 1 });
};
ws.onerror = (e) => console.log('error:', e.message ?? 'could not connect (wrong token, or the API is not up)');
ws.onclose = (e) => { console.log('closed', e.code); save(); process.exit(0); };
ws.onmessage = ({ data }) => {
  if (typeof data !== 'string') { heard.push(Buffer.from(data)); return; }
  console.log('<', data);
  const msg = JSON.parse(data);
  if (msg.event !== 'call') return;
  callId = msg.id;
  if (msg.state === 'ringing' && flag('--answer')) send({ cmd: 'answer', id: callId });
  if (msg.state === 'active') {
    if (toPlay) startSending();
    if (value('--hangup-after')) setTimeout(() => send({ cmd: 'hangup', id: callId }), value('--hangup-after') * 1000);
  }
  if (msg.state === 'ended') { clearInterval(sender); save(); if (!flag('--stay')) ws.close(); }
};

// Sends by the clock, not by the timer, so a late timer catches up instead of drifting.
function startSending() {
  const started = Date.now();
  let sent = 0;
  sender = setInterval(() => {
    const due = Math.floor((Date.now() - started) / 20);
    for (; sent < due && (sent + 1) * FRAME_BYTES <= toPlay.length; sent++) {
      ws.send(toPlay.subarray(sent * FRAME_BYTES, (sent + 1) * FRAME_BYTES));
    }
  }, 5);
}

function save() {
  if (!heard.length) return;
  const pcm = Buffer.concat(heard.splice(0));
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVEfmt ', 8);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(16000, 24); h.writeUInt32LE(32000, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  mkdirSync(new URL('../recordings/', import.meta.url), { recursive: true });
  const file = new URL(`../recordings/heard-${Date.now()}.wav`, import.meta.url);
  writeFileSync(file, Buffer.concat([h, pcm]));
  console.log(`saved ${(pcm.length / 32000).toFixed(1)} s of the caller to ${file.pathname}`);
}

process.on('SIGINT', () => { if (callId) send({ cmd: 'hangup', id: callId }); setTimeout(() => ws.close(), 300); });
