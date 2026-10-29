/* ================= book card ================= */
function getBookReflection(book) { return (book.notes||[]).find(n=>n.scope==='book') || null; }
function openBookReflection(book,onSaved) { return editNotebookNote({book,note:getBookReflection(book),scope:'book',onSaved}); }
function bookRatingControl(book,{clearable=false}={}) {
  let saving=false;
  const status=h('small',{'aria-live':'polite'}),error=h('p',{class:'rating-error',role:'alert',hidden:true});
  const stars=h('div',{class:'rating-stars',role:'radiogroup','aria-label':'Оценка книги'});
  const root=h('div',{class:'book-rating'},h('div',{class:'rating-heading'},h('span',null,'Ваша оценка'),status),stars,error);
  const buttons=[];
  const update=()=>{
    const value=Number(book.rating)||0;root.setAttribute('aria-busy',String(saving));
    status.textContent=saving?'Сохраняем…':value?`${value} из 5`:'Не выбрана';
    buttons.forEach((b,i)=>{b.classList.toggle('is-filled',i<value);b.setAttribute('aria-checked',String(i+1===value));b.tabIndex=i===(value?value-1:0)?0:-1;b.setAttribute('aria-disabled',String(saving));});
    if(reset){reset.hidden=!value;reset.disabled=saving;}
  };
  const set=async value=>{
    if(saving||value===(Number(book.rating)||0))return;
    const previous=book.rating;saving=true;book.rating=value;error.hidden=true;update();
    root.dispatchEvent(new CustomEvent('rating-saving',{bubbles:true}));
    try {await persistBook(book);libraryTabCache.clear();
      if(value&&!reduceMotion()&&root.isConnected)buttons[value-1].animate([{transform:'scale(.9)'},{transform:'scale(1.08)',offset:.55},{transform:'scale(1)'}],{duration:380,easing:'cubic-bezier(.25,.1,.25,1)'});
    } catch(e){if(previous===undefined)delete book.rating;else book.rating=previous;error.hidden=false;error.textContent='Оценка не сохранена. Попробуйте ещё раз.';}
    finally {saving=false;update();root.dispatchEvent(new CustomEvent('rating-settled',{bubbles:true}));}
  };
  for(let n=1;n<=5;n++){
    const star=svgEl('svg',{viewBox:'0 0 24 24','aria-hidden':'true'});star.append(svgEl('path',{d:'M12 2.8l2.85 5.77 6.37.93-4.61 4.49 1.09 6.34L12 17.34l-5.7 2.99 1.09-6.34-4.61-4.49 6.37-.93Z','stroke-linejoin':'round'}));
    const b=h('button',{class:'rating-star',role:'radio','aria-label':`${n} ${plural(n,['звезда','звезды','звёзд'])}`,onclick:()=>set(n)},star);
    b.addEventListener('keydown',e=>{let next;if(e.key==='ArrowRight'||e.key==='ArrowUp')next=n%5+1;else if(e.key==='ArrowLeft'||e.key==='ArrowDown')next=(n+3)%5+1;else if(e.key==='Home')next=1;else if(e.key==='End')next=5;else return;e.preventDefault();buttons[next-1].focus();set(next);});
    buttons.push(b);stars.append(b);
  }
  const reset=clearable?h('button',{class:'link-btn rating-clear',onclick:()=>set(0)},'Убрать оценку'):null;
  if(reset)root.append(reset);update();return root;
}
function bookFeedbackPanel(book) {
  const panel=h('section',{class:'bd-feedback','aria-label':'Мои впечатления о книге'});
  const draw=()=>{const note=getBookReflection(book);panel.replaceChildren(...[bookRatingControl(book,{clearable:true}),note?h('p',{class:'book-reflection-preview'},note.text):null,h('button',{class:'btn ghost wide',onclick:()=>openBookReflection(book,()=>{if(panel.isConnected)draw()})},icon('pen','sm'),note?'Изменить мысли о книге':'Мысли о книге')].filter(Boolean));};
  draw();return panel;
}

function bookReadSpeed() {
  try { if (typeof totals === 'function') { const t = totals(); const v = wpm(t.chars, t.ms); if (v >= 60 && v <= 900) return v; } } catch (_) {}
  return 190;
}
function langLabel(code) {
  if (!code) return '';
  try { const n = new Intl.DisplayNames(['ru'], { type: 'language' }).of(code); if (n && n !== code) return n[0].toUpperCase() + n.slice(1); } catch (_) {}
  return code.toUpperCase();
}
// Shared-element flight of the cover between a list cell and the detail hero.
function flyCover(from, to, { duration = 520, easing = 'cubic-bezier(.22,1,.36,1)', hide = [] } = {}) {
  if (!from || !to || reduceMotion() || !Element.prototype.animate) return null;
  const a = from.getBoundingClientRect ? from.getBoundingClientRect() : from;
  const b = to.getBoundingClientRect ? to.getBoundingClientRect() : to;
  if (!a.width || !b.width || a.bottom < 0 || a.top > innerHeight) return null;
  const src = from.nodeType ? from : to;
  const ghost = src.cloneNode(true);
  for (const el of [ghost, ...ghost.querySelectorAll('[id]')]) el.removeAttribute('id');
  ghost.removeAttribute('style');
  ghost.classList.add('cover-ghost');
  // Lay the copy out at the larger of the two sizes and only ever scale it down:
  // the type stays crisp and the cover keeps one look (the shelf look) from start to end.
  const big = a.width >= b.width ? a : b;
  Object.assign(ghost.style, { left: big.left + 'px', top: big.top + 'px', width: big.width + 'px', height: big.height + 'px' });
  const pose = (r) => `translate3d(${r.left - big.left}px,${r.top - big.top}px,0) scale(${r.width / big.width},${r.height / big.height})`;
  document.body.append(ghost);
  for (const el of hide) if (el) el.style.visibility = 'hidden';
  const anim = ghost.animate([{ transform: pose(a) }, { transform: pose(b) }], { duration, easing, fill: 'both' });
  let cleaned = false;
  const done = () => { if (cleaned) return; cleaned = true; ghost.remove(); for (const el of hide) if (el) el.style.visibility = ''; };
  anim.cancelFlight = () => { anim.cancel(); done(); };
  anim.finished.then(done, done);
  return anim;
}
function openDetail(book, srcEl) {
  const p = pctOf(book);
  const edited = book.edits || 0;
  const st = (typeof ST !== 'undefined' && ST.data.books[book.id]) || null;
  const spent = Math.max(0, Number(st && st.ms) || 0);
  let s;
  const close = () => s.close();
  const backdrop = h('div', { class: 'bh-bg', 'aria-hidden': 'true' }, coverEl(book));
  const heroCover = coverEl(book);
  const cta = book.noFile ? 'Добавить файл книги' : p > 0 && p < 100 ? 'Продолжить' : p >= 100 ? 'Читать снова' : 'Читать';
  const read = () => { if (book.noFile) { attachFileTo(book, () => { s.close(true); openReader(book, $('.cv .cover', body)); }); return; } openReader(book, $('.cv .cover', body)); s.close(true); };

  // progress: a quiet line under the button, only once reading has begun
  const prog = h('div', { class: 'bd-prog', hidden: !(p > 0) });
  const progLine = h('i', { style: `--p:${Math.max(p ? 1.5 : 0, p) / 100}` });
  const progText = h('span', { class: 'bd-prog-t' }), progPct = h('b', null, p >= 100 ? '' : p + '%');
  prog.append(h('div', { class: 'bd-line', role: 'progressbar', 'aria-label': 'Прогресс чтения', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(p) }, progLine), h('div', { class: 'bd-prog-row' }, progText, progPct));
  if (p >= 100) prog.classList.add('done');
  progText.textContent = p >= 100 ? (book.completedAt ? 'Прочитана ' + fmtDate(book.completedAt) : 'Книга прочитана') : 'Прочитано';

  // facts strip: three quiet columns, hairline separated
  const fact = (label, value, extra = {}) => {
    const v = h('b', null, value);
    const el = h(extra.onclick ? 'button' : 'div', { class: 'bd-fact' + (extra.onclick ? ' tap' : ''), ...extra }, h('span', null, label), v);
    el._v = v; el._l = el.firstChild; return el;
  };
  const fVolume = fact('Объём', '—');
  const fTime = fact(p >= 100 ? 'За чтением' : p > 0 ? 'Осталось' : 'На чтение', '—');
  const fShelf = fact('Полка', book.shelf || 'Без полки', { 'aria-label': 'Полка: ' + (book.shelf || 'без полки') + '. Сменить', onclick: () => chooseShelf(book, () => { shelfName.textContent = book.shelf || 'Без полки'; fShelf.setAttribute('aria-label', 'Полка: ' + (book.shelf || 'без полки') + '. Сменить'); fShelf._v.animate([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], { duration: 320, easing: 'cubic-bezier(.22,1,.36,1)' }); }) });
  const shelfName = h('em', null, book.shelf || 'Без полки');
  fShelf._v.replaceChildren(shelfName, icon('next', 'sm'));
  const facts = h('div', { class: 'bd-facts' }, fVolume, fTime, fShelf);
  const fillFacts = (chars) => {
    if (chars) {
      const pages = Math.max(1, Math.round(chars / 1800));
      fVolume._v.textContent = `${pages.toLocaleString('ru')} стр.`;
    }
    if (p >= 100) fTime._v.textContent = spent ? fmtDur(spent, true) : '—';
    else if (chars) {
      const left = chars * (1 - ((book.progress && book.progress.pct) || 0));
      fTime._v.textContent = '≈ ' + fmtDur(Math.max(60000, left / CHARS_PER_WORD / bookReadSpeed() * 60000), true);
    }
  };
  fillFacts(book.chars || (book.recPages ? book.recPages * 1800 : 0));

  const annot = book.annotation ? h('div', { class: 'annot' }, book.annotation.split(/\n+/).map((t) => h('p', null, t))) : h('p', { class: 'annot muted-l' }, 'Описания пока нет. Его можно добавить в меню «⋯» → «Редактировать».');
  const info = [
    book.series ? ['Серия', book.series + (book.seriesNo ? ', книга ' + book.seriesNo : '')] : null,
    book.genres && book.genres.length ? ['Жанр', book.genres.map(genreName).join(', ')] : null,
    book.lang ? ['Язык', langLabel(book.lang)] : null,
    spent && p < 100 ? ['За чтением', fmtDur(spent, true)] : null,
    book.noFile ? ['Файл', 'Ещё не добавлен'] : ['Файл', (book.format || '').toUpperCase() + (book.size ? ', ' + fmtSize(book.size) : '')],
    ['Добавлена', fmtDate(book.addedAt)],
    edited ? ['Правки текста', `${edited} ${plural(edited, ['сохранение', 'сохранения', 'сохранений'])}`] : null,
  ].filter(Boolean);
  const sec = (title, ...kids) => h('section', { class: 'bd-sec' }, h('h4', { class: 'bd-h' }, title), ...kids);
  const showFeedback = p >= 100 || book.completedAt || book.rating || getBookReflection(book);

  const main = h('div', { class: 'bd-main' },
    book.sample ? h('span', { class: 'tag' }, 'Книга-пример') : null,
    h('h3', null, book.title),
    h('div', { class: 'au' }, authorsStr(book) || 'Автор не указан'),
    h('div', { class: 'bd-go' }, h('button', { class: 'btn wide primary', autofocus: true, onclick: read }, cta), book.noFile ? h('p', { class: 'bd-nofile' }, 'Выберите файл FB2 или EPUB — книга откроется здесь, с этой обложкой и местом на полке') : prog),
    facts,
    showFeedback ? sec('Впечатления', bookFeedbackPanel(book)) : null,
    sec('О книге', annot),
    sec('Подробности', h('dl', { class: 'bd-list' }, info.map(([k, v]) => h('div', null, h('dt', null, k), h('dd', null, v))))));
  [...main.children].forEach((el, i) => el.style.setProperty('--i', i));
  const body = h('div', { class: 'bd' },
    h('div', { class: 'bh' }, backdrop,
      h('div', { class: 'bh-bar' },
        h('button', { class: 'bh-btn', 'aria-label': 'Закрыть', onclick: close }, icon('back')),
        h('button', { class: 'bh-btn', 'aria-label': 'Ещё', onclick: () => bookMenu(book, s) }, icon('more'))),
      h('div', { class: 'cv' }, heroCover)),
    main);
  if (book.annotation && book.annotation.length > 260) {
    annot.classList.add('clamp');
    annot.after(h('button', { class: 'link-btn', onclick: (e) => { annot.style.maxHeight = annot.scrollHeight + 'px'; annot.classList.remove('clamp'); annot.addEventListener('transitionend', () => { annot.style.maxHeight = 'none'; }, { once: true }); e.currentTarget.remove(); } }, 'Читать полностью'));
  }
  const fillChapter = (content) => {
    if (!(p > 0 && p < 100) || !content) return;
    const ch = content.chapters[book.progress ? book.progress.ch || 0 : 0];
    const t = ch && ch.title && ch.title.trim();
    if (t) { progText.textContent = t; progText.title = t; }
  };
  DB.get('content', book.id).then((c) => {
    if (!c) return; fillChapter(c);
    const n = c.chapters.reduce((a, x) => a + (x.len || 0), 0);
    if (book.chars !== n) { book.chars = n; DB.put('books', book).catch(() => {}); }
    fillFacts(n);
  }).catch(() => {});

  // find the cell this book lives in, for the shared cover flight
  const cellCover = () => {
    const c = srcEl && srcEl.isConnected ? srcEl : null;
    const cv = c && (c.matches('.cover') ? c : c.querySelector('.cover'));
    if (!cv || !cv.isConnected) return null;
    const r = cv.getBoundingClientRect();
    return r.width && r.bottom > 0 && r.top < innerHeight ? cv : null;
  };
  const phone = () => innerWidth < 720;
  let openingFlight = null;
  s = openSheet({ title: '', label: book.title, body, tall: true, cls: 'book-sheet', closeBtn: false,
    onClose: () => {
      if (openingFlight) { openingFlight.cancelFlight(); openingFlight = null; }
      const target = phone() && cellCover();
      if (!target || reduceMotion()) return;
      flyCover(heroCover, target, { duration: 460, easing: 'cubic-bezier(.2,.75,.25,1)', hide: [heroCover, target] });
    } });
  const from = phone() && srcEl ? cellCover() : null;
  if (from) {
    // measure where the hero cover will rest once the sheet has settled
    s.panel.classList.add('flying');
    const pr = s.panel.getBoundingClientRect(), hr = heroCover.getBoundingClientRect();
    const dy = pr.top - (innerHeight - s.panel.offsetHeight);
    const rest = { left: hr.left, top: hr.top - dy, width: hr.width, height: hr.height, bottom: hr.bottom - dy };
    const a = openingFlight = flyCover(from, rest, { duration: 460, easing: 'cubic-bezier(.2,.75,.25,1)', hide: [heroCover, from] });
    // the cover arrives by flight, so its own entrance animation must never start afterwards
    heroCover.parentNode.style.animation = 'none';
    if (!a) { s.panel.classList.remove('flying'); heroCover.parentNode.style.animation = ''; }
  }
}
function bookMenu(book, sheet) {
  const m = openSheet({
    title: book.title,
    body: h('div', { class: 'group menu-list' },
      h('button', { onclick: () => { m.close(true); sheet.close(true); openMetaEditor(book); } }, icon('pen'), 'Редактировать'),
      book.noFile ? null : h('button', { onclick: () => { m.close(true); exportBook(book); } }, icon('down'), 'Скачать файл'),
      h('button', { class: 'danger', onclick: () => { m.close(true); removeBook(book, sheet); } }, icon('trash'), 'Удалить книгу')),
  });
}

async function removeBook(book, sheet) {
  const ok = await confirmSheet({ title: 'Удалить книгу?', text: `«${book.title}» и все правки в ней будут удалены с этого устройства. Это нельзя отменить.`, ok: 'Удалить книгу', danger: true });
  if (!ok) return;
  try {
    await flushLocalState(); await Recovery.capture('safety');
    await DB.writeBatch([{ store: 'books', delete: book.id }, { store: 'content', delete: book.id }]);
    state.books = state.books.filter((b) => b.id !== book.id);
    dropCoverUrl(book.id);
    if (sheet) sheet.close(true);
    renderLibrary();
    toast(`«${book.title}» удалена`);
  } catch (e) { toast('Не удалось удалить книгу: ' + e.message, { tone: 'error' }); }
}

async function persistBook(book) {
  try { await DB.put('books', book); } catch (e) { toast('Не удалось сохранить: ' + (e.name === 'QuotaExceededError' ? 'на устройстве не хватает места' : e.message), { tone: 'error' }); throw e; }
}

