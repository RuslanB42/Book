/* ---------- start: pick a few liked books ---------- */
function recStartList() {
  const d = REC.data, sig = recSignals();
  const order = d.b.map((b, i) => [b[7] + (b[9] && b[9].includes(d.tags.indexOf('русская литература')) ? 12 : 0) + (b[9] && b[9].includes(d.tags.indexOf('классика')) ? 4 : 0), i])
    .filter(([, i]) => !sig.has(i) && !(d.b[i][3] > 1)).sort((a, b) => b[0] - a[0]).map((x) => x[1]);
  // spread across themes: no more than two books sharing a leading theme on one page
  const pages = [], used = new Map(), pool = order.slice(0, 260);
  let page = [];
  while (pool.length && pages.length < 8) {
    const k = pool.findIndex((i) => (used.get(d.b[i][9]?.[0]) || 0) < 2); const i = pool.splice(k < 0 ? 0 : k, 1)[0];
    const th = d.b[i][9]?.[0]; used.set(th, (used.get(th) || 0) + 1); page.push(i);
    if (page.length === 12) { pages.push(page); page = []; used.clear(); }
  }
  return pages;
}
function recStart(box) {
  const pages = recStartList();
  const page = pages[REC.startPage % Math.max(1, pages.length)] || [];
  const sel = REC.startSel;
  const known = recPositive();
  const go = h('button', { class: 'btn wide primary' }, 'Подобрать книги');
  const paintGo = () => { go.textContent = sel.size ? `Подобрать книги · ${sel.size}` : 'Подобрать книги'; };
  const grid = h('div', { class: 'fy-pick fy-look' }, page.map((i) => {
    const b = recBook(i), gb = b.gb;
    const el = h('button', { class: 'fy-pk', 'aria-pressed': String(sel.has(gb)), 'aria-label': `${b.title}, ${authorsStr(b)}`, onclick: () => { sel.has(gb) ? sel.delete(gb) : sel.add(gb); el.setAttribute('aria-pressed', String(sel.has(gb))); paintGo(); if (!reduceMotion()) el.animate([{ transform: 'scale(.94)' }, { transform: 'none' }], { duration: 260, easing: 'cubic-bezier(.34,1.5,.64,1)' }); } },
      coverEl(b), h('span', { class: 'fy-pk-t' }, b.title), h('span', { class: 'fy-pk-ok', 'aria-hidden': 'true' }));
    return el;
  }));
  go.onclick = () => { REC.st.picks = [...new Set([...REC.st.picks, ...sel])]; REC.st.onboarded = true; sel.clear(); recSave(); recPaint(box); };
  paintGo();
  box.replaceChildren(h('div', { class: 'fy-start' },
    h('h2', null, 'Какие из этих книг вам понравились?'),
    h('p', { class: 'muted' }, known ? `${known} ${plural(known, ['книгу', 'книги', 'книг'])} из вашей библиотеки мы уже учли. Отметьте ещё несколько — с них начнётся подборка.` : 'Отметьте несколько книг, которые вам нравятся, — с них начнётся подборка.'),
    grid,
    h('button', { class: 'link-btn fy-more', onclick: () => { REC.startPage++; recStart(box); } }, 'Показать другие'),
    h('div', { class: 'fy-start-go' }, go, h('button', { class: 'link-btn', onclick: () => { REC.st.onboarded = true; recSave(); recPaint(box); } }, 'Пропустить'))));
}
function recTune(box) {
  const d = REC.data;
  const lib = state.books.filter((b) => !b.sample && !b.noFile);
  const matched = lib.filter((b) => recMatch(b) >= 0).length;
  const hidden = Object.values(REC.st.sw).filter((s) => s.a === 'nope').length;
  let s;
  const body = h('div', { class: 'detail fy-tune-body' },
    h('div', { class: 'group menu-list' },
      h('button', { onclick: () => { s.close(true); REC.startSel.clear(); recStart(box); } }, icon('plus'), 'Отметить любимые книги'),
      hidden ? h('button', { onclick: () => { for (const [g, v] of Object.entries(REC.st.sw)) if (v.a === 'nope') delete REC.st.sw[g]; recSave(); s.close(true); recPaint(box); toast('Скрытые книги снова в подборке'); } }, icon('back'), `Вернуть скрытые книги · ${hidden}`) : null,
      h('button', { class: 'danger', onclick: async () => { s.close(true); if (await confirmSheet({ title: 'Начать подборку заново?', text: 'Отметки «Не то», «Читал» и выбранные любимые книги забудутся. Книги на полках останутся.', ok: 'Начать заново', danger: true })) { REC.st = { v: 1, picks: [], sw: {}, onboarded: false }; REC.hist = []; recSave(); recPaint(box); } } }, icon('trash'), 'Начать заново')),
    h('p', { class: 'muted fy-note' }, lib.length ? `Мы узнали ${matched} из ${lib.length} ${plural(lib.length, BOOKS)} вашей библиотеки. Остальных пока нет в каталоге — по ним подборка не строится.` : ''),
    h('p', { class: 'muted fy-note' }, `В каталоге ${d.b.length.toLocaleString('ru')} книг. Связи между ними посчитаны по открытым оценкам читателей (Goodbooks-10k, CC BY-SA 4.0). Подборка считается на вашем устройстве — ничего никуда не отправляется.`));
  s = openSheet({ title: 'Подборка', body });
}

