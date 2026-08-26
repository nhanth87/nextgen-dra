/* Elisa Nextgen DRA — shared hub helpers (gmlc pattern) */
async function jget(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(url + ' -> ' + r.status);
  return r.json();
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, c =>
    ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
}
function pill(ok, okText, badText) {
  return '<span class="pill ' + (ok ? 'ok' : 'bad') + '">' + (ok ? okText : badText) + '</span>';
}
function kv(k, v) {
  return '<div class="kv"><span class="muted">' + k + '</span><b>' +
         (v === null || v === undefined || v === '' ? '–' : v) + '</b></div>';
}
async function refreshLivePill() {
  try {
    const d = await jget('/api/peers');
    const el = document.getElementById('live-pill');
    if (!el) return;
    el.className = 'pill ' + (d.live ? 'ok' : 'bad');
    el.textContent = d.live ? 'LIVE' : 'DOWN';
  } catch (e) { /* page may not have the pill */ }
}
function markActiveNav() {
  const here = location.pathname.split('/').pop() || 'index.html';
  document.querySelectorAll('nav.hub a').forEach(a => {
    if (a.getAttribute('href').endsWith(here)) a.classList.add('active');
  });
}
setInterval(refreshLivePill, 5000);
document.addEventListener('DOMContentLoaded', () => { refreshLivePill(); markActiveNav(); });
