/* ---------- profile infographics ---------- */
const IG_COLORS = ['#f28a2e', '#6f9dff', '#34c27a', '#c77dff', '#ffc93c', '#ff6b8a', '#2ec5d3', '#8e8e93'];
const WD_MON = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
const MON_SHORT = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
let igSeq = 0;
function dayAgo(i) { const d = new Date(); d.setHours(12, 0, 0, 0); d.setDate(d.getDate() - i); return d; }
function daySeries(n, field = 'ms') { const out = []; for (let i = n - 1; i >= 0; i--) { const r = ST.data.days[dayKey(dayAgo(i))]; out.push(r ? r[field] || 0 : 0); } return out; }
function sumRange(from, to) { let s = 0; for (let i = from; i < to; i++) { const r = ST.data.days[dayKey(dayAgo(i))]; if (r) s += r.ms || 0; } return s; }
function trendChip(cur, prev) {
  if (!cur && !prev) return null;
  if (!prev) return h('span', { class: 'trend up', title: 'Раньше вы не читали' }, '↑ новое');
  const p = Math.round(((cur - prev) / prev) * 100);
  const cls = p > 2 ? 'up' : p < -2 ? 'down' : 'flat';
  return h('span', { class: 'trend ' + cls, title: 'По сравнению с прошлым периодом' }, (cls === 'up' ? '↑ +' : cls === 'down' ? '↓ ' : '→ ') + p + '%');
}
function animDash(el, val) {
  if (reduceMotion()) { el.style.strokeDasharray = `${val} 100`; return; }
  el.style.strokeDasharray = '0 100';
  requestAnimationFrame(() => requestAnimationFrame(() => { el.style.strokeDasharray = `${val} 100`; }));
}
function ringEl(frac, size, stroke, color, label) {
  const r = (size - stroke) / 2, c = size / 2;
  const svg = svgEl('svg', { class: 'ring', width: size, height: size, viewBox: `0 0 ${size} ${size}`, 'aria-hidden': 'true' });
  const v = svgEl('circle', { class: 'rv', cx: c, cy: c, r, 'stroke-width': stroke, stroke: color, pathLength: 100 });
  svg.append(svgEl('circle', { class: 'rt', cx: c, cy: c, r, 'stroke-width': stroke }), v);
  animDash(v, clamp(frac, 0, 1) * 100 || 0.001);
  return h('span', { class: 'ring-wrap', style: `width:${size}px;height:${size}px` }, svg, label != null ? h('span', { class: 'rc' }, label) : null);
}
function donutEl(parts, size, stroke, center) {
  const total = parts.reduce((a, p) => a + p.v, 0) || 1;
  const r = (size - stroke) / 2, c = size / 2, gap = parts.length > 1 ? 1.2 : 0;
  const svg = svgEl('svg', { class: 'donut', width: size, height: size, viewBox: `0 0 ${size} ${size}`, 'aria-hidden': 'true' });
  svg.append(svgEl('circle', { class: 'dt', cx: c, cy: c, r, 'stroke-width': stroke }));
  let acc = 0;
  parts.forEach((p) => {
    const len = (p.v / total) * 100;
    if (len <= 0) return;
    const seg = svgEl('circle', { class: 'dv', cx: c, cy: c, r, 'stroke-width': stroke, stroke: p.c, pathLength: 100, 'stroke-dashoffset': String(-acc) });
    svg.append(seg); animDash(seg, Math.max(0.3, len - gap)); acc += len;
  });
  return h('div', { class: 'donut-wrap', style: `width:${size}px;height:${size}px` }, svg, h('div', { class: 'dc' }, center));
}
function sparkEl(vals, color) {
  const id = 'sp' + (++igSeq), n = vals.length, max = Math.max(1, ...vals);
  const pts = vals.map((v, i) => [n > 1 ? (i / (n - 1)) * 100 : 50, 31 - (v / max) * 27]);
  // smooth line through the points
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 1; i < n; i++) { const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], mx = (x0 + x1) / 2; d += ` C${mx},${y0} ${mx},${y1} ${x1},${y1}`; }
  const svg = svgEl('svg', { class: 'spark reveal', viewBox: '0 0 100 34', preserveAspectRatio: 'none', 'aria-hidden': 'true', style: `--c:${color}` });
  const g = svgEl('linearGradient', { id, x1: 0, y1: 0, x2: 0, y2: 1 });
  g.append(svgEl('stop', { offset: '0', 'stop-color': color, 'stop-opacity': '.32' }), svgEl('stop', { offset: '1', 'stop-color': color, 'stop-opacity': '0' }));
  const defs = svgEl('defs', {}); defs.append(g);
  svg.append(defs, svgEl('path', { d: `${d} L100,34 L0,34 Z`, fill: `url(#${id})` }), svgEl('path', { class: 'sl', d }));
  return svg;
}
function gaugeEl(frac, color) {
  const svg = svgEl('svg', { class: 'gauge', viewBox: '0 0 100 58', 'aria-hidden': 'true' });
  const arc = 'M10 52 A40 40 0 0 1 90 52';
  const v = svgEl('path', { class: 'gv', d: arc, stroke: color, 'stroke-width': 9, pathLength: 100 });
  const t = svgEl('text', { class: 'gp', x: 50, y: 50, 'text-anchor': 'middle' }); t.textContent = Math.round(frac * 100) + '%';
  svg.append(svgEl('path', { class: 'gt', d: arc, 'stroke-width': 9, 'stroke-linecap': 'round' }), v, t);
  animDash(v, clamp(frac, 0, 1) * 100 || 0.001);
  return svg;
}
function igTile(i, color, iconName, label, valueEl, sub, viz) {
  return h('div', { class: 'ig-tile card', style: `--i:${i};--c:${color}` },
    h('div', { class: 'ig-head' }, h('span', { class: 'ig-ico' }, icon(iconName)), h('span', { class: 'tl' }, label)),
    valueEl, h('span', { class: 'ts' }, sub), h('div', { class: 'ig-viz' }, viz));
}
function overviewTiles(tot, today, goal, speed) {
  const grid = h('section', { class: 'ig-ov', 'aria-label': 'Сводка' });
  const last14 = daySeries(14);
  const b1 = h('b', null, '0');
  countUp(b1, tot.ms, (v) => fmtDur(v, true), 1000);
  grid.append(igTile(0, '#f28a2e', 'clock', 'Всего', b1, tot.first ? `с ${fmtDate(tot.first).replace(/ \d{4} г\.?$/, '')}` : 'Пока ни минуты', sparkEl(last14, '#f28a2e')));

  const frac = goal ? today.ms / goal : 0;
  const b2 = h('b', null, '0');
  countUp(b2, today.ms, (v) => fmtDur(v, true), 1000);
  grid.append(igTile(1, '#34c27a', 'target', 'Сегодня', b2, frac >= 1 ? 'цель выполнена' : `цель — ${ST.data.goal} мин`, gaugeEl(Math.min(1, frac), frac >= 1 ? '#34c27a' : '#f28a2e')));

  const b3 = h('b', null, speed ? '0' : '—');
  if (speed) countUp(b3, speed, (v) => String(Math.round(v)), 1000);
  const x = clamp((speed || 0) / 400, 0.02, 0.98) * 100;
  const scale = h('div', null, h('div', { class: 'scale', style: `--x:${speed ? x : 0}%;--m:50%` }, speed ? h('i') : null, h('u')), h('div', { class: 'scale-l' }, h('span', null, '0'), h('span', null, 'ср. 200'), h('span', null, '400+')));
  grid.append(igTile(2, '#5b8cff', 'bolt', 'Скорость', b3, speed ? `слов/мин | ${speed >= 220 ? 'быстрее среднего' : speed >= 180 ? 'средний темп' : 'вдумчивый темп'}` : 'посчитаем после пары минут чтения', scale));

  return grid;
}

function rhythmCard() {
  const sums = [0, 0, 0, 0, 0, 0, 0], cnt = [0, 0, 0, 0, 0, 0, 0];
  for (let i = 0; i < 56; i++) { const d = dayAgo(i), w = (d.getDay() + 6) % 7; sums[w] += ((ST.data.days[dayKey(d)] || {}).ms || 0); cnt[w]++; }
  const avg = sums.map((s, i) => s / Math.max(1, cnt[i]));
  const max = Math.max(...avg), top = max > 0 ? avg.indexOf(max) : -1;
  const sessions = Object.values(ST.data.books).reduce((a, b) => a + (b.sessions || 0), 0);
  const tot = totals();
  const avgSession = sessions ? tot.ms / sessions : 0;
  const weekend = (avg[5] + avg[6]) / 2, weekday = avg.slice(0, 5).reduce((a, b) => a + b, 0) / 5;
  const names = ['понедельникам', 'вторникам', 'средам', 'четвергам', 'пятницам', 'субботам', 'воскресеньям'];
  const insight = top < 0 ? 'Почитайте несколько дней — и здесь появится ваш личный ритм.'
    : null;
  return h('section', { class: 'card pad' },
    h('div', { class: 'card-h' }, h('h2', null, 'Ритм недели'), h('span', null, 'среднее за 8 нед.')),
    h('div', { class: 'rhythm', role: 'list' }, avg.map((v, i) => h('div', { class: 'rh' + (i === top ? ' top' : ''), role: 'listitem', 'aria-label': `${WD_MON[i]}: в среднем ${fmtDur(v, true)}` },
      h('span', { class: 'rv-l' }, v >= 30000 ? String(Math.round(v / 60000)) + 'м' : v > 0 ? '<1м' : ''),
      h('span', { class: 'track' }, h('i', { style: `--i:${i};height:${max ? Math.max(v ? 6 : 0, (v / max) * 100) : 0}%` })),
      h('span', { class: 'wl' }, WD_MON[i])))),
    h('div', { class: 'ig-insight' }, icon('spark'), insight ? h('span', null, insight)
      : h('span', null, 'Больше всего вы читаете по ', h('b', null, names[top]), '. ',
        weekend > weekday * 1.15 ? 'Выходные — ваше время для книг. ' : weekday > weekend * 1.15 ? 'В будни вы читаете больше, чем в выходные. ' : 'Вы читаете ровно всю неделю. ',
        avgSession ? ['Средняя сессия — ', h('b', null, fmtDur(avgSession, true)), '.'] : '')));
}

function bookShareDonut(rows) {
  const total = rows.reduce((a, [, b]) => a + b.ms, 0);
  const top = rows.slice(0, 5).map(([id, b], i) => { const book = state_book(id); return { n: book ? book.title : b.title || 'Удалённая книга', v: b.ms, c: IG_COLORS[i] }; });
  const rest = rows.slice(5).reduce((a, [, b]) => a + b.ms, 0);
  if (rest) top.push({ n: 'Другие', v: rest, c: IG_COLORS[7] });
  const hrs = total / 3600000;
  const center = [h('b', null, hrs >= 1 ? (hrs >= 10 ? Math.round(hrs) : hrs.toFixed(1).replace('.', ',')) : String(Math.round(total / 60000))), h('span', null, hrs >= 1 ? 'часов' : 'минут')];
  return h('div', { class: 'ig-split', style: 'margin:6px 0 4px' }, donutEl(top, 132, 16, center),
    h('ul', { class: 'legend' }, top.map((p, i) => h('li', { style: `--c:${p.c};--i:${i}` }, h('span', { class: 'sw' }), h('span', { class: 'ln' }, p.n), h('span', { class: 'lv' }, Math.round((p.v / total) * 100) + '%')))));
}

function libraryCard() {
  const books = state.books || [];
  const done = books.filter((b) => readingStatus(b)==='done').length;
  const reading = books.filter((b) => readingStatus(b)==='reading').length;
  const fresh = books.length - done - reading;
  const parts = [{ n: 'Читаю', v: reading, c: '#f28a2e' }, { n: 'Прочитано', v: done, c: '#34c27a' }, { n: 'Хочу прочитать', v: fresh, c: '#8e8e93' }];
  const shelves = {};
  books.forEach((b) => { const s = b.shelf || 'Без полки'; shelves[s] = (shelves[s] || 0) + 1; });
  const sh = Object.entries(shelves).sort((a, b) => b[1] - a[1]);
  const shTop = sh.slice(0, 6), shRest = sh.slice(6).reduce((a, [, n]) => a + n, 0);
  if (shRest) shTop.push(['Другие', shRest]);
  const count = (k) => books.reduce((a, b) => a + ((b[k] && b[k].length) || 0), 0);
  const q = count('quotes'), nt = count('notes'), bm = count('bookmarks');
  if (!books.length) return h('section', { class: 'card pad' }, h('div', { class: 'card-h' }, h('h2', null, 'Библиотека')), h('p', { class: 'muted', style: 'padding:12px 6px' }, 'Добавьте книги — здесь появится состав библиотеки.'));
  return h('section', { class: 'card pad' },
    h('div', { class: 'card-h' }, h('h2', null, 'Библиотека'), h('span', null, `${books.length} ${plural(books.length, BOOKS)}`)),
    h('div', { class: 'ig-split' },
      donutEl(parts, 132, 16, [h('b', null, (books.length ? Math.round((done / books.length) * 100) : 0) + '%'), h('span', null, 'прочитано')]),
      h('ul', { class: 'legend' }, parts.map((p, i) => h('li', { style: `--c:${p.c};--i:${i}` }, h('span', { class: 'sw' }), h('span', { class: 'ln' }, p.n), h('span', { class: 'lv' }, String(p.v)))))),
    h('h3', { class: 'sub-h' }, 'Полки'),
    h('div', { class: 'stack', role: 'img', 'aria-label': shTop.map(([n, c]) => `${n}: ${c}`).join(', ') }, shTop.map(([, c], i) => h('i', { style: `flex:${c};--c:${IG_COLORS[(i + 1) % 7]};--i:${i}` }))),
    h('div', { class: 'chips-lg' }, shTop.map(([n, c], i) => h('span', { style: `--c:${IG_COLORS[(i + 1) % 7]}` }, n, ' ', h('em', null, String(c))))),
    h('div', { class: 'counters' },
      h('div', { class: 'counter' }, h('span', { class: 'ig-ico', style: '--c:#ffc93c' }, icon('quote')), h('b', null, String(q)), h('span', null, plural(q, ['цитата', 'цитаты', 'цитат']))),
      h('div', { class: 'counter' }, h('span', { class: 'ig-ico', style: '--c:#5b8cff' }, icon('pen')), h('b', null, String(nt)), h('span', null, plural(nt, ['заметка', 'заметки', 'заметок']))),
      h('div', { class: 'counter' }, h('span', { class: 'ig-ico', style: '--c:#ff6b8a' }, icon('bm')), h('b', null, String(bm)), h('span', null, plural(bm, ['закладка', 'закладки', 'закладок'])))));
}

function activityCard() {
  const days = profileRange;
  const goal = goalMs();
  const items = [];
  for (let i = days - 1; i >= 0; i--) { const d = new Date(); d.setDate(d.getDate() - i); const k = dayKey(d); items.push({ d, k, ms: (ST.data.days[k] || {}).ms || 0 }); }
  const sum = items.reduce((a, x) => a + x.ms, 0);
  const max = Math.max(goal * 1.25, ...items.map((x) => x.ms));
  const wd = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];
  const fmtD = (d) => `${wd[d.getDay()]}, ${d.getDate()} ${['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'][d.getMonth()]}`;
  const tip = h('div', { class: 'ctip', hidden: true });
  const plot = h('div', { class: 'plot', style: `--n:${days}` });
  const goalY = (goal / max) * 100;
  plot.append(h('div', { class: 'goal-line', style: `bottom:${goalY}%` }, h('span', null, `цель ${ST.data.goal} мин`)));
  items.forEach((x, i) => {
    const col = h('button', { class: 'col' + (x.ms >= goal ? ' met' : '') + (i === days - 1 ? ' today' : ''), style: `--i:${i}`, 'aria-label': `${fmtD(x.d)}: ${x.ms ? fmtDur(x.ms) : 'не читали'}` },
      h('span', { class: 'barv' + (x.ms ? '' : ' zero'), style: `height:${x.ms ? Math.max(2.5, (x.ms / max) * 100) : 0}%` }));
    const show = () => {
      $$('.col.sel', plot).forEach((c) => c.classList.remove('sel'));
      col.classList.add('sel');
      tip.hidden = false;
      tip.replaceChildren(h('b', null, x.ms ? fmtDur(x.ms) : 'Не читали'), h('span', null, fmtD(x.d)));
      const pr = plot.getBoundingClientRect(), cr = col.getBoundingClientRect();
      tip.style.left = clamp(cr.left - pr.left + cr.width / 2, 50, pr.width - 50) + 'px';
    };
    col.addEventListener('pointerenter', show); col.addEventListener('focus', show); col.addEventListener('click', show);
    plot.append(col);
  });
  plot.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse') { tip.hidden = true; $$('.col.sel', plot).forEach((c) => c.classList.remove('sel')); } });
  const axis = h('div', { class: 'xaxis', style: `--n:${days}` }, items.map((x, i) => h('span', null, days === 7 ? wd[x.d.getDay()] : (i % 5 === 4 || i === days - 1 ? String(x.d.getDate()) : ''))));
  const seg = h('div', { class: 'seg mini', role: 'group', 'aria-label': 'Период' }, [[7, '7 дней'], [30, '30 дней']].map(([n, l]) =>
    h('button', { 'aria-pressed': String(days === n), onclick: () => { profileRange = n; const c = activityCard(); $('#activity').replaceWith(c); } }, l)));
  const empty = !sum ? h('p', { class: 'chart-empty' }, 'График заполнится, когда вы начнёте читать') : null;
  return h('section', { class: 'card pad', id: 'activity' },
    h('div', { class: 'card-h' }, h('h2', null, 'Активность'), seg),
    h('p', { class: 'sum' }, h('span', null, h('b', null, fmtDur(sum, true)), ` за ${days === 7 ? 'неделю' : '30 дней'} | в среднем ${fmtDur(sum / days, true)} в день`), trendChip(sum, sumRange(days, days * 2))),
    h('div', { class: 'chart' }, plot, tip, empty), axis);
}

function updatePfBadge() {
  const b = $('#pf-badge'); if (!b) return;
  const st = streakInfo();
  b.hidden = true;
  b.textContent = st.current;
  b.classList.toggle('dim', !st.todayMet);
}

