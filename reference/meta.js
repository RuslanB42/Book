/* ================= metadata editor ================= */
async function normalizeCover(file) {
  if (/^image\/(jpeg|png)$/.test(file.type) && file.size < 3 * 1048576) return file;
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, 1600 / bmp.height);
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * scale); c.height = Math.round(bmp.height * scale);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  return new Promise((res, rej) => c.toBlob((b) => (b ? res(b) : rej(new Error('не удалось обработать изображение'))), 'image/jpeg', 0.9));
}
function openMetaEditor(book) {
  let newCover = null, previewUrl = '';
  const f = (id, label, value, opts = {}) => {
    const input = h(opts.area ? 'textarea' : 'input', { id, name: id, autocomplete: 'off', inputmode: opts.inputmode, list: opts.list, maxlength: opts.max || null });
    input.value = value || '';
    return h('div', { class: 'field' }, h('label', { for: id }, label), input, opts.hint ? h('span', { class: 'hint' }, opts.hint) : null, h('span', { class: 'err', hidden: true }));
  };
  const coverBox = h('div', null, coverEl(book));
  const fileIn = h('input', { type: 'file', accept: 'image/*', hidden: true, id: 'meta-cover-file' });
  const resetBtn = h('button', { class: 'link-btn', hidden: true, onclick: () => { newCover = null; if (previewUrl) URL.revokeObjectURL(previewUrl); coverBox.replaceChildren(coverEl(book)); resetBtn.hidden = true; } }, 'Вернуть прежнюю');
  fileIn.addEventListener('change', async () => {
    const file = fileIn.files[0]; fileIn.value = '';
    if (!file) return;
    try {
      newCover = await normalizeCover(file);
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = URL.createObjectURL(newCover);
      coverBox.replaceChildren(h('span', { class: 'cover' }, h('img', { src: previewUrl, alt: 'Новая обложка' })));
      resetBtn.hidden = false;
    } catch (e) { toast('Это изображение не открылось. Подойдут JPEG, PNG или WebP.', { tone: 'error' }); }
  });
  const shelfList = h('datalist', { id: 'shelf-list' }, shelves().map(([n]) => h('option', { value: n })));
  const form = h('form', { novalidate: true },
    h('div', { class: 'cover-edit' }, coverBox, h('div', { class: 'btns' },
      h('button', { type: 'button', class: 'btn ghost', onclick: () => fileIn.click() }, 'Выбрать обложку'), resetBtn, fileIn)),
    f('m-title', 'Название', book.title, { max: 300 }),
    f('m-authors', 'Авторы', book.authors.join(', '), { hint: 'Несколько авторов — через запятую' }),
    h('div', { class: 'two' }, f('m-series', 'Серия', book.series), f('m-no', 'Номер', book.seriesNo, { inputmode: 'decimal' })),
    f('m-shelf', 'Полка', book.shelf, { list: 'shelf-list', hint: 'Выберите существующую или впишите новую' }), shelfList,
    f('m-genres', 'Жанры', (book.genres || []).map(genreName).join(', '), { hint: 'Через запятую' }),
    f('m-lang', 'Язык', book.lang, { hint: 'Код языка: ru, en, de…', max: 12 }),
    f('m-annot', 'Аннотация', book.annotation, { area: true }),
    h('button', { type: 'submit', hidden: true }));
  const save = async () => {
    const v = (id) => $('#' + id, form).value.trim();
    const titleField = $('#m-title', form).closest('.field');
    if (!v('m-title')) {
      titleField.setAttribute('data-invalid', ''); const err = $('.err', titleField); err.hidden = false; err.textContent = 'Введите название книги';
      $('#m-title', form).focus(); return;
    }
    const split = (s) => s.split(/[,;]/).map((x) => x.trim()).filter(Boolean);
    const genresIn = split(v('m-genres')).map((g) => (book.format === 'fb2' ? GENRE_BY_NAME[g.toLowerCase()] || g : g));
    Object.assign(book, { title: v('m-title'), authors: split(v('m-authors')), series: v('m-series'), seriesNo: v('m-no'), shelf: v('m-shelf') || 'Разное', genres: genresIn, lang: v('m-lang') || 'ru', annotation: $('#m-annot', form).value.trim(), modifiedAt: Date.now() });
    if (newCover) { book.cover = newCover; book.coverChanged = true; dropCoverUrl(book.id); }
    try { await persistBook(book); } catch (e) { return; }
    s.close(true);
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    renderLibrary();
    toast('Данные книги сохранены');
    openDetail(book);
  };
  form.addEventListener('submit', (e) => { e.preventDefault(); save(); });
  form.addEventListener('input', (e) => { const fl = e.target.closest('.field'); if (fl && fl.hasAttribute('data-invalid')) { fl.removeAttribute('data-invalid'); $('.err', fl).hidden = true; } });
  const s = openSheet({
    title: 'Данные книги', tall: true, body: form,
    left: h('button', { class: 'txt-btn', onclick: () => s.close() }, 'Отмена'),
    right: h('button', { class: 'txt-btn strong', onclick: save }, 'Готово'),
    onClose: () => { if (previewUrl) URL.revokeObjectURL(previewUrl); },
  });
}

