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

