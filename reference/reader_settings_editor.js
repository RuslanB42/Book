/* ---------- type settings ---------- */
function openType() {
  const body = h('div', { class: 'type' });
  const set = (key, val) => { const pos = curPos(); prefs[key] = val; savePrefs(); applyReaderPrefs(); relayoutKeep(pos); paint(); };
  const segIcons = (key, items, label) => h('div', { class: 'seg', role: 'group', 'aria-label': label },
    items.map(([val, ic, text]) => h('button', { 'aria-pressed': String(prefs[key] === val), 'aria-label': text, title: text, onclick: () => set(key, val) }, icon(ic), h('span', { class: 'seg-l' }, text))));
  const paint = () => {
    const range = h('input', { type: 'range', id: 'fs', min: 14, max: 30, step: 1, 'aria-label': 'Размер шрифта' });
    range.value = prefs.size;
    range.style.setProperty('--fill', ((prefs.size - 14) / 16 * 100) + '%');
    range.addEventListener('input', () => { range.style.setProperty('--fill', ((range.value - 14) / 16 * 100) + '%'); $('#fs-v').textContent = range.value; });
    range.addEventListener('change', () => set('size', +range.value));
    body.replaceChildren(
      h('div', { class: 'opt' }, h('div', { class: 'opt-h' }, h('span', null, 'Размер шрифта'), h('output', { id: 'fs-v' }, String(prefs.size))),
        h('div', { class: 'size-row' },
          h('button', { class: 'size-btn', 'aria-label': 'Мельче', onclick: () => set('size', clamp(prefs.size - 1, 14, 30)) }, h('span', { style: 'font-size:15px' }, 'А')),
          range,
          h('button', { class: 'size-btn', 'aria-label': 'Крупнее', onclick: () => set('size', clamp(prefs.size + 1, 14, 30)) }, h('span', { style: 'font-size:24px' }, 'А')))),
      h('div', { class: 'opt' }, h('div', { class: 'opt-h' }, h('span', null, 'Шрифт')),
        h('div', { class: 'fonts' }, [['literata', 'Literata'], ['ptserif', 'PT Serif'], ['sans', 'Без засечек']].map(([k, name]) =>
          h('button', { class: 'font-card', 'aria-pressed': String(prefs.font === k), onclick: () => set('font', k) },
            h('span', { class: 'ff', style: `font-family:${FONTS[k]}` }, 'Аа'), h('span', { class: 'fn' }, name))))),
      h('div', { class: 'opt' }, h('div', { class: 'opt-h' }, h('span', null, 'Межстрочный интервал')),
        segIcons('lh', [['tight', 'lh1', 'Плотный'], ['normal', 'lh2', 'Обычный'], ['loose', 'lh3', 'Свободный']], 'Межстрочный интервал')),
      h('div', { class: 'opt' }, h('div', { class: 'opt-h' }, h('span', null, 'Листание')),
        segIcons('mode', [['paged', 'pages', 'Страницами'], ['scroll', 'scroll', 'Лентой']], 'Листание')),
      h('div', { class: 'opt' }, h('div', { class: 'opt-h' }, h('span', null, 'Цвет страницы')),
        h('div', { class: 'swatches' }, Object.entries(READER_THEMES).map(([k, t]) =>
          h('button', { class: 'sw', 'aria-pressed': String(readerTheme() === t), onclick: () => withVT(() => { prefs.rt = k; savePrefs(); applyReaderPrefs(); paint(); }) },
            h('i', { style: `background:${t.bg};color:${t.fg}` }, 'Аа'), t.name)))),
      h('div', { class: 'opt row' }, h('label', { for: 'justify' }, 'Выравнивание по ширине'),
        h('span', { class: 'switch' }, (() => { const i = h('input', { type: 'checkbox', id: 'justify', role: 'switch' }); i.checked = !!prefs.justify; i.addEventListener('change', () => set('justify', i.checked)); return i; })(), h('span'))));
  };
  paint();
  openSheet({ title: 'Текст', body });
}

/* ---------- edit mode ---------- */
function startEditing() {
  if (R.editing) return;
  hideQbar();
  $$('mark.hl', rc()).forEach(unmark);
  const pos = curPos();
  R.editing = true; R.dirty = false;
  layout();
  const c = rc();
  c.querySelectorAll('mark.q').forEach(unmark);
  R.blocks = [...c.querySelectorAll(BLOCK_SEL)];
  c.contentEditable = 'true';
  c.spellcheck = true;
  try { document.execCommand('defaultParagraphSeparator', false, 'p'); } catch (e) { /* old browser */ }
  $('#reader').classList.add('editing');
  setCalm(false);
  const bar = $('#r-edit-bar'); bar.hidden = false; playIn(bar, 'bar-in');
  $('#r-bottom').hidden = true;
  renderTop();
  const keep = R.blocks[pos.block];
  requestAnimationFrame(() => {
    if (keep) {
      scrollToEl(keep);
      const sel = window.getSelection(); const r = document.createRange();
      r.setStart(keep, 0); r.collapse(true); sel.removeAllRanges(); sel.addRange(r);
    }
    c.focus({ preventScroll: true });
  });
}
function cleanHtml() {
  const box = rc().cloneNode(true);
  box.querySelectorAll('mark.hl, mark.q').forEach(unmark);
  for (const p of ['width', 'height', 'column-width', 'column-gap', 'transform', 'transition', 'opacity']) box.style.removeProperty(p);
  for (const img of box.querySelectorAll('img[data-src]')) { img.removeAttribute('src'); img.removeAttribute('decoding'); }
  for (const im of box.querySelectorAll('image[data-src]')) im.removeAttribute('href');
  for (const el of box.querySelectorAll('.dc')) { el.classList.remove('dc'); if (!el.className) el.removeAttribute('class'); }
  for (const a of box.querySelectorAll('a[target]')) { a.removeAttribute('target'); a.removeAttribute('rel'); }
  for (const el of box.querySelectorAll('[style]')) el.removeAttribute('style');
  for (const sp of box.querySelectorAll('span:not([class]):not([id]), font')) { const p = sp.parentNode; while (sp.firstChild) p.insertBefore(sp.firstChild, sp); sp.remove(); }
  for (const d of box.querySelectorAll('div:not([class]):not([id])')) { if (!d.querySelector('p,div,h1,h2,h3,h4,h5,h6,blockquote,table,ul,ol,section')) { const p = document.createElement('p'); while (d.firstChild) p.append(d.firstChild); d.replaceWith(p); } }
  box.normalize();
  return box;
}
function relocateQuotes(newText) {
  for (const q of R.book.quotes.filter((x) => x.ch === R.ch)) {
    const needle = q.rawText || q.text;
    let best = -1, bestD = Infinity, from = 0, pos;
    const norm = newText;
    while ((pos = norm.indexOf(needle, from)) >= 0) { const d = Math.abs(pos - q.start); if (d < bestD) { best = pos; bestD = d; } from = pos + 1; }
    if (best >= 0) { q.start = best; q.end = best + needle.length; } else q.start = -1;
  }
}
async function saveEditing() {
  if (!R.editing) return;
  const ch = R.content.chapters[R.ch];
  const box = cleanHtml();
  const html = box.innerHTML;
  const changed = html !== ch.html;
  const pos = curPos();
  if (changed) {
    const prev = { html: ch.html, len: ch.len, edited: ch.edited };
    const previousBook = structuredClone(R.book);
    ch.html = html; ch.len = box.textContent.length; ch.edited = true;
    try {
      relocateQuotes(box.textContent);
      R.book.modifiedAt = Date.now(); R.book.edits = (R.book.edits || 0) + 1;
      await DB.writeBatch([{ store: 'content', value: R.content }, { store: 'books', value: R.book }]);
    } catch (e) {
      Object.assign(ch, prev); Object.assign(R.book, previousBook);
      toast('Правка не сохранилась: ' + (e && e.name === 'QuotaExceededError' ? 'на устройстве не хватает места' : (e && e.message) || 'ошибка хранилища') + '. Текст остался в режиме правки.', { tone: 'error', timeout: 7000 });
      return;
    }
    R.textCache.delete(R.ch); R.idMap = null; recalcLengths();
  }
  finishEditing(pos, true);
  toast(changed ? 'Правка сохранена' : 'Изменений не было');
}
function finishEditing(pos, rerender) {
  const c = rc();
  R.editing = false; R.dirty = false;
  c.removeAttribute('contenteditable');
  window.getSelection().removeAllRanges();
  $('#reader').classList.remove('editing');
  $('#r-edit-bar').hidden = true;
  $('#r-bottom').hidden = false;
  if (rerender) renderChapter();
  R.blocks = [...c.querySelectorAll(BLOCK_SEL)];
  renderTop(); renderPager();
  relayoutKeep(pos);
}
async function leaveEditing() {
  if (!R.editing) return true;
  if (R.dirty) {
    const ok = await confirmSheet({ title: 'Отменить правки?', text: 'Изменения в этой главе не сохранены.', ok: 'Отменить правки', danger: true, cancel: 'Продолжить правку' });
    if (!ok) { rc().focus({ preventScroll: true }); return false; }
  }
  finishEditing(curPos(), true);
  return true;
}
rc().addEventListener('input', () => { if (R.editing) R.dirty = true; });
rc().addEventListener('paste', (e) => {
  if (!R.editing) return;
  e.preventDefault();
  const text = (e.clipboardData || window.clipboardData).getData('text/plain');
  document.execCommand('insertText', false, text);
});
rc().addEventListener('drop', (e) => { if (R.editing) e.preventDefault(); });
$('#r-edit-bar').addEventListener('mousedown', (e) => e.preventDefault());
$('#r-edit-bar').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-cmd]'); if (!b) return;
  const cmd = b.dataset.cmd;
  if (cmd === 'heading') {
    const v = String(document.queryCommandValue('formatBlock') || '').toLowerCase();
    document.execCommand('formatBlock', false, /^h[1-6]$/.test(v) ? 'p' : 'h3');
  } else document.execCommand(cmd, false, null);
  R.dirty = true;
});
if (window.visualViewport) {
  const vv = window.visualViewport;
  const fit = () => { const bar = $('#r-edit-bar'); const gap = window.innerHeight - vv.height - vv.offsetTop; bar.style.bottom = gap > 40 ? (gap + 10) + 'px' : ''; };
  vv.addEventListener('resize', fit); vv.addEventListener('scroll', fit);
}
window.addEventListener('beforeunload', (e) => { if (R.editing && R.dirty) { e.preventDefault(); e.returnValue = ''; } });

document.addEventListener('keydown', (e) => {
  if ($('#reader').hidden) return;
  const mod = e.metaKey || e.ctrlKey;
  if (R.editing) {
    if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); saveEditing(); }
    else if (e.key === 'Escape' && !sheets.length) { e.preventDefault(); leaveEditing(); }
    return;
  }
  if (sheets.length || /INPUT|TEXTAREA/.test(document.activeElement.tagName)) return;
  if (e.key === 'Escape' && (selRange() || QS.touch || !$('#qbar').hidden)) { e.preventDefault(); window.getSelection()?.removeAllRanges(); hideQbar(); return; }
  if (mod || e.shiftKey || e.altKey || selRange() || QS.touch || document.activeElement.closest('button,[contenteditable=true],#qbar')) return;
  if (isPaged()) {
    if (['ArrowRight', 'PageDown', ' '].includes(e.key)) { e.preventDefault(); nextPage(); return; }
    if (['ArrowLeft', 'PageUp'].includes(e.key)) { e.preventDefault(); prevPage(); return; }
  } else {
    if (e.key === 'ArrowRight' && R.ch < R.content.chapters.length - 1) { goTo(R.ch + 1, { enter: 1 }); return; }
    if (e.key === 'ArrowLeft' && R.ch > 0) { goTo(R.ch - 1, { atEnd: true }); return; }
  }
  if (e.key === 'Escape') closeReader();
});

