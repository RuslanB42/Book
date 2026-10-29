/* ================= settings ================= */
async function openSettings() {
  const seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Оформление' });
  const opts = [['dark', 'Тёмное'], ['light', 'Светлое'], ['system', 'Как в системе']];
  const paint = () => seg.replaceChildren(...opts.map(([k, l]) => h('button', { 'aria-pressed': String(prefs.ui === k), onclick: () => withVT(() => { prefs.ui = k; savePrefs(); applyUi(); paint(); }) }, l)));
  paint();
  const storage = await storagePanel();
  openSheet({
    title: 'Настройки',
    body: h('div', null,
      h('div', { class: 'group-title' }, 'Оформление'),
      h('div', { style: 'margin-bottom:24px' }, seg),
      hapticsSetting(),
      storage),
  });
}

/* ================= import ================= */
async function importFiles(fileList) {
  const files = [...fileList];
  if (!files.length) return;
  if (!DB.ok) { storageError(new Error('Импорт отменён: постоянное хранилище недоступно.')); return; }
  if (STORE_HEALTH.busy) { toast('Дождитесь завершения резервного копирования.'); return; }
  await protectStorage();
  const t = toast(files.length > 1 ? `Добавляю файл 1 из ${files.length}…` : 'Добавляю файл…', { kind: 'import-progress', busy: true, timeout: 0 });
  const added = [], failed = [], dup = [];
  const target = !state.query && state.tab === 'shelves' && state.shelf ? state.shelf : null;
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    if (files.length > 1) t.set(`Добавляю файл ${i + 1} из ${files.length}…`);
    try {
      const { book, content } = await parseFile(f);
      const wish = state.books.find((b) => b.noFile && recSameBook(b, book));
      if (wish) { if (target) wish.shelf = target; await mergeIntoWish(wish, { book, content }); added.push(wish); continue; }
      const twin = state.books.find((b) => b.title === book.title && b.authors.join() === book.authors.join() && b.format === book.format);
      if (twin) { dup.push(book.title); continue; }
      if (target) book.shelf = target;
      book.pos = Math.min(0, ...state.books.map((x) => x.pos ?? 0)) - 1;
      await checkImportSpace(book, content);
      await DB.writeBatch([{ store: 'content', value: content }, { store: 'books', value: book }]);
      state.books.push(book); added.push(book);
    } catch (e) {
      failed.push({ name: f.name, why: e && e.name === 'QuotaExceededError' ? (e.message || 'не хватает места по квоте или на устройстве') : (e && e.message) || 'неизвестная ошибка' });
    }
  }
  t.close();
  if (added.length) { state.query = ''; $('#q').value = ''; $('#q-clear').hidden = true; if (added.every(book => book.shelf === REC_SHELF)) { state.tab='shelves';state.shelf=REC_SHELF;state.autoShelf=null; } else if (target) {state.shelf=target;state.autoShelf=null;} else {state.tab='shelves';state.shelf=null;state.autoShelf='want';} renderLibrary(); }

  if (added.length) setTimeout(() => toast(`Добавлено книг: ${added.length}`, { kind: 'import-result' }), 320);
}

/* ================= shelves management ================= */
function shelfNameSheet({ title, value = '', okLabel, onSave }) {
  const input = h('input', { id: 'shelf-name', autocomplete: 'off', maxlength: 40, placeholder: 'Например, Прочитать летом', enterkeyhint: 'done' });
  input.value = value;
  const err = h('span', { class: 'err', hidden: true });
  const field = h('div', { class: 'field' }, h('label', { for: 'shelf-name' }, 'Название полки'), input, err);
  const submit = () => {
    const name = input.value.trim().replace(/\s+/g, ' ');
    const exists = shelves().some(([n]) => n.toLowerCase() === name.toLowerCase() && n !== value);
    const msg = !name ? 'Введите название полки' : exists ? 'Такая полка уже есть' : '';
    if (msg) { field.setAttribute('data-invalid', ''); err.hidden = false; err.textContent = msg; input.focus(); return; }
    s.close(true); onSave(name);
  };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  input.addEventListener('input', () => { field.removeAttribute('data-invalid'); err.hidden = true; });
  const s = openSheet({ title, body: h('div', null, field, h('button', { class: 'btn wide', onclick: submit }, okLabel)) });
  setTimeout(() => input.focus(), 80);
}
function createShelf(after) {
  shelfNameSheet({
    title: 'Новая полка', okLabel: 'Создать полку',
    onSave: (name) => {
      prefs.shelves = [...(prefs.shelves || []), name]; savePrefs();
      if (after) { after(name); return; }
      state.query = ''; go('shelves', name);
      toast(`Полка «${name}» создана`);
    },
  });
}
function shelfMenu(name) {
  const s = openSheet({
    title: name,
    body: h('div', { class: 'group menu-list' },
      h('button', { onclick: () => { s.close(true); pickBooks(name); } }, icon('shelf'), 'Переложить сюда книги'),
      h('button', { onclick: () => { s.close(true); renameShelf(name); } }, icon('pen'), 'Переименовать'),
      h('button', { class: 'danger', onclick: () => { s.close(true); deleteShelf(name); } }, icon('trash'), 'Удалить полку')),
  });
}
function renameShelf(old) {
  shelfNameSheet({
    title: 'Переименовать полку', value: old, okLabel: 'Сохранить',
    onSave: async (name) => {
      if (name === old) return;
      prefs.shelves = (prefs.shelves || []).map((x) => (x === old ? name : x));
      if (!prefs.shelves.includes(name)) prefs.shelves.push(name);
      savePrefs();
      for (const b of state.books.filter((x) => x.shelf === old)) { b.shelf = name; await persistBook(b).catch(() => {}); }
      state.shelf = name; renderLibrary();
      toast('Полка переименована');
    },
  });
}
async function deleteShelf(name) {
  const list = state.books.filter((b) => b.shelf === name);
  const ok = await confirmSheet({ title: `Удалить полку «${name}»?`, text: list.length ? `${list.length} ${plural(list.length, BOOKS)} с неё переедут на полку «Разное». Сами книги не удалятся.` : 'Полка пустая, книги не пострадают.', ok: 'Удалить полку', danger: true });
  if (!ok) return;
  prefs.shelves = (prefs.shelves || []).filter((x) => x !== name); savePrefs();
  for (const b of list) { b.shelf = 'Разное'; await persistBook(b).catch(() => {}); }
  go('shelves');
  toast(`Полка «${name}» удалена`);
}
function pickBooks(target) {
  const candidates = state.books.filter((b) => b.shelf !== target).sort((a, b) => a.title.localeCompare(b.title, 'ru'));
  const chosen = new Set();
  const doneBtn = h('button', { class: 'txt-btn strong', disabled: true }, 'Готово');
  const refresh = () => { doneBtn.disabled = !chosen.size; doneBtn.textContent = chosen.size ? `Готово (${chosen.size})` : 'Готово'; };
  const body = candidates.length
    ? h('ul', { class: 'pick' }, candidates.map((b) => h('li', null, h('button', {
      role: 'checkbox', 'aria-checked': 'false',
      onclick: (e) => { const btn = e.currentTarget; if (chosen.has(b)) chosen.delete(b); else chosen.add(b); btn.setAttribute('aria-checked', String(chosen.has(b))); refresh(); },
    }, coverEl(b), h('span', null, h('span', { class: 't' }, b.title), h('span', { class: 's' }, `${authorsStr(b)} | ${b.shelf}`)), h('span', { class: 'ck' }, icon('check'))))))
    : h('p', { class: 'muted' }, 'Все книги уже стоят на этой полке.');
  doneBtn.addEventListener('click', async () => {
    for (const b of chosen) { b.shelf = target; await persistBook(b).catch(() => {}); }
    s.close(true);
    if (!(prefs.shelves || []).includes(target)) { prefs.shelves = [...(prefs.shelves || []), target]; savePrefs(); }
    go('shelves', target);
    toast(`${chosen.size} ${plural(chosen.size, BOOKS)} на полке «${target}»`);
  });
  const s = openSheet({ title: target, tall: true, body, left: h('button', { class: 'txt-btn', onclick: () => s.close() }, 'Отмена'), right: doneBtn });
}
function chooseShelf(book, after) {
  const apply = async (name) => {
    book.shelf = name; await persistBook(book).catch(() => {});
    if (!(prefs.shelves || []).includes(name)) { prefs.shelves = [...(prefs.shelves || []), name]; savePrefs(); }
    renderLibrary(false); toast(`«${book.title}» на полке «${name}»`); if (after) after();
  };
  const s = openSheet({
    title: 'Полка',
    body: h('div', null,
      h('ul', { class: 'pick' }, shelves().map(([name, list]) => h('li', null, h('button', { 'aria-pressed': String(book.shelf === name), onclick: () => { s.close(true); if (name !== book.shelf) apply(name); } },
        h('span', { class: 'ck', style: 'margin-left:6px' }, icon('check')), h('span', null, h('span', { class: 't' }, name), h('span', { class: 's' }, `${list.length} ${plural(list.length, BOOKS)}`)), h('span'))))),
      h('button', { class: 'link-btn pad-top', onclick: () => { s.close(true); createShelf((name) => apply(name)); } }, '+ Новая полка')),
  });
}

