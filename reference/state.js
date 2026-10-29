/* ================= state ================= */
const state = { qbook: '', books: [], tab: 'all', autoShelf: null, shelf: null, sort: prefs.sort || 'manual', query: '', coverUrls: new Map() };
const root = document.documentElement;

function applyUi() {
  root.setAttribute('data-ui', prefs.ui);
  root.setAttribute('data-motion', 'system');
}

