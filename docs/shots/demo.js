// Made-up data for the README pictures; make.sh adds it to a copy of the client. The view is picked by
// the page's hash: #thread, #messages, #recents, #contacts, #call or #ringing, plus "-dark" for dark mode.
document.head.append(Object.assign(document.createElement('style'), { textContent: '*{animation:none!important;transition:none!important}' }));
const [view, theme] = location.hash.slice(1).split('-');
if (theme === 'dark') document.head.append(Object.assign(document.createElement('style'), { textContent:
  ':root{--bg:#0c1220;--surface:#161f36;--ink:#eef1f6;--soft:#9aa5bd;--rule:#27314d;--gold-soft:#3a3214}' }));
// In a phone frame, leave room for its camera cutout and rounded corners, as a real phone does.
if (window.frameElement?.closest('.phone')) document.head.append(Object.assign(document.createElement('style'), { textContent:
  ':root{--safe-t:46px;--safe-b:22px} body{background:var(--surface)}' }));
$('setup').hidden = true; $('app').hidden = false; status('Connected', 'sim.example.com');
ws = { send() {}, readyState: 1, close() {} };
const now = Math.floor(Date.now() / 1000);
contactList = [
  ['Ada Lovelace', '+15550100123'], ['Alan Turing', '+15550100789'], ['Barbara Liskov', '+15550100321'],
  ['Edsger Dijkstra', '+15550100654'], ['Grace Hopper', '+15550100456'], ['Katherine Johnson', '+15550100987'],
  ['Margaret Hamilton', '+15550100111'], ['Radia Perlman', '+15550100222'],
].map(([name, number]) => ({ name, number }));
people = new Map(contactList.map((c) => [digits(c.number), c.name]));
threads = [
  { id: 'm9', thread: 't1', number: '+15550100123', text: 'Running ten minutes late, start without me', at: now - 300, out: false, read: false },
  { id: 'm8', thread: 't2', number: 'BANK-ALERT', text: 'Your statement for October is ready to view', at: now - 5400, out: false, read: true },
  { id: 'm7', thread: 't3', number: '+15550100456', text: 'See you Friday at the workshop', at: now - 90000, out: true, read: true },
  { id: 'm6', thread: 't4', number: '+15550100789', text: 'Thanks, the parcel arrived this morning', at: now - 200000, out: false, read: true },
  { id: 'm5', thread: 't5', number: '+15550100111', text: 'The launch went fine. Notes attached to the doc', at: now - 300000, out: false, read: true },
  { id: 'm4', thread: 't6', number: '+15550100321', text: 'Can we move it to Tuesday?', at: now - 420000, out: true, read: true },
];
recents = [
  { number: '+15550100123', direction: 'in', missed: true, at: now - 120, duration: 0 },
  { number: '+15550100456', direction: 'out', at: now - 4000, duration: 134 },
  { number: '+15550100789', direction: 'in', at: now - 95000, duration: 61 },
  { number: '+15550100999', direction: 'in', rejected: true, at: now - 180000, duration: 0 },
  { number: '+15550100111', direction: 'out', at: now - 260000, duration: 912 },
  { number: '+15550100222', direction: 'in', at: now - 350000, duration: 245 },
];
store.set(SEEN, '0');
drawThreads(); drawCalls(); drawContacts();
if (view === 'thread') {
  open = { thread: 't1', number: '+15550100123' };
  $('view-messages').classList.add('reading'); $('app').classList.add('reading');
  $('thread-head').hidden = false; $('compose').hidden = false;
  $('thread-title').replaceChildren('Ada Lovelace', el('small', '', '+15550100123'));
  $('messages').replaceChildren(...bubbles([
    { text: 'Are you joining the planning call at 4?', at: now - 1500, out: true },
    { text: 'Yes, give me a moment to find my notes', at: now - 1100, out: false },
    { text: 'No rush. I’ll share the agenda when we start.', at: now - 900, out: true },
    { text: 'Running ten minutes late, start without me', at: now - 300, out: false },
  ]));
  drawThreads();
} else if (view === 'recents' || view === 'contacts') {
  show(view); store.set(SEEN, '0'); drawCalls();
} else if (view === 'call') {
  audio = {}; outputs = [{ deviceId: 'a', label: 'iPhone' }, { deviceId: 'b', label: 'Speaker' }];
  onCall({ event: 'call', id: 'c1', state: 'active', direction: 'in', number: '+15550100123', name: 'Ada Lovelace' });
  call.since = Date.now() - 154000; drawCall(true);
  $('voice').style.setProperty('--level', '0.55');
} else if (view === 'ringing') {
  ring = () => {};
  onCall({ event: 'call', id: 'c2', state: 'ringing', direction: 'in', number: '+15550100789', name: 'Alan Turing' });
}
