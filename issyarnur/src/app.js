const listingGrid = document.querySelector('#listingGrid');
const emptyState = document.querySelector('#emptyState');
const dashboard = document.querySelector('#dashboard');
const adminDashboard = document.querySelector('#adminDashboard');
const accountArea = document.querySelector('#accountArea');
const backdrop = document.querySelector('#modalBackdrop');
const modalContent = document.querySelector('#modalContent');
const toastElement = document.querySelector('#toast');
let currentUser = null;
let allListings = [];
let toastTimer;

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' } : {}), ...options.headers }
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'Не удалось выполнить запрос');
  return result;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
}

function formatPrice(value) {
  return new Intl.NumberFormat('ru-RU').format(value) + ' ₽';
}

function notify(message) {
  toastElement.textContent = message;
  toastElement.classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastElement.classList.remove('visible'), 3200);
}

function showModal(content) {
  modalContent.innerHTML = content;
  backdrop.hidden = false;
  document.body.style.overflow = 'hidden';
  const firstInput = modalContent.querySelector('input');
  if (firstInput) firstInput.focus();
}

function closeModal() {
  backdrop.hidden = true;
  document.body.style.overflow = '';
}

function showAuth(mode = 'login') {
  const registering = mode === 'register';
  showModal(`
    <div class="eyebrow">FASTRENT · ЛИЧНЫЙ КАБИНЕТ</div>
    <h2>${registering ? 'Создать аккаунт' : 'С возвращением'}</h2>
    <p class="modal-subtitle">${registering ? 'Сохраните любимые места и управляйте поездками.' : 'Войдите, чтобы продолжить бронирование.'}</p>
    <form id="authForm">
      ${registering ? '<div class="form-field"><label for="authName">Как вас зовут</label><input id="authName" name="name" autocomplete="name" required minlength="2" maxlength="60" placeholder="Ваше имя"></div>' : ''}
      <div class="form-field"><label for="authEmail">Электронная почта</label><input id="authEmail" name="email" type="email" autocomplete="email" required maxlength="254" placeholder="name@example.com"></div>
      <div class="form-field"><label for="authPassword">Пароль</label><input id="authPassword" name="password" type="password" autocomplete="${registering ? 'new-password' : 'current-password'}" required minlength="${registering ? '8' : '1'}" maxlength="128" placeholder="${registering ? 'Не менее 8 символов' : 'Ваш пароль'}"></div>
      ${registering ? '' : '<div class="demo-hint">Демо-вход администратора: <strong>admin@fastrent.ru</strong><br>Пароль: <strong>FastRent2026!</strong></div>'}
      <button class="button button-dark" type="submit">${registering ? 'Зарегистрироваться' : 'Войти в аккаунт'}</button>
    </form>
    <p class="modal-switch">${registering ? 'Уже есть аккаунт?' : 'Впервые на FastRent?'} <button type="button" data-auth="${registering ? 'login' : 'register'}">${registering ? 'Войти' : 'Создать аккаунт'}</button></p>
  `);
}

function renderAccount() {
  if (!currentUser) {
    accountArea.innerHTML = '<button class="button button-quiet" data-auth="login">Войти</button><button class="button button-dark" data-auth="register">Создать аккаунт</button>';
    dashboard.hidden = true;
    adminDashboard.hidden = true;
    return;
  }
  accountArea.innerHTML = `<button class="button button-quiet" data-scroll="dashboard">${escapeHtml(currentUser.name.split(' ')[0])} ↘</button><button class="button button-dark" data-logout>Выйти</button>`;
  dashboard.hidden = currentUser.role === 'ADMIN';
  adminDashboard.hidden = currentUser.role !== 'ADMIN';
}

function renderListings(listings = allListings) {
  document.querySelector('#listingCount').textContent = `${listings.length} ${listings.length === 1 ? 'место' : 'мест'}`;
  emptyState.hidden = listings.length !== 0;
  listingGrid.innerHTML = listings.map(listing => `
    <article class="listing-card">
      <img class="listing-photo" src="${escapeHtml(listing.imageUrl)}" alt="${escapeHtml(listing.title)}" loading="lazy">
      <div class="listing-content">
        <p class="listing-location">${escapeHtml(listing.city)} · ${escapeHtml(listing.address)}</p>
        <h3>${escapeHtml(listing.title)}</h3>
        <div class="listing-meta"><span>♧ ${listing.guests} гостя</span><span>⌂ ${listing.bedrooms} ${listing.bedrooms === 1 ? 'комната' : 'комнаты'}</span></div>
        <p class="listing-description">${escapeHtml(listing.description)}</p>
        <div class="listing-bottom"><div class="listing-price">${formatPrice(listing.price)} <small>/ ночь</small></div><button class="button button-accent button-small" data-book="${escapeHtml(listing.id)}">Выбрать ↗</button></div>
      </div>
    </article>
  `).join('');
  listingGrid.querySelectorAll('img').forEach(image => image.addEventListener('error', () => { image.src = 'https://images.unsplash.com/photo-1600210492486-724fe5c67fb0?auto=format&fit=crop&w=900&q=75'; }, { once: true }));
}

function renderBookings(bookings, isAdmin = false) {
  if (!bookings.length) return '<div class="empty-state">Пока бронирований нет. Самое время выбрать место для поездки.</div>';
  return `<div class="booking-list">${bookings.map(booking => {
    const listing = booking.listing || {};
    const guest = booking.guest || {};
    const cancelled = booking.status === 'CANCELLED';
    const dateLabel = `${escapeHtml(booking.checkIn)} — ${escapeHtml(booking.checkOut)}`;
    return `<article class="booking-row"><div class="booking-info"><h3>${escapeHtml(listing.title || 'Квартира')}</h3><p>${escapeHtml(listing.city || '')} · ${dateLabel} · ${booking.guests} гост. ${isAdmin && guest.name ? `· ${escapeHtml(guest.name)} (${escapeHtml(guest.email)})` : ''}</p></div><div class="booking-side"><strong>${formatPrice(booking.total)}</strong><span class="booking-status ${cancelled ? 'cancelled' : ''}">${cancelled ? 'Отменено' : 'Подтверждено'}</span>${cancelled ? '' : ` <button class="button button-quiet button-small" data-cancel="${escapeHtml(booking.id)}">Отменить</button>`}</div></article>`;
  }).join('')}</div>`;
}

async function loadDashboard() {
  if (!currentUser) return;
  if (currentUser.role === 'ADMIN') {
    const [bookingResult, listingResult] = await Promise.all([api('/api/bookings'), api('/api/admin/listings')]);
    const activeCount = listingResult.listings.filter(item => item.active).length;
    const revenue = bookingResult.bookings.filter(item => item.status !== 'CANCELLED').reduce((total, item) => total + item.total, 0);
    adminDashboard.innerHTML = `
      <div class="dashboard-head"><div><div class="eyebrow">УПРАВЛЕНИЕ СЕРВИСОМ</div><h2>Панель администратора</h2></div><span class="muted">${activeCount} активных квартир · ${bookingResult.bookings.length} бронирований · ${formatPrice(revenue)}</span></div>
      <div class="admin-grid">
        <form id="listingForm" class="admin-form"><h3>Добавить квартиру</h3>
          <div class="form-field"><label>Название</label><input name="title" required maxlength="100" placeholder="Уютная квартира в центре"></div>
          <div class="field-grid"><div class="form-field"><label>Город</label><input name="city" required maxlength="60"></div><div class="form-field"><label>Район / адрес</label><input name="address" required maxlength="120"></div></div>
          <div class="field-grid"><div class="form-field"><label>Цена за ночь, ₽</label><input name="price" type="number" min="500" max="1000000" required></div><div class="form-field"><label>Гостей</label><input name="guests" type="number" min="1" max="20" required></div></div>
          <div class="field-grid"><div class="form-field"><label>Комнат</label><input name="bedrooms" type="number" min="1" max="20" required></div><div class="form-field"><label>Ссылка на фото</label><input name="imageUrl" type="url" required placeholder="https://…"></div></div>
          <div class="form-field"><label>Описание</label><textarea name="description" required maxlength="500" placeholder="Расскажите о квартире"></textarea></div>
          <button class="button button-dark" type="submit">Добавить в каталог</button>
        </form>
        <div class="admin-inventory"><h3>Каталог квартир</h3>${listingResult.listings.map(item => `<div class="admin-listing"><div><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.city)} · ${item.active ? 'Опубликована' : 'Скрыта'}</small></div><button class="button button-quiet button-small" data-toggle="${escapeHtml(item.id)}">${item.active ? 'Скрыть' : 'Опубликовать'}</button></div>`).join('')}</div>
      </div>
      <div class="dashboard-head" style="margin:34px 0 18px"><div><div class="eyebrow">ВСЕ ГОСТИ И ЗАКАЗЫ</div><h2>Бронирования</h2></div></div>${renderBookings(bookingResult.bookings, true)}
    `;
  } else {
    const result = await api('/api/bookings');
    dashboard.innerHTML = `<div class="dashboard-head"><div><div class="eyebrow">ВАШ FASTRENT</div><h2>Привет, ${escapeHtml(currentUser.name)}!</h2></div><span class="muted">${escapeHtml(currentUser.email)}</span></div><h3 style="font:700 16px var(--display);margin:0 0 16px">Ваши поездки</h3>${renderBookings(result.bookings)}`;
  }
}

async function reload() {
  try {
    const [me, listings] = await Promise.all([api('/api/me'), api('/api/listings')]);
    currentUser = me.user;
    allListings = listings.listings;
    renderAccount();
    renderListings();
    await loadDashboard();
  } catch (error) {
    notify(error.message);
  }
}

function openBooking(listingId) {
  if (!currentUser) {
    showAuth('login');
    notify('Войдите или зарегистрируйтесь, чтобы забронировать квартиру');
    return;
  }
  const listing = allListings.find(item => item.id === listingId);
  if (!listing) return;
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const dayAfter = new Date(tomorrow);
  dayAfter.setDate(dayAfter.getDate() + 1);
  const dateValue = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  showModal(`
    <div class="eyebrow">ВАША СЛЕДУЮЩАЯ ПОЕЗДКА</div><h2>Забронировать</h2>
    <p class="modal-subtitle"><strong>${escapeHtml(listing.title)}</strong><br>${escapeHtml(listing.city)} · ${formatPrice(listing.price)} за ночь</p>
    <form id="bookingForm"><input type="hidden" name="listingId" value="${escapeHtml(listing.id)}">
      <div class="field-grid"><div class="form-field"><label>Заезд</label><input type="date" name="checkIn" min="${dateValue(tomorrow)}" value="${dateValue(tomorrow)}" required></div><div class="form-field"><label>Выезд</label><input type="date" name="checkOut" min="${dateValue(dayAfter)}" value="${dateValue(dayAfter)}" required></div></div>
      <div class="form-field"><label>Количество гостей (максимум ${listing.guests})</label><select name="guests">${Array.from({ length: listing.guests }, (_, index) => `<option value="${index + 1}">${index + 1}</option>`).join('')}</select></div>
      <button class="button button-dark" type="submit">Подтвердить бронирование</button>
    </form>
  `);
  const checkIn = modalContent.querySelector('[name="checkIn"]');
  const checkOut = modalContent.querySelector('[name="checkOut"]');
  checkIn.addEventListener('change', () => {
    const next = new Date(`${checkIn.value}T12:00:00`);
    next.setDate(next.getDate() + 1);
    const minCheckout = dateValue(next);
    checkOut.min = minCheckout;
    if (checkOut.value <= checkIn.value) checkOut.value = minCheckout;
  });
}

document.addEventListener('click', async event => {
  const authButton = event.target.closest('[data-auth]');
  if (authButton) return showAuth(authButton.dataset.auth);
  const bookButton = event.target.closest('[data-book]');
  if (bookButton) return openBooking(bookButton.dataset.book);
  const cancelButton = event.target.closest('[data-cancel]');
  if (cancelButton && confirm('Отменить это бронирование?')) {
    try { await api(`/api/bookings/${encodeURIComponent(cancelButton.dataset.cancel)}/cancel`, { method: 'POST', body: '' }); notify('Бронирование отменено'); await reload(); }
    catch (error) { notify(error.message); }
  }
  const toggleButton = event.target.closest('[data-toggle]');
  if (toggleButton) {
    try { await api(`/api/admin/listings/${encodeURIComponent(toggleButton.dataset.toggle)}/toggle`, { method: 'POST', body: '' }); notify('Каталог обновлён'); await reload(); }
    catch (error) { notify(error.message); }
  }
  const scrollButton = event.target.closest('[data-scroll]');
  if (scrollButton) document.querySelector(`#${scrollButton.dataset.scroll}`).scrollIntoView({ behavior: 'smooth' });
  if (event.target.closest('#modalClose') || event.target === backdrop) closeModal();
  if (event.target.closest('[data-logout]')) {
    try { await api('/api/logout', { method: 'POST', body: '' }); currentUser = null; closeModal(); await reload(); notify('Вы вышли из аккаунта'); }
    catch (error) { notify(error.message); }
  }
});

document.addEventListener('submit', async event => {
  const form = event.target;
  if (form.id === 'searchForm') {
    event.preventDefault();
    const city = document.querySelector('#citySearch').value.trim().toLocaleLowerCase('ru');
    const maxPrice = Number(document.querySelector('#priceSearch').value) || Infinity;
    const results = allListings.filter(item => `${item.city} ${item.address}`.toLocaleLowerCase('ru').includes(city) && item.price <= maxPrice);
    renderListings(results);
    document.querySelector('#catalog').scrollIntoView({ behavior: 'smooth' });
  }
  if (form.id === 'authForm') {
    event.preventDefault();
    const registering = Boolean(form.elements.name);
    const payload = new URLSearchParams(new FormData(form));
    try {
      await api(registering ? '/api/register' : '/api/login', { method: 'POST', body: payload });
      closeModal(); await reload(); notify(registering ? 'Аккаунт создан. Добро пожаловать!' : 'Вы вошли в аккаунт');
    } catch (error) { notify(error.message); }
  }
  if (form.id === 'bookingForm') {
    event.preventDefault();
    try { await api('/api/bookings', { method: 'POST', body: new URLSearchParams(new FormData(form)) }); closeModal(); await reload(); notify('Квартира забронирована — хорошей поездки!'); document.querySelector('#dashboard').scrollIntoView({ behavior: 'smooth' }); }
    catch (error) { notify(error.message); }
  }
  if (form.id === 'listingForm') {
    event.preventDefault();
    try { await api('/api/admin/listings', { method: 'POST', body: new URLSearchParams(new FormData(form)) }); form.reset(); await reload(); notify('Квартира добавлена в каталог'); }
    catch (error) { notify(error.message); }
  }
});

document.querySelector('#modalClose').addEventListener('click', closeModal);
document.addEventListener('keydown', event => { if (event.key === 'Escape' && !backdrop.hidden) closeModal(); });
reload();
