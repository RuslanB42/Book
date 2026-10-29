/* ---------- profile screen ---------- */
let profileRange = 7;
function openProfile() {
  stopQuoteBookMotion();
  $('#lib').hidden = true; $('#profile').hidden = false; $('#tabbar').hidden = true; $('#btn-add').hidden = true;
  window.scrollTo(0, 0); renderProfile(); playIn($('#profile .wrap'), 'push-in'); libScrollChrome();
}
function closeProfile() {
  $('#profile').hidden = true; $('#lib').hidden = false; $('#tabbar').hidden = false; $('#btn-add').hidden = false;
  renderLibrary(false); playIn($('#lib .wrap'), 'pop-back'); paintTabbar(); libScrollChrome();
}

function renderProfile() {
  const main = $('#pf-main');
  main.replaceChildren();
  const st = streakInfo();
  const tot = totals();
  const today = ST.data.days[dayKey()] || { ms: 0, chars: 0 };
  const goal = goalMs();
  const speed = wpm(tot.chars, tot.ms);
  const state = st.todayMet ? 'on' : st.current > 0 ? 'risk' : 'off';
  const left = Math.max(0, Math.ceil((goal - today.ms) / 60000));

  /* hero: the flame carries the streak number; the bar under it is today's goal */
  const flame = flameSvg(state === 'off' ? 'off' : 'on', 0);
  // the flame only comes alive when the streak has grown since the last visit
  const celebrate = st.current > 0 && st.current > (ST.data.seenStreak || 0) && !reduceMotion();
  if (ST.data.seenStreak !== st.current) { ST.data.seenStreak = st.current; saveStats(); }
  if (celebrate) flame.classList.add('celebrate');
  const num = flame.querySelector('.fnum');
  const status = st.todayMet ? `Цель на сегодня выполнена — ${fmtDur(today.ms)}. Огонёк горит!`
    : st.current > 0 ? `Почитайте ещё ${left} мин сегодня, чтобы не потерять серию`
      : `Огонёк загорится после ${ST.data.goal} минут чтения за день`;
  const fill = h('i', { style: `width:${Math.min(100, (today.ms / goal) * 100)}%` });
  const week = h('div', { class: 'week', role: 'list', 'aria-label': 'Последние 7 дней' });
  const wd = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(); d.setDate(d.getDate() - i);
    const k = dayKey(d), rec = ST.data.days[k], met = dayMet(k), part = rec && rec.ms > 0 && !met;
    week.append(h('div', { class: 'wd' + (i === 0 ? ' today' : ''), role: 'listitem', style: `--i:${6 - i}`, 'aria-label': `${wd[d.getDay()]}: ${rec ? fmtDur(rec.ms) : 'не читали'}` },
      h('span', { class: 'dot' + (met ? ' met' : part ? ' part' : '') }, met ? flameSvg('on') : null),
      h('span', { class: 'wl' }, wd[d.getDay()])));
  }
  main.append(h('section', { class: 'p-hero card ' + state },
    h('div', { class: 'hero-top' },
      h('div', { class: 'flame-box', role: 'img', 'aria-label': `Серия: ${st.current} ${plural(st.current, ['день', 'дня', 'дней'])} подряд` }, flame),
      h('div', { class: 'hero-txt' },
        h('div', { class: 'streak-title' }, `${plural(st.current, ['день', 'дня', 'дней'])} подряд`),
        h('p', { class: 'hero-status' }, status),
        h('div', { class: 'goal-bar' }, h('div', { class: 'gb-row' }, h('span', null, 'Сегодня'), h('span', null, `${Math.min(ST.data.goal, Math.floor(today.ms / 60000))} из ${ST.data.goal} мин`)), h('div', { class: 'gb' }, fill)))),
    week));
  if (celebrate) { countUp(num, st.current, (v) => String(Math.round(v)), 900); setTimeout(() => num.classList.add('pop'), 920); }
  else num.textContent = String(st.current);

  /* overview tiles: each number carries its own small chart */
  main.append(overviewTiles(tot, today, goal, speed));

  /* activity chart */
  main.append(activityCard());

  /* weekly rhythm */
  main.append(rhythmCard());

  /* books */
  const rows = Object.entries(ST.data.books).filter(([, b]) => b.ms >= 30000).sort((a, b) => b[1].ms - a[1].ms);
  const maxMs = rows.length ? rows[0][1].ms : 1;
  const list = h('div', { class: 'blist' });
  const booksDonut = rows.length ? bookShareDonut(rows) : null;
  if (!rows.length) list.append(h('p', { class: 'muted', style: 'padding:18px 6px' }, 'Здесь появятся книги, которые вы читали, и сколько времени ушло на каждую.'));
  rows.forEach(([id, s], i) => {
    const book = state_book(id);
    const sp = wpm(s.chars, s.ms);
    const pct = book ? pctOf(book) : 0;
    const remainChars = book && s.total ? Math.max(0, s.total * (1 - ((book.progress && book.progress.pct) || 0))) : 0;
    const eta = sp && remainChars && pct < 100 ? `осталось ≈ ${fmtDur(remainChars / CHARS_PER_WORD / sp * 60000)}` : pct >= 100 ? 'прочитана' : '';
    const bar = h('i', { style: `width:${Math.max(4, (s.ms / maxMs) * 100)}%` });
    const row = h(book ? 'button' : 'div', { class: 'brow', style: `--i:${i}`, onclick: book ? (e) => openDetail(book, e.currentTarget) : null },
      book ? coverEl(book) : h('span', { class: 'cover' }),
      h('span', { class: 'bm' },
        h('span', { class: 'bt' }, book ? book.title : s.title || 'Удалённая книга'),
        h('span', { class: 'bs' }, [fmtDur(s.ms), sp ? `${sp} сл/мин` : '', eta].filter(Boolean).join(' | ')),
        h('span', { class: 'share' }, bar)),
      book ? ringEl(pct / 100, 38, 3.5, pct >= 100 ? '#34c27a' : '#f28a2e', pct + '%') : ringEl(0, 38, 3.5, 'var(--fg-3)', '—'));
    list.append(row);
  });
  main.append(h('section', { class: 'card pad' }, h('div', { class: 'card-h' }, h('h2', null, 'Время по книгам'), rows.length ? h('span', null, `${rows.length} ${plural(rows.length, BOOKS)}`) : null),
    booksDonut, booksDonut ? h('h3', { class: 'sub-h' }, 'Прогресс и темп') : null, list));

  /* library composition */
  main.append(libraryCard());

  /* records */
  main.append(h('section', { class: 'records' },
    rec('Лучшая серия', `${st.best} ${plural(st.best, ['день', 'дня', 'дней'])}`),
    rec('Рекорд за день', tot.bestDay ? fmtDur(tot.bestDay, true) : '—'),
    rec('Дочитано книг', String(booksDone()))));

  /* goal */
  const goals = [5, 10, 15, 30, 60];
  const seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Цель на день' }, goals.map((g) =>
    h('button', { 'aria-pressed': String(ST.data.goal === g), onclick: () => { ST.data.goal = g; saveStats(); renderProfile(); } }, g + ' мин')));
  main.append(h('section', { class: 'card pad' }, h('div', { class: 'card-h' }, h('h2', null, 'Цель на день')),
    h('p', { class: 'hint-p' }, 'Сколько минут нужно читать в день, чтобы огонёк горел и серия росла.'), seg));
}
function rec(label, value) { return h('div', { class: 'rec card' }, h('b', null, value), h('span', null, label)); }
function booksDone() { return state.books.filter((b) => pctOf(b) >= 100).length; }
function state_book(id) { return state.books.find((b) => b.id === id) || null; }

