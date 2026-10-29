/* ================= all quotes ================= */
let quoteFilter = '';
function allQuotes() {
  const out = [];
  for (const b of state.books) for (const q of b.quotes || []) out.push({ q, b });
  return out.sort((x, y) => (y.q.at || 0) - (x.q.at || 0));
}
const quoteCite = (q, b) => `«${q.text}»\n— ${authorsStr(b)}, «${b.title}»`;
async function copyPlain(text) {
  try { await navigator.clipboard.writeText(text); return true; }
  catch (e) {
    const ta = h('textarea', { style: 'position:fixed;opacity:0;top:0' }); ta.value = text; document.body.append(ta); ta.select();
    let ok = false; try { ok = document.execCommand('copy'); } catch (err) { ok = false; } ta.remove(); return ok;
  }
}
function quoteCard(q, b, i, withBook) {
  const card = h('article', { class: 'qcard', style: `--i:${Math.min(i, 10)}` });
  const open = () => openReader(b, null, { ch: q.ch, quote: q.start >= 0 ? q.id : null, block: q.block });
  const when = fmtDate(q.at || Date.now()).replace(/ \d{4} г\.?$/, '');
  card.append(
    h('button', { class: 'q-body', onclick: open, 'aria-label': `Открыть цитату в книге «${b.title}»` },
      h('span', { class: 'q-mark', 'aria-hidden': 'true' }, '“'),
      h('span', { class: 'q-text' }, q.text)),
    ...(b.notes || []).filter(n => n.quoteId === q.id).map(n => h('button', {class:'q-thought',onclick:()=>editNotebookNote({book:b,note:n,onSaved:()=>renderLibrary(false)}),'aria-label':'Изменить мысль к цитате'}, h('small',null,'Моя мысль'),h('span',null,n.text))),
    h('div', { class: 'q-foot' },
      withBook
        ? h('button', { class: 'q-book', onclick: (e) => openDetail(b, e.currentTarget) }, coverEl(b), h('span', null, h('b', null, b.title), h('small', null, `${authorsStr(b)} | ${when}`)))
        : h('span', { class: 'q-when' }, when),
      h('button', { class: 'icon-btn', 'aria-label': 'Копировать цитату', onclick: async () => { toast((await copyPlain(quoteCite(q, b))) ? 'Цитата скопирована' : 'Не удалось скопировать'); } }, icon('copy', 'sm')),
      h('button', { class: 'icon-btn', 'aria-label': 'Удалить цитату', onclick: async () => {
        const idx = b.quotes.indexOf(q);
        if (idx < 0) return;
        try { await card.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.96)' }], { duration: reduceMotion() ? 1 : 200, easing: 'ease-in' }).finished; } catch (e) { /* ignore */ }
        b.quotes.splice(idx, 1); persistBook(b).catch(() => {}); renderLibrary(false);
        toast('Цитата удалена', { action: { label: 'Вернуть', fn: () => { b.quotes.splice(idx, 0, q); persistBook(b).catch(() => {}); renderLibrary(false); } } });
      } }, icon('trash', 'sm'))));
  return card;
}
let quoteBookMotion = null;
function stopQuoteBookMotion() { if (quoteBookMotion) quoteBookMotion(); }
function openQuoteBook(id) {
  if (state.qbook === id) return;
  stopQuoteBookMotion();
  const main = $('#lib-main'), previousId = state.qbook;
  const opening = !!id, oldRect = main.getBoundingClientRect();
  const canAnimate = !reduceMotion() && !document.hidden && typeof main.animate === 'function';
  // Keep the outgoing view as a short-lived, inert visual layer; no duplicated controls.
  const ghost = canAnimate ? main.cloneNode(true) : null;
  if (ghost) {
    ghost.removeAttribute('id'); ghost.querySelectorAll('[id]').forEach(el=>el.removeAttribute('id'));
    ghost.classList.add('quote-motion-ghost'); ghost.inert=true; ghost.setAttribute('aria-hidden','true');
    ghost.style.cssText=`position:fixed;left:${oldRect.left}px;top:${oldRect.top}px;width:${oldRect.width}px;height:${Math.max(0,Math.min(oldRect.height,innerHeight-oldRect.top))}px;margin:0;overflow:hidden;pointer-events:none;z-index:4;`;
  }
  state.navDir = ''; state.qbook = id; renderLibrary(false);
  window.scrollTo({top:0,behavior:'instant'}); libScrollChrome();
  const focusTarget = opening ? main.querySelector('.back-link') : [...main.querySelectorAll('.qgroup')].find(el=>el.dataset.quoteBook===previousId);
  focusTarget?.focus({preventScroll:true});
  if (!canAnimate) return;
  main.classList.add('quote-reveal');
  const animations=[], media=matchMedia('(prefers-reduced-motion: reduce)'); let ended=false,timer;
  const cleanup=()=>{
    if(ended)return;ended=true;clearTimeout(timer);
    animations.forEach(a=>a.cancel());ghost?.remove();main.classList.remove('quote-reveal');
    window.removeEventListener('resize',cleanup);document.removeEventListener('visibilitychange',onVisibility);media.removeEventListener('change',onReduced);
    if(quoteBookMotion===cleanup)quoteBookMotion=null;
  };
  const onVisibility=()=>{if(document.hidden)cleanup()},onReduced=e=>{if(e.matches)cleanup()};
  quoteBookMotion=cleanup;
  window.addEventListener('resize',cleanup);document.addEventListener('visibilitychange',onVisibility);media.addEventListener('change',onReduced);
  const run=(el,frames,options={})=>{const a=el.animate(frames,{duration:420,easing:'cubic-bezier(.25,.1,.25,1)',fill:'both',...options});animations.push(a);return a};
  if(ghost){document.body.append(ghost);run(ghost,[{opacity:1,transform:'none'},{opacity:0,transform:`translate3d(${opening?-8:8}px,-3px,0)`}],{duration:190,easing:'ease-out'})}
  const newHeight=main.getBoundingClientRect().height;
  if(Math.abs(oldRect.height-newHeight)>1)run(main,[{height:`${oldRect.height}px`},{height:`${newHeight}px`}],{duration:520});
  main.querySelectorAll(':scope > .sec-head,:scope > .qb-head').forEach((el,i)=>run(el,[{opacity:0,transform:`translate3d(${opening?8:-8}px,6px,0)`},{opacity:1,transform:'none'}],{duration:280,delay:55+i*30}));
  const cards=[...main.querySelectorAll(opening?'.qcard':'.qgroup')];
  cards.forEach((card,i)=>{
    // Bound the stagger; a large collection must never wait seconds to become usable.
    if(card.getBoundingClientRect().top>innerHeight+80)return;
    run(card,[{opacity:0,transform:`translate3d(${opening?10:-10}px,${opening?16:10}px,0)`},{opacity:1,transform:'none'}],{duration:420,delay:80+Math.min(i,7)*32});
  });
  Promise.all(animations.map(a=>a.finished.catch(()=>{}))).then(cleanup);timer=setTimeout(cleanup,850);
}
function renderQuotes(main) {
  const items = allQuotes();
  if (!items.length) {
    state.qbook = '';
    main.append(h('div', { class: 'empty' },
      h('div', { class: 'q-empty-mark', 'aria-hidden': 'true' }, '“'),
      h('h2', null, 'Здесь будут ваши цитаты'),
      h('p', null, 'Во время чтения выделите текст и нажмите «Цитата» — она появится здесь вместе с названием книги.')));
    return;
  }
  // group by book, freshest book first
  const groups = [];
  const byId = new Map();
  for (const it of items) {
    if (!byId.has(it.b.id)) { const g = { b: it.b, list: [] }; byId.set(it.b.id, g); groups.push(g); }
    byId.get(it.b.id).list.push(it.q);
  }
  const g = state.qbook && byId.get(state.qbook);
  if (state.qbook && !g) state.qbook = '';
  if (g) {
    main.append(h('div', { class: 'sec-head' },
      h('button', { class: 'back-link', onclick: () => openQuoteBook('') }, icon('back', 'sm'), 'Цитаты'),
      h('span', null, `${g.list.length} ${plural(g.list.length, ['цитата', 'цитаты', 'цитат'])}`)));
    main.append(h('button', { class: 'qb-head', onclick: (e) => openDetail(g.b, e.currentTarget) }, coverEl(g.b),
      h('span', null, h('b', null, g.b.title), h('small', null, authorsStr(g.b)))));
    const list = h('div', { class: 'q-list' });
    g.list.slice().sort((x, y) => (x.ch - y.ch) || (x.start - y.start)).forEach((q, i) => list.append(quoteCard(q, g.b, i, false)));
    main.append(list);
    return;
  }
  main.append(h('div', { class: 'sec-head' }, h('h2', null, `${items.length} ${plural(items.length, ['цитата', 'цитаты', 'цитат'])}`),
    h('span', null, `из ${groups.length} ${plural(groups.length, ['книги', 'книг', 'книг'])}`)));
  const list = h('div', { class: 'q-list' });
  groups.forEach(({ b, list: qs }, i) => {
    const n = qs.length;
    list.append(h('button', { 'data-quote-book': b.id, class: 'qgroup' + (n > 1 ? ' stacked' : '') + (n > 2 ? ' stacked2' : ''), style: `--i:${Math.min(i, 10)}`, onclick: () => openQuoteBook(b.id), 'aria-label': `${b.title}: ${n} ${plural(n, ['цитата', 'цитаты', 'цитат'])}` },
      h('span', { class: 'qg-card' },
        h('span', { class: 'qg-top' }, coverEl(b),
          h('span', { class: 'qg-meta' }, h('b', null, b.title), h('small', null, authorsStr(b))),
          h('span', { class: 'qg-count' }, String(n), icon('next', 'sm'))),
        h('span', { class: 'qg-q' }, h('span', { class: 'q-mark', 'aria-hidden': 'true' }, '“'), h('span', { class: 'qg-quote' }, qs[0].text)))));
  });
  main.append(list);
}

/* ---------- top bar actions ---------- */
function toggleSearch(force) {
  state.searchOpen = force != null ? force : !state.searchOpen;
  if (!state.searchOpen) { state.query = ''; $('#q').value = ''; $('#q-clear').hidden = true; }
  renderLibrary(false);
  if (state.searchOpen) $('#q').focus({ preventScroll: true });
  else $('#btn-search').focus({ preventScroll: true });
}
function openViewMenu() {
  const opts = [['manual', 'Мой порядок'], ['recent', 'Недавние'], ['title', 'По названию'], ['author', 'По автору']];
  const list = h('ul', { class: 'pick' });
  const paint = () => list.replaceChildren(...opts.map(([k, l]) => h('li', null, h('button', { 'aria-pressed': String(state.sort === k), onclick: () => { state.sort = k; prefs.sort = k; savePrefs(); paint(); renderLibrary(false); } },
    h('span', { class: 'ck', style: 'margin-left:6px' }, icon('check')), h('span', null, h('span', { class: 't' }, l)), h('span')))));
  paint();
  openSheet({
    title: 'Фильтры и сортировка',
    body: h('div', null, h('div', { class: 'group-title' }, 'Фильтры'), recommendationFilter(), h('div', { class: 'group-title' }, 'Сортировка книг'), list),
  });
}

