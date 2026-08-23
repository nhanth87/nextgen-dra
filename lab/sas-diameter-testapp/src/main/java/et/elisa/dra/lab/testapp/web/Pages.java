package et.elisa.dra.lab.testapp.web;

final class Pages {

    private Pages() {
    }

    static String index() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>HSS Simulator — Nextgen DRA Security Lab</title>
                <style>
                  :root { --green:#078930; --yellow:#FCDD09; --red:#DA121A;
                          --bg:#101418; --panel:#1a2026; --line:#2a333c; --fg:#e8edf2; }
                  body { background:var(--bg); color:var(--fg); font:14px/1.45 system-ui,sans-serif;
                         margin:0; padding:24px; }
                  h1 { font-size:18px; margin:0 0 4px; }
                  h1 .flag { border-left:6px solid var(--green); border-right:6px solid var(--red);
                             padding:0 8px; background:linear-gradient(90deg,var(--green),var(--yellow),var(--red));
                             -webkit-background-clip:text; background-clip:text; color:transparent; }
                  .sub { color:#9fb0bf; margin:0 0 20px; font-size:12px; }
                  .grid { display:grid; grid-template-columns:380px 1fr; gap:16px; align-items:start; }
                  .panel { background:var(--panel); border:1px solid var(--line); border-radius:10px;
                           padding:14px 16px; }
                  .panel h2 { font-size:13px; text-transform:uppercase; letter-spacing:.08em;
                              color:var(--yellow); margin:0 0 10px; }
                  table { width:100%; border-collapse:collapse; font-size:12.5px; }
                  th, td { text-align:left; padding:5px 8px; border-bottom:1px solid var(--line);
                           vertical-align:top; }
                  th { color:#9fb0bf; font-weight:600; position:sticky; top:0; background:var(--panel); }
                  td.cmd { font-weight:700; }
                  tr.req td.cmd { color:#7fd1a8; }
                  tr.ans td.cmd { color:#f2e27a; }
                  .rc-ok { color:#7fd1a8; } .rc-bad { color:#ff7b72; }
                  .scroll { max-height:60vh; overflow:auto; }
                  label { display:block; margin:8px 0 2px; color:#9fb0bf; font-size:12px; }
                  input, select { width:100%; box-sizing:border-box; background:#0d1114; color:var(--fg);
                                  border:1px solid var(--line); border-radius:6px; padding:6px 8px; }
                  button { margin-top:10px; width:100%; padding:8px 10px; border:0; border-radius:6px;
                           background:var(--green); color:#fff; font-weight:600; cursor:pointer; }
                  button.warn { background:var(--red); }
                  button:hover { filter:brightness(1.1); }
                  .kv { display:grid; grid-template-columns:150px 1fr; row-gap:3px; font-size:12.5px;
                        margin-bottom:10px; }
                  .kv b { color:#9fb0bf; font-weight:500; }
                  #health { font-size:12px; margin-bottom:4px; }
                  #health.ok { color:#7fd1a8; } #health.bad { color:#ff7b72; }
                  #metrics { font-size:11.5px; color:#9fb0bf; margin-bottom:12px; white-space:pre-wrap; }
                  pre.session { color:#6d7d8b; font-size:11px; max-width:220px; overflow:hidden;
                                text-overflow:ellipsis; white-space:nowrap; margin:0; }
                </style>
                </head>
                <body>
                <h1><span class="flag">HSS / 3GPP AAA Simulator — DRA Lab</span></h1>
                <p class="sub">Elisa Nextgen DRA security lab — iFinder agents → DRA → this HSS sim
                   (S6a ULR/AIR/IDR + SWx MAR/SAR/PPR + Gx CCR binding)</p>
                <div class="grid">
                  <div>
                    <div class="panel" id="subscriber-panel">
                      <h2>Subscriber state</h2>
                      <div id="health">checking…</div>
                      <div id="metrics"></div>
                      <div id="subscriber"></div>
                      <label>IMSI or MSISDN</label>
                      <input id="identity" value="">
                      <label>attached</label>
                      <select id="attached"><option value="true">true</option><option value="false">false</option></select>
                      <label>barred</label>
                      <select id="barred"><option value="false">false</option><option value="true">true</option></select>
                      <label>authVectorsAvailable (0 = fail-closed)</label>
                      <input id="vectors" type="number" min="0" max="10" value="1">
                      <button onclick="updateSubscriber()">Update subscriber (create-or-update)</button>
                      <button class="warn" onclick="resetAll()">Reset buffer + defaults</button>
                    </div>
                    <div class="panel" id="binding-panel" style="margin-top:16px">
                      <h2>Gx IP bindings (PCRF)</h2>
                      <div id="bindings"></div>
                      <label>IP</label>
                      <input id="bind-ip" placeholder="10.20.30.40">
                      <label>MSISDN</label>
                      <input id="bind-msisdn" placeholder="+251911111111">
                      <label>IMSI</label>
                      <input id="bind-imsi" placeholder="655010000000001">
                      <button onclick="upsertBinding()">Upsert binding</button>
                      <button class="warn" onclick="deleteBinding()">Delete binding</button>
                    </div>
                  </div>
                  <div class="panel scroll">
                    <h2>Diameter messages (last 500)</h2>
                    <table>
                      <thead><tr><th>time</th><th></th><th>command</th><th>result</th><th>details</th><th>session</th></tr></thead>
                      <tbody id="rows"></tbody>
                    </table>
                  </div>
                </div>
                <script>
                let selectedIdentity = "";
                function esc(s){ return String(s ?? "").replace(/[&<>"]/g, c =>
                  ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
                async function refresh() {
                  try {
                    const [health, subs, msgs, binds, metrics] = await Promise.all([
                      fetch('/api/health').then(r => r.json()),
                      fetch('/api/subscriber').then(r => r.json()),
                      fetch('/api/messages').then(r => r.json()),
                      fetch('/api/binding').then(r => r.json()),
                      fetch('/api/metrics').then(r => r.json())
                    ]);
                    const h = document.getElementById('health');
                    const up = health.status === 'up' && health.diameterListening === true;
                    h.textContent = 'status: ' + health.status + ' · diameter: '
                      + (health.diameterListening ? 'listening' : 'DOWN')
                      + ' · lastMessageAge: ' + health.lastMessageAgeMillis + 'ms';
                    h.className = up ? 'ok' : 'bad';
                    const m = document.getElementById('metrics');
                    if (m && metrics) {
                      m.textContent = 'req=' + metrics.requestsTotal + ' ans=' + metrics.answersTotal
                        + ' err=' + metrics.errorsTotal + ' · threads=' + metrics.threadCount
                        + ' · deadlocked=' + metrics.deadlockCount
                        + ' · heap=' + Math.round(metrics.heapUsed/1048576) + '/'
                        + Math.round(metrics.heapMax/1048576) + 'MB';
                    }
                    renderSubscribers(subs.subscribers || []);
                    renderMessages(msgs);
                    renderBindings(binds.bindings || []);
                  } catch (e) {}
                }
                function renderSubscribers(subs) {
                  const el = document.getElementById('subscriber');
                  if (!subs.length) { el.innerHTML = '<i>none provisioned</i>'; return; }
                  if (!selectedIdentity && subs[0]) {
                    selectedIdentity = subs[0].imsi;
                    document.getElementById('identity').value = subs[0].imsi;
                  }
                  el.innerHTML = subs.map(s => `
                    <div class="kv">
                      <b>IMSI</b><span>${esc(s.imsi)}</span>
                      <b>MSISDN</b><span>${esc(s.msisdn)}</span>
                      <b>attached</b><span>${s.attached}</span>
                      <b>barred</b><span style="${s.barred ? 'color:var(--red)' : ''}">${s.barred}</span>
                      <b>vectors</b><span style="${s.authVectorsAvailable ? '' : 'color:var(--red)'}">${s.authVectorsAvailable}</span>
                      <b>RAT</b><span>${esc(s.subscribedRat)}</span>
                      <b>lastEapAuth</b><span>${esc(s.lastEapAuthSuccess || 'never')}</span>
                    </div>`).join('<hr style="border-color:var(--line)">');
                }
                function renderMessages(msgs) {
                  document.getElementById('rows').innerHTML = msgs.map(m => `
                    <tr class="${esc(m.direction)}">
                      <td>${esc(m.time.slice(11, 23))}</td>
                      <td>${m.direction === 'req' ? '→' : '←'}</td>
                      <td class="cmd">${esc(m.command)}</td>
                      <td class="${String(m.result).startsWith('2001') ? 'rc-ok' : 'rc-bad'}">${esc(m.result)}</td>
                      <td>${esc(m.details)}</td>
                      <td><pre class="session">${esc(m.session)}</pre></td>
                    </tr>`).join('');
                }
                async function updateSubscriber() {
                  await fetch('/api/subscriber', { method:'POST',
                    headers:{'Content-Type':'application/json'},
                    body: JSON.stringify({
                      identity: document.getElementById('identity').value.trim(),
                      attached: document.getElementById('attached').value === 'true',
                      barred: document.getElementById('barred').value === 'true',
                      authVectorsAvailable: Number(document.getElementById('vectors').value)
                    })});
                  refresh();
                }
                function renderBindings(binds) {
                  const el = document.getElementById('bindings');
                  if (!binds.length) { el.innerHTML = '<i>no bindings</i>'; return; }
                  el.innerHTML = binds.map(b => `
                    <div class="kv">
                      <b>${esc(b.ip)}</b><span>${esc(b.msisdn)} / ${esc(b.imsi || '—')}</span>
                    </div>`).join('');
                }
                async function upsertBinding() {
                  await fetch('/api/binding', { method:'POST',
                    headers:{'Content-Type':'application/json'},
                    body: JSON.stringify({
                      ip: document.getElementById('bind-ip').value.trim(),
                      msisdn: document.getElementById('bind-msisdn').value.trim(),
                      imsi: document.getElementById('bind-imsi').value.trim()
                    })});
                  refresh();
                }
                async function deleteBinding() {
                  const ip = document.getElementById('bind-ip').value.trim();
                  await fetch('/api/binding/' + encodeURIComponent(ip), { method:'DELETE' });
                  refresh();
                }
                async function resetAll() {
                  await fetch('/api/reset', { method:'POST' });
                  refresh();
                }
                setInterval(refresh, 2000);
                refresh();
                </script>
                </body>
                </html>
                """;
    }
}
