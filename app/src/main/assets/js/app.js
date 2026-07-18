(() => {
  'use strict';

  const DB_KEY = 'carta-wallet-cards';
  const isAndroid = typeof Android !== 'undefined';
  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];
  const i18n = window.CartaI18n;
  const t = (key, params) => i18n.t(key, params);

  const CATEGORY_KEYS = {
    fedelta: 'category.loyalty',
    trasporti: 'category.ticket',
    documenti: 'category.document',
    altro: 'category.other'
  };

  const FORMAT_KEYS = {
    ean13: 'format.ean13', ean8: 'format.ean8', upc: 'format.upc', upce: 'format.upce',
    code128: 'format.code128', code39: 'format.code39', code93: 'format.code93',
    codabar: 'format.codabar', itf: 'format.itf',
    qr: 'format.qr', pdf417: 'format.pdf417', aztec: 'format.aztec',
    datamatrix: 'format.datamatrix'
  };

  const categoryLabel = category => t(CATEGORY_KEYS[category] || 'category.card');
  const formatLabel = format => FORMAT_KEYS[format] ? t(FORMAT_KEYS[format]) : '';

  const JSBARCODE_FORMATS = {
    ean13: 'EAN13', ean8: 'EAN8', code128: 'CODE128',
    code39: 'CODE39', upc: 'UPC', itf: 'ITF'
  };
  const BWIP_BCID = {
    code128: 'code128', upce: 'upce', code93: 'code93ext', codabar: 'rationalizedCodabar',
    pdf417: 'pdf417', aztec: 'azteccode', datamatrix: 'datamatrix'
  };

  const BACKUP_SCHEMA = 'loya-wallet-backup';
  const BACKUP_VERSION = 1;
  const BACKUP_LIMITS = Object.freeze({
    fileBytes: 64 * 1024 * 1024,
    cards: 1000,
    photos: 2000,
    photosPerCard: 50,
    imageBytes: 12 * 1024 * 1024,
    totalImageBytes: 56 * 1024 * 1024,
    imageSide: 6000,
    imagePixels: 16 * 1024 * 1024,
    sourceImageBytes: 32 * 1024 * 1024,
    sourceImageSide: 16000,
    sourceImagePixels: 80 * 1024 * 1024,
    nameLength: 200,
    codeLength: 16384,
    notesLength: 20000
  });
  const BACKUP_CATEGORIES = new Set(['fedelta', 'trasporti', 'documenti', 'altro']);
  const BACKUP_FORMATS = new Set(['ean13', 'ean8', 'upc', 'upce', 'code128', 'code39', 'code93', 'codabar', 'itf', 'qr', 'pdf417', 'aztec', 'datamatrix']);

  function backupError(code) {
    const error = new Error(code);
    error.code = code;
    return error;
  }

  function isBackupObject(value) {
    return value !== null && typeof value === 'object' && Object.prototype.toString.call(value) === '[object Object]';
  }

  function backupString(value, maxLength, allowEmpty = true) {
    if (typeof value !== 'string' || value.length > maxLength || (!allowEmpty && !value.trim())) {
      throw backupError('invalid');
    }
    return value;
  }

  function base64Bytes(encoded) {
    let binary;
    try { binary = atob(encoded); }
    catch (_) { throw backupError('invalid'); }
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  function asciiAt(bytes, offset, length) {
    if (offset < 0 || offset + length > bytes.length) return '';
    let value = '';
    for (let index = 0; index < length; index += 1) value += String.fromCharCode(bytes[offset + index]);
    return value;
  }

  function readUint16BE(bytes, offset) {
    return offset + 1 < bytes.length ? (bytes[offset] << 8) | bytes[offset + 1] : 0;
  }

  function readUint24LE(bytes, offset) {
    return offset + 2 < bytes.length ? bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) : -1;
  }

  function readUint32BE(bytes, offset) {
    if (offset + 3 >= bytes.length) return 0;
    return ((bytes[offset] * 0x1000000) + (bytes[offset + 1] << 16) + (bytes[offset + 2] << 8) + bytes[offset + 3]) >>> 0;
  }

  function readUint32LE(bytes, offset) {
    if (offset + 3 >= bytes.length) return -1;
    return (bytes[offset] + bytes[offset + 1] * 0x100 + bytes[offset + 2] * 0x10000 + bytes[offset + 3] * 0x1000000) >>> 0;
  }

  function jpegDimensions(bytes) {
    let offset = 2;
    const startOfFrame = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
    while (offset + 3 < bytes.length) {
      while (offset < bytes.length && bytes[offset] === 0xff) offset += 1;
      if (offset >= bytes.length) break;
      const marker = bytes[offset++];
      if (marker === 0xd9 || marker === 0xda) break;
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue;
      const length = readUint16BE(bytes, offset);
      if (length < 2 || offset + length > bytes.length) return null;
      if (startOfFrame.has(marker)) {
        if (length < 7) return null;
        return { width: readUint16BE(bytes, offset + 5), height: readUint16BE(bytes, offset + 3) };
      }
      offset += length;
    }
    return null;
  }

  function webpDimensions(bytes) {
    const declaredSize = readUint32LE(bytes, 4);
    if (declaredSize < 4 || declaredSize + 8 !== bytes.length) return null;
    let offset = 12;
    while (offset + 8 <= bytes.length) {
      const type = asciiAt(bytes, offset, 4);
      const size = readUint32LE(bytes, offset + 4);
      const data = offset + 8;
      if (size < 0 || data + size > bytes.length) return null;
      if (type === 'VP8X' && size >= 10) {
        return { width: readUint24LE(bytes, data + 4) + 1, height: readUint24LE(bytes, data + 7) + 1 };
      }
      if (type === 'VP8 ' && size >= 10 && asciiAt(bytes, data + 3, 3) === '\u009d\u0001\u002a') {
        return { width: readUint16BE(new Uint8Array([bytes[data + 7], bytes[data + 6]]), 0) & 0x3fff,
          height: readUint16BE(new Uint8Array([bytes[data + 9], bytes[data + 8]]), 0) & 0x3fff };
      }
      if (type === 'VP8L' && size >= 5 && bytes[data] === 0x2f) {
        return {
          width: 1 + bytes[data + 1] + ((bytes[data + 2] & 0x3f) << 8),
          height: 1 + (bytes[data + 2] >> 6) + (bytes[data + 3] << 2) + ((bytes[data + 4] & 0x0f) << 10)
        };
      }
      offset = data + size + (size & 1);
    }
    return null;
  }

  function encodedImageDimensions(mime, bytes) {
    if (mime === 'jpeg') return jpegDimensions(bytes);
    if (mime === 'png') {
      if (bytes.length < 33 || asciiAt(bytes, 12, 4) !== 'IHDR') return null;
      return { width: readUint32BE(bytes, 16), height: readUint32BE(bytes, 20) };
    }
    return webpDimensions(bytes);
  }

  function validateBackupImage(dataUrl) {
    if (typeof dataUrl !== 'string') throw backupError('invalid');
    const match = /^data:image\/(jpeg|png|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(dataUrl);
    if (!match) throw backupError('invalid');
    const mime = match[1];
    const encoded = match[2];
    if (encoded.length % 4 !== 0) throw backupError('invalid');
    const padding = encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0;
    const bytes = (encoded.length / 4) * 3 - padding;
    if (bytes <= 0 || bytes > BACKUP_LIMITS.imageBytes) throw backupError('tooLarge');

    const decoded = base64Bytes(encoded);
    if (decoded.length !== bytes) throw backupError('invalid');
    const byte = index => decoded[index];
    const validMagic = mime === 'jpeg'
      ? byte(0) === 0xff && byte(1) === 0xd8 && byte(2) === 0xff
      : mime === 'png'
        ? byte(0) === 0x89 && asciiAt(decoded, 1, 3) === 'PNG' && byte(4) === 0x0d && byte(5) === 0x0a && byte(6) === 0x1a && byte(7) === 0x0a
        : asciiAt(decoded, 0, 4) === 'RIFF' && asciiAt(decoded, 8, 4) === 'WEBP';
    if (!validMagic) throw backupError('invalid');
    const dimensions = encodedImageDimensions(mime, decoded);
    if (!dimensions || dimensions.width <= 0 || dimensions.height <= 0) throw backupError('invalid');
    if (dimensions.width > BACKUP_LIMITS.imageSide || dimensions.height > BACKUP_LIMITS.imageSide ||
      dimensions.width * dimensions.height > BACKUP_LIMITS.imagePixels) throw backupError('tooLarge');
    return { dataUrl, bytes, mime, width: dimensions.width, height: dimensions.height };
  }

  async function decodeBackupImage(checked) {
    const encoded = checked.dataUrl.slice(checked.dataUrl.indexOf(',') + 1);
    const blob = new Blob([base64Bytes(encoded)], { type: `image/${checked.mime}` });
    let width = 0;
    let height = 0;
    if (typeof createImageBitmap === 'function') {
      let bitmap;
      try {
        bitmap = await createImageBitmap(blob);
        width = bitmap.width;
        height = bitmap.height;
      } catch (_) {
        throw backupError('invalid');
      } finally {
        bitmap?.close?.();
      }
    } else {
      const url = URL.createObjectURL(blob);
      try {
        const dimensions = await new Promise((resolve, reject) => {
          const image = new Image();
          image.onload = () => resolve({ width: image.naturalWidth, height: image.naturalHeight });
          image.onerror = () => reject(backupError('invalid'));
          image.src = url;
        });
        width = dimensions.width;
        height = dimensions.height;
      } finally {
        URL.revokeObjectURL(url);
      }
    }
    if (width <= 0 || height <= 0 || width > BACKUP_LIMITS.imageSide || height > BACKUP_LIMITS.imageSide ||
      width * height > BACKUP_LIMITS.imagePixels) throw backupError('tooLarge');
  }

  async function validateDecodedBackupImages(checked) {
    for (const card of checked.cards) {
      for (const photo of card.photos) await decodeBackupImage(validateBackupImage(photo));
    }
    return checked;
  }

  function validateBackupPayload(payload) {
    if (!isBackupObject(payload)) throw backupError('invalid');
    if (payload.schema !== BACKUP_SCHEMA) throw backupError('invalid');
    if (payload.version !== BACKUP_VERSION) throw backupError('unsupported');
    if (typeof payload.exportedAt !== 'string' || payload.exportedAt.length > 64 || !Number.isFinite(Date.parse(payload.exportedAt))) {
      throw backupError('invalid');
    }
    if (!Array.isArray(payload.cards) || payload.cards.length > BACKUP_LIMITS.cards) throw backupError('tooLarge');

    const ids = new Set();
    let imageCount = 0;
    let imageBytes = 0;
    const cleanCards = payload.cards.map(card => {
      if (!isBackupObject(card)) throw backupError('invalid');
      const id = backupString(card.id, 128, false);
      if (!/^[A-Za-z0-9_-]+$/.test(id) || ids.has(id)) throw backupError('invalid');
      ids.add(id);
      const name = backupString(card.name, BACKUP_LIMITS.nameLength, false);
      const category = backupString(card.category, 20, false);
      if (!BACKUP_CATEGORIES.has(category)) throw backupError('invalid');
      const code = backupString(card.code, BACKUP_LIMITS.codeLength);
      const format = backupString(card.format, 20);
      if (category === 'documenti') {
        if (code || format) throw backupError('invalid');
      } else if (!code || !BACKUP_FORMATS.has(format) || !isValidForFormat(code, format)) {
        throw backupError('invalid');
      }
      const color = backupString(card.color, 7, false);
      if (!/^#[0-9A-Fa-f]{6}$/.test(color)) throw backupError('invalid');
      const notes = backupString(card.notes, BACKUP_LIMITS.notesLength);
      if (!Array.isArray(card.photos) || card.photos.length > BACKUP_LIMITS.photosPerCard) throw backupError('tooLarge');
      const photos = card.photos.map(photo => {
        const checked = validateBackupImage(photo);
        imageCount += 1;
        imageBytes += checked.bytes;
        if (imageCount > BACKUP_LIMITS.photos || imageBytes > BACKUP_LIMITS.totalImageBytes) throw backupError('tooLarge');
        return checked.dataUrl;
      });
      if (!Number.isSafeInteger(card.createdAt) || card.createdAt < 0) throw backupError('invalid');
      return { id, name, category, code, format, color, notes, photos, createdAt: card.createdAt };
    });

    return { cards: cleanCards, cardCount: cleanCards.length, imageCount, imageBytes };
  }

  function parseBackupText(text) {
    if (typeof text !== 'string' || text.length > BACKUP_LIMITS.fileBytes) throw backupError('tooLarge');
    let payload;
    try { payload = JSON.parse(text); }
    catch (_) { throw backupError('invalid'); }
    return validateBackupPayload(payload);
  }

  window.LoyaBackupValidation = Object.freeze({
    schema: BACKUP_SCHEMA,
    version: BACKUP_VERSION,
    limits: BACKUP_LIMITS,
    validateImage: validateBackupImage,
    validateDecodedImages: validateDecodedBackupImages,
    validate: validateBackupPayload,
    parse: parseBackupText
  });

  function loadCards() {
    try { return JSON.parse(localStorage.getItem(DB_KEY) || '[]'); }
    catch (_) { return []; }
  }

  let cards = isAndroid ? [] : normaliseCards(loadCards());
  let currentFilter = 'all';
  let currentCardId = null;
  let editingCardId = null;
  let currentFormKind = 'card';
  let photosData = [];
  let originalPhotosData = [];
  let formNewMedia = new Set();
  let scannerStream = null;
  let scannerAnimation = null;
  let scannerBusy = false;
  let documentScannerBusy = false;
  let toastTimer = null;
  let pendingBackupImport = null;
  let backupImportBusy = false;

  const cardsList = $('#cards-list');
  const emptyState = $('#empty-state');
  const searchInput = $('#search-input');
  const modalAdd = $('#modal-add');
  const modalScanner = $('#modal-scanner');
  const modalDetail = $('#modal-detail');
  const modalConfirm = $('#modal-confirm');
  const modalProject = $('#modal-project');
  const modalBackupImport = $('#modal-backup-import');

  function normaliseCards(input) {
    if (!Array.isArray(input)) return [];
    return input.filter(Boolean).map((card) => ({
      id: card.id || genId(),
      name: String(card.name || t('name.unnamed')),
      category: card.category || 'altro',
      code: String(card.code || ''),
      format: card.format || (card.code ? smartDetectFormat(String(card.code), null) : 'code128'),
      color: card.color || '#123C34',
      notes: String(card.notes || ''),
      photos: Array.isArray(card.photos) ? card.photos.filter(Boolean) : (card.photo ? [card.photo] : []),
      createdAt: card.createdAt || Date.now()
    }));
  }

  window._injectCards = function(json) {
    try { cards = normaliseCards(JSON.parse(json)); }
    catch (_) { cards = []; }
    migrateLegacyImages();
    render();
  };

  function migrateLegacyImages() {
    if (!isAndroid || typeof Android.storeImage !== 'function') return;
    let changed = false;
    cards.forEach(card => {
      card.photos = card.photos.map(src => {
        if (typeof src !== 'string' || !src.startsWith('data:image/')) return src;
        const stored = Android.storeImage(src);
        if (stored) { changed = true; return stored; }
        return src;
      });
    });
    if (changed) save();
  }

  function save() {
    const json = JSON.stringify(cards);
    localStorage.setItem(DB_KEY, json);
    if (isAndroid && typeof Android.saveData === 'function') Android.saveData(DB_KEY, json);
  }

  function genId() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function escHtml(value) {
    const node = document.createElement('div');
    node.textContent = value == null ? '' : String(value);
    return node.innerHTML;
  }

  function escAttr(value) {
    return escHtml(value).replace(/`/g, '&#96;');
  }

  function cardPhotos(card) {
    return Array.isArray(card.photos) ? card.photos : (card.photo ? [card.photo] : []);
  }

  function isLocalMedia(src) {
    if (typeof src !== 'string') return false;
    try {
      const url = new URL(src);
      return url.protocol === 'https:'
        && url.hostname === 'media.cartawallet.local'
        && !url.port && !url.username && !url.password
        && !url.search && !url.hash
        && /^\/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(url.pathname);
    } catch (_) {
      return false;
    }
  }

  function deleteLocalMedia(src) {
    if (isAndroid && isLocalMedia(src) && typeof Android.deleteImage === 'function') {
      Android.deleteImage(src);
    }
  }

  function render() {
    const query = searchInput.value.toLocaleLowerCase(i18n.language).trim();
    const filtered = cards.filter(card => {
      const categoryMatch = currentFilter === 'all' || card.category === currentFilter;
      const haystack = `${card.name} ${card.code || ''} ${card.notes || ''}`.toLocaleLowerCase(i18n.language);
      return categoryMatch && (!query || haystack.includes(query));
    });

    cardsList.querySelectorAll('.wallet-item,.no-results').forEach(node => node.remove());
    cardsList.classList.toggle('document-grid', currentFilter === 'documenti');
    $('#view-eyebrow').textContent = t('library.eyebrow');
    $('#view-title').textContent = currentFilter === 'documenti' ? t('library.documents') : currentFilter === 'trasporti' ? t('library.tickets') : t('library.wallet');
    searchInput.placeholder = currentFilter === 'documenti' ? t('search.documents') : currentFilter === 'trasporti' ? t('search.tickets') : t('search.wallet');
    $('#items-count').textContent = `${cards.length} ${t(cards.length === 1 ? 'count.item' : 'count.items')}`;

    const walletEmpty = cards.length === 0;
    emptyState.classList.toggle('hidden', !walletEmpty);
    if (walletEmpty) {
      $('#empty-title').textContent = t('empty.title');
      $('#empty-copy').textContent = t('empty.copy');
      $('#empty-action').textContent = t(currentFilter === 'documenti' ? 'empty.document' : 'empty.first');
    }

    if (!walletEmpty && filtered.length === 0) {
      const noResults = document.createElement('p');
      noResults.className = 'no-results';
      noResults.textContent = currentFilter === 'documenti'
        ? t('results.noDocuments')
        : currentFilter === 'trasporti' ? t('results.noTickets') : t('results.none');
      cardsList.appendChild(noResults);
      return;
    }

    filtered.forEach(card => {
      const isDocument = card.category === 'documenti';
      const item = document.createElement('article');
      item.className = isDocument ? 'document-item wallet-item' : 'card-item wallet-item';
      item.dataset.id = card.id;

      if (isDocument) {
        const photos = cardPhotos(card);
        const preview = photos.length
          ? `<img src="${escAttr(photos[0])}" alt="${escAttr(t('photo.preview', { name: card.name }))}">`
          : `<span class="document-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/></svg></span>`;
        item.innerHTML = `<div class="document-preview">${preview}</div><div class="document-info"><h3>${escHtml(card.name)}</h3><span>${photos.length} ${t(photos.length === 1 ? 'count.page' : 'count.pages')}</span></div>`;
      } else {
        item.style.setProperty('--card-color', card.color);
        item.innerHTML = `
          <div class="card-top">
            <span class="card-monogram">${escHtml(card.name.charAt(0))}</span>
            <span class="card-copy"><h3>${escHtml(card.name)}</h3><span>${categoryLabel(card.category)}</span></span>
            <svg class="card-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
          </div>
          <div class="card-code-preview"><span class="fake-bars"></span><small>${escHtml(card.code)}</small></div>`;
      }
      item.addEventListener('click', () => openDetail(card.id));
      cardsList.appendChild(item);
    });
  }

  $$('.tab').forEach(tab => tab.addEventListener('click', () => {
    $$('.tab').forEach(item => item.classList.remove('active'));
    tab.classList.add('active');
    currentFilter = tab.dataset.cat;
    render();
  }));
  searchInput.addEventListener('input', render);

  $('#btn-quick-card').addEventListener('click', () => openAdd('card', true));
  $('#btn-quick-document').addEventListener('click', () => openAdd('document', true));
  $('#btn-add').addEventListener('click', () => openAdd(currentFilter === 'documenti' ? 'document' : 'card', false));
  $('#empty-action').addEventListener('click', () => openAdd(currentFilter === 'documenti' ? 'document' : 'card', true));
  $('#btn-project').addEventListener('click', () => modalProject.classList.remove('hidden'));
  $('.close-project').addEventListener('click', () => modalProject.classList.add('hidden'));
  modalProject.addEventListener('click', event => {
    if (event.target === modalProject) modalProject.classList.add('hidden');
  });

  function backupFileName() {
    return `loya-backup-${new Date().toISOString().slice(0, 10)}.json`;
  }

  function setBackupExportBusy(busy) {
    const button = $('#btn-export-backup');
    button.disabled = busy;
    button.textContent = t(busy ? 'backup.exporting' : 'backup.export');
  }

  function backupToastKey(error) {
    if (error?.code === 'tooLarge') return 'toast.backupTooLarge';
    if (error?.code === 'unsupported') return 'toast.backupUnsupported';
    return 'toast.backupReadError';
  }

  async function portablePwaPhoto(photo) {
    try {
      const checked = validateBackupImage(photo);
      await decodeBackupImage(checked);
      return checked.dataUrl;
    } catch (_) {
      if (typeof photo !== 'string' || !/^data:image\//i.test(photo)) throw backupError('invalid');
      return compressImage(photo);
    }
  }

  async function pwaBackupPayload() {
    const portableCards = [];
    for (const card of cards) {
      const photos = [];
      for (const photo of cardPhotos(card)) photos.push(await portablePwaPhoto(photo));
      portableCards.push({
        id: card.id,
        name: card.name,
        category: card.category,
        code: card.code || '',
        format: card.format || '',
        color: card.color,
        notes: card.notes || '',
        photos,
        createdAt: card.createdAt
      });
    }
    const payload = {
      schema: BACKUP_SCHEMA,
      version: BACKUP_VERSION,
      exportedAt: new Date().toISOString(),
      source: 'pwa',
      cards: portableCards
    };
    const checked = validateBackupPayload(payload);
    await validateDecodedBackupImages(checked);
    payload.cards = checked.cards;
    return payload;
  }

  function downloadBackup(payload) {
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    if (blob.size > BACKUP_LIMITS.fileBytes) throw backupError('tooLarge');
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = backupFileName();
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
  }

  $('#btn-export-backup').addEventListener('click', async () => {
    setBackupExportBusy(true);
    if (isAndroid && typeof Android.exportBackup === 'function') {
      let started = false;
      try {
        started = Android.exportBackup(JSON.stringify(cards), new Date().toISOString(), backupFileName()) === true;
      } catch (_) {}
      if (!started) {
        setBackupExportBusy(false);
        showToast(t('toast.backupError'));
      }
      return;
    }

    try {
      downloadBackup(await pwaBackupPayload());
      showToast(t('toast.backupCreated'));
    } catch (error) {
      showToast(t(error?.code === 'tooLarge' ? 'toast.backupTooLarge' : 'toast.backupError'));
    } finally {
      setBackupExportBusy(false);
    }
  });

  window._onNativeBackupExported = function() {
    setBackupExportBusy(false);
    showToast(t('toast.backupCreated'));
  };
  window._onNativeBackupExportCancelled = function() {
    setBackupExportBusy(false);
    showToast(t('toast.backupCancelled'));
  };
  window._onNativeBackupExportError = function() {
    setBackupExportBusy(false);
    showToast(t('toast.backupError'));
  };

  function readBackupFile(file) {
    if (typeof file.text === 'function') return file.text();
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result || ''));
      reader.onerror = () => reject(reader.error || new Error('read'));
      reader.readAsText(file, 'UTF-8');
    });
  }

  function updateBackupImportSummary() {
    if (!pendingBackupImport) return;
    $('#backup-import-summary').textContent = t('backup.summary', {
      items: pendingBackupImport.cardCount,
      images: pendingBackupImport.imageCount
    });
  }

  function closeBackupImport() {
    pendingBackupImport = null;
    modalBackupImport.classList.add('hidden');
    $('#btn-confirm-import').disabled = false;
    $('#btn-cancel-import').disabled = false;
  }

  $('#btn-import-backup').addEventListener('click', () => {
    if (!backupImportBusy) $('#backup-file-input').click();
  });
  $('#backup-file-input').addEventListener('change', async event => {
    const input = event.currentTarget;
    const file = input.files?.[0];
    if (!file) return;
    if (backupImportBusy) {
      input.value = '';
      return;
    }
    backupImportBusy = true;
    $('#btn-import-backup').disabled = true;
    try {
      if (file.size > BACKUP_LIMITS.fileBytes) throw backupError('tooLarge');
      pendingBackupImport = await validateDecodedBackupImages(parseBackupText(await readBackupFile(file)));
      updateBackupImportSummary();
      modalBackupImport.classList.remove('hidden');
    } catch (error) {
      pendingBackupImport = null;
      showToast(t(backupToastKey(error)));
    } finally {
      input.value = '';
      backupImportBusy = false;
      $('#btn-import-backup').disabled = false;
    }
  });

  $('#btn-cancel-import').addEventListener('click', closeBackupImport);
  function persistRestoredCards(restoredCards, previousCards) {
    const restoredJson = JSON.stringify(restoredCards);
    const previousJson = JSON.stringify(previousCards);
    const previousLocalValue = localStorage.getItem(DB_KEY);
    try {
      localStorage.setItem(DB_KEY, restoredJson);
      if (isAndroid && typeof Android.saveData === 'function' && Android.saveData(DB_KEY, restoredJson) !== true) {
        throw backupError('storage');
      }
    } catch (error) {
      try {
        if (previousLocalValue === null) localStorage.removeItem(DB_KEY);
        else localStorage.setItem(DB_KEY, previousLocalValue);
      } catch (_) {}
      if (isAndroid && typeof Android.saveData === 'function') {
        try { Android.saveData(DB_KEY, previousJson); } catch (_) {}
      }
      throw error;
    }
  }

  $('#btn-confirm-import').addEventListener('click', () => {
    if (!pendingBackupImport) return;
    const confirmButton = $('#btn-confirm-import');
    const cancelButton = $('#btn-cancel-import');
    confirmButton.disabled = true;
    cancelButton.disabled = true;

    const previousCards = cards;
    const newlyStoredMedia = [];
    let restoredCards;
    try {
      restoredCards = pendingBackupImport.cards.map(card => ({
        ...card,
        photos: card.photos.map(dataUrl => {
          if (!isAndroid) return dataUrl;
          if (typeof Android.storeImage !== 'function') throw backupError('storage');
          const stored = Android.storeImage(dataUrl);
          if (!isLocalMedia(stored)) throw backupError('storage');
          newlyStoredMedia.push(stored);
          return stored;
        })
      }));

      persistRestoredCards(restoredCards, previousCards);
    } catch (error) {
      newlyStoredMedia.forEach(photo => {
        try { deleteLocalMedia(photo); } catch (_) {}
      });
      showToast(t(error?.name === 'QuotaExceededError' ? 'toast.backupStorageFull' : 'toast.backupRestoreError'));
      confirmButton.disabled = false;
      cancelButton.disabled = false;
      return;
    }

    // Persistence is committed at this point. Cleanup and painting are intentionally
    // best-effort: a cosmetic or file-removal error must never report a failed restore.
    cards = restoredCards;
    pendingBackupImport = null;
    modalBackupImport.classList.add('hidden');
    previousCards.forEach(card => cardPhotos(card).forEach(photo => {
      try { deleteLocalMedia(photo); } catch (_) {}
    }));
    try { render(); } catch (_) {}
    showToast(t('toast.backupRestored'));
    confirmButton.disabled = false;
    cancelButton.disabled = false;
  });

  function updateLanguageControls() {
    $('#btn-language').textContent = i18n.language === 'it' ? 'EN' : 'IT';
    $$('.language-options [data-language]').forEach(button => {
      button.classList.toggle('active', button.dataset.language === i18n.language);
      button.setAttribute('aria-pressed', String(button.dataset.language === i18n.language));
    });
  }

  $('#btn-language').addEventListener('click', () => i18n.toggle());
  $$('.language-options [data-language]').forEach(button => {
    button.addEventListener('click', () => i18n.setLanguage(button.dataset.language));
  });

  window.addEventListener('carta:languagechange', () => {
    updateLanguageControls();
    updateBackupImportSummary();
    setBackupExportBusy($('#btn-export-backup').disabled);
    render();
    setFormKind(currentFormKind);
    renderPhotoThumbs();
    setScanBusy(scannerBusy);
    setDocumentScanBusy(documentScannerBusy);
    setDetectedFormat($('#card-format').value);
    if (!modalDetail.classList.contains('hidden') && currentCardId) openDetail(currentCardId);
  });

  function openAdd(kind, startScan) {
    editingCardId = null;
    resetForm();
    setFormKind(kind);
    modalAdd.classList.remove('hidden');
    if (startScan) setTimeout(() => kind === 'document' ? startDocumentScan() : startBarcodeScan(), 180);
  }

  function closeAdd(commit = false) {
    if (!commit) {
      formNewMedia.forEach(deleteLocalMedia);
    }
    formNewMedia.clear();
    modalAdd.classList.add('hidden');
    resetForm();
  }

  $('.close-add').addEventListener('click', () => closeAdd(false));
  modalAdd.addEventListener('click', event => { if (event.target === modalAdd) closeAdd(false); });

  $$('.entry-type').forEach(button => button.addEventListener('click', () => setFormKind(button.dataset.kind)));
  function setFormKind(kind) {
    currentFormKind = kind;
    const isDocument = kind === 'document';
    $$('.entry-type').forEach(button => button.classList.toggle('active', button.dataset.kind === kind));
    $$('.card-only').forEach(node => node.classList.toggle('hidden', isDocument));
    $('#document-presets').classList.toggle('hidden', !isDocument);
    $('#btn-scan-document').classList.toggle('hidden', !isDocument);
    $('#photo-label').textContent = t(isDocument ? 'form.documentPages' : 'form.imagesOptional');
    $('#form-eyebrow').textContent = t(isDocument ? 'form.newDocumentEyebrow' : 'form.newCardEyebrow');
    $('#form-title').textContent = editingCardId ? t('form.edit') : t(isDocument ? 'form.archiveDocument' : 'form.add');
    $('#btn-save').textContent = editingCardId ? t('form.saveChanges') : t(isDocument ? 'form.saveDocument' : 'form.saveCardTicket');
    $('#card-name').placeholder = t(isDocument ? 'form.documentNamePlaceholder' : 'form.cardNamePlaceholder');
    if (isDocument) $('#card-category').value = 'fedelta';
  }

  $$('#document-presets button').forEach(button => button.addEventListener('click', () => {
    $('#card-name').value = t(`preset.${button.dataset.preset}`);
  }));

  $$('.color-dot').forEach(dot => dot.addEventListener('click', () => {
    $$('.color-dot').forEach(item => item.classList.remove('active'));
    dot.classList.add('active');
  }));

  function resetForm() {
    $('#card-name').value = '';
    $('#card-code').value = '';
    $('#card-category').value = 'fedelta';
    $('#card-notes').value = '';
    $('#card-photo-input').value = '';
    setDetectedFormat('');
    photosData = [];
    originalPhotosData = [];
    renderPhotoThumbs();
    $$('.color-dot').forEach((dot, index) => dot.classList.toggle('active', index === 0));
    setScanBusy(false);
    setDocumentScanBusy(false);
  }

  function renderPhotoThumbs() {
    const container = $('#photos-thumbs');
    container.innerHTML = '';
    photosData.forEach((src, index) => {
      const wrap = document.createElement('div');
      wrap.className = 'photo-thumb';
      const pageLabel = index === 0 ? t('photo.front') : index === 1 ? t('photo.back') : t('photo.page', { number: index + 1 });
      wrap.innerHTML = `<img src="${escAttr(src)}" alt="${escAttr(t('photo.page', { number: index + 1 }))}"><button class="photo-thumb-remove" data-index="${index}" aria-label="${escAttr(t('photo.remove'))}">×</button><span class="photo-thumb-label">${escHtml(pageLabel)}</span>`;
      container.appendChild(wrap);
    });
    container.classList.toggle('hidden', photosData.length === 0);
    $('#photo-count').textContent = photosData.length ? `${photosData.length} ${t(photosData.length === 1 ? 'count.image' : 'count.images')}` : '';
    container.querySelectorAll('.photo-thumb-remove').forEach(button => button.addEventListener('click', event => {
      event.stopPropagation();
      const index = Number(button.dataset.index);
      const removed = photosData.splice(index, 1)[0];
      if (formNewMedia.has(removed)) {
        deleteLocalMedia(removed);
        formNewMedia.delete(removed);
      }
      renderPhotoThumbs();
    }));
  }

  $('#btn-add-photo').addEventListener('click', () => $('#card-photo-input').click());
  $('#card-photo-input').addEventListener('change', async event => {
    const file = event.target.files && event.target.files[0];
    if (!file) return;
    try {
      if (file.size <= 0 || file.size > BACKUP_LIMITS.sourceImageBytes) throw backupError('tooLarge');
      const source = await readFileAsDataUrl(file);
      photosData.push(await compressImage(source));
      renderPhotoThumbs();
    } catch (_) {
      showToast(t('toast.imageReadError'));
    }
    event.target.value = '';
  });

  function readFileAsDataUrl(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
  }

  function compressImage(dataUrl) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => {
        if (img.naturalWidth <= 0 || img.naturalHeight <= 0 ||
          img.naturalWidth > BACKUP_LIMITS.sourceImageSide || img.naturalHeight > BACKUP_LIMITS.sourceImageSide ||
          img.naturalWidth * img.naturalHeight > BACKUP_LIMITS.sourceImagePixels) {
          reject(backupError('tooLarge'));
          return;
        }
        const maxSide = 1800;
        const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
        canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
        const context = canvas.getContext('2d', { alpha: false });
        if (!context) { reject(backupError('invalid')); return; }
        context.fillStyle = '#ffffff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(img, 0, 0, canvas.width, canvas.height);
        try {
          const normalized = canvas.toDataURL('image/jpeg', .86);
          validateBackupImage(normalized);
          resolve(normalized);
        } catch (error) {
          reject(error);
        }
      };
      img.onerror = () => reject(backupError('invalid'));
      img.src = dataUrl;
    });
  }

  $('#btn-scan').addEventListener('click', startBarcodeScan);
  $('#card-code').addEventListener('input', () => {
    const value = $('#card-code').value.trim();
    setDetectedFormat(value ? smartDetectFormat(value, null) : '');
  });

  function setScanBusy(busy) {
    scannerBusy = busy;
    $('#btn-scan').classList.toggle('busy', busy);
    $('#btn-scan span').textContent = t(busy ? 'form.scanning' : 'form.scan');
    $('#scan-status').textContent = t(busy ? 'form.cameraOpen' : 'form.codeHint');
  }

  function startBarcodeScan() {
    if (scannerBusy) return;
    setScanBusy(true);
    if (isAndroid && typeof Android.scanBarcode === 'function') {
      Android.scanBarcode();
      return;
    }
    modalScanner.classList.remove('hidden');
    startWebScanner();
  }

  window._onNativeBarcodeScan = function(value, format) {
    setScanBusy(false);
    onCodeScanned(String(value || ''), format || 'code128');
  };
  window._onNativeBarcodeScanCancelled = function() { setScanBusy(false); };

  async function startWebScanner() {
    const video = $('#scanner-video');
    try {
      scannerStream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: { ideal: 'environment' },
          width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 }
        }, audio: false
      });
      video.srcObject = scannerStream;
      await video.play();

      if (typeof BarcodeDetector === 'undefined') throw new Error('BarcodeDetector unavailable');
      let formats = ['ean_13','ean_8','code_128','code_39','code_93','codabar','upc_a','upc_e','itf','qr_code','pdf417','aztec','data_matrix'];
      if (BarcodeDetector.getSupportedFormats) {
        const supported = await BarcodeDetector.getSupportedFormats();
        formats = formats.filter(format => supported.includes(format));
      }
      const detector = new BarcodeDetector({ formats });
      let processing = false;
      let lastAnalysis = 0;
      const analyse = async timestamp => {
        if (!scannerStream) return;
        scannerAnimation = requestAnimationFrame(analyse);
        if (processing || timestamp - lastAnalysis < 90 || video.readyState < 2) return;
        processing = true;
        lastAnalysis = timestamp;
        try {
          const results = await detector.detect(video);
          const decoded = results.find(item => item.rawValue);
          if (decoded) onCodeScanned(decoded.rawValue, mapBarcodeFormat(decoded.format));
        } catch (_) {}
        processing = false;
      };
      scannerAnimation = requestAnimationFrame(analyse);
    } catch (_) {
      stopScanner();
      modalScanner.classList.add('hidden');
      setScanBusy(false);
      showToast(t('toast.cameraUnavailable'));
    }
  }

  function onCodeScanned(code, format) {
    stopScanner();
    modalScanner.classList.add('hidden');
    if (format === 'codabar' && /^[0-9\-\$:/.+]+$/i.test(code)) code = `A${code}A`;
    const detected = smartDetectFormat(code, format);
    $('#card-code').value = code;
    setDetectedFormat(detected);
    const ticketDetected = looksLikeTicket(code, detected);
    if (ticketDetected) $('#card-category').value = 'trasporti';
    setScanBusy(false);
    showToast(t(ticketDetected ? 'toast.ticketRecognized' : 'toast.codeRecognized'));
    if (isAndroid && typeof Android.vibrate === 'function') Android.vibrate(80);
    else if (navigator.vibrate) navigator.vibrate(80);
  }

  function looksLikeTicket(code, format) {
    if (format === 'pdf417' || format === 'aztec') return true;
    return /\b(boarding|flight|ticket|bigliett|prenotazione|booking|pnr|trenitalia|italotreno|trainline)\b/i.test(code)
      || /^M1[A-Z0-9 ]{20,}/.test(code);
  }

  function stopScanner() {
    if (scannerAnimation) cancelAnimationFrame(scannerAnimation);
    scannerAnimation = null;
    if (scannerStream) scannerStream.getTracks().forEach(track => track.stop());
    scannerStream = null;
  }

  $('.close-scanner').addEventListener('click', () => {
    stopScanner(); modalScanner.classList.add('hidden'); setScanBusy(false);
  });
  modalScanner.addEventListener('click', event => {
    if (event.target === modalScanner) {
      stopScanner(); modalScanner.classList.add('hidden'); setScanBusy(false);
    }
  });

  $('#btn-scan-document').addEventListener('click', startDocumentScan);
  function setDocumentScanBusy(busy) {
    documentScannerBusy = busy;
    const button = $('#btn-scan-document');
    button.classList.toggle('busy', busy);
    const label = button.querySelector('strong');
    if (label) label.textContent = t(busy ? 'form.openingScanner' : 'quick.document.title');
  }

  function startDocumentScan() {
    setDocumentScanBusy(true);
    if (isAndroid && typeof Android.scanDocument === 'function') {
      Android.scanDocument();
    } else {
      setDocumentScanBusy(false);
      $('#card-photo-input').click();
    }
  }

  window._onNativeDocumentScan = function(urls) {
    setDocumentScanBusy(false);
    if (!Array.isArray(urls)) return;
    urls.filter(Boolean).forEach(url => {
      photosData.push(url);
      formNewMedia.add(url);
    });
    renderPhotoThumbs();
    showToast(urls.length === 1 ? t('toast.documentCaptured') : t('toast.pagesCaptured', { count: urls.length }));
  };
  window._onNativeDocumentScanError = function(message) {
    setDocumentScanBusy(false);
    if (message) showToast(message);
  };
  window._onNativeDocumentScanCancelled = function() { setDocumentScanBusy(false); };

  function persistPhoto(src) {
    if (!isAndroid || !src.startsWith('data:image/') || typeof Android.storeImage !== 'function') return src;
    return Android.storeImage(src) || src;
  }

  $('#btn-save').addEventListener('click', () => {
    const name = $('#card-name').value.trim();
    const isDocument = currentFormKind === 'document';
    const code = isDocument ? '' : $('#card-code').value.trim();
    if (!name) { shake($('#card-name')); showToast(t('toast.nameRequired')); return; }
    if (!isDocument && !code) { shake($('#card-code')); showToast(t('toast.codeRequired')); return; }
    if (isDocument && photosData.length === 0) {
      shake($('#btn-scan-document'));
      showToast(t('toast.imageRequired'));
      return;
    }

    const persistedPhotos = photosData.map(persistPhoto);
    const category = isDocument ? 'documenti' : $('#card-category').value;
    const format = isDocument ? '' : ($('#card-format').value || smartDetectFormat(code, null));
    const color = $('.color-dot.active')?.dataset.color || '#123C34';
    const notes = $('#card-notes').value.trim();
    const existing = cards.find(card => card.id === editingCardId);

    if (existing) {
      const removed = originalPhotosData.filter(src => !persistedPhotos.includes(src));
      removed.forEach(deleteLocalMedia);
      Object.assign(existing, { name, code, category, format, color, notes, photos: persistedPhotos });
    } else {
      cards.push({ id: genId(), name, code, category, format, color, notes, photos: persistedPhotos, createdAt: Date.now() });
    }

    formNewMedia.clear();
    save();
    render();
    closeAdd(true);
    showToast(t(existing ? 'toast.changesSaved' : (isDocument ? 'toast.documentSaved' : 'toast.cardSaved')));
  });

  function shake(element) {
    element.style.animation = 'none';
    element.offsetHeight;
    element.style.animation = 'shake .3s';
    setTimeout(() => { element.style.animation = ''; }, 500);
  }

  function isDigits(value) { return /^\d+$/.test(value); }
  function eanChecksumValid(code) {
    if (!isDigits(code)) return false;
    const digits = code.split('').map(Number);
    const check = digits.pop();
    let sum = 0;
    digits.reverse().forEach((number, index) => { sum += number * (index % 2 === 0 ? 3 : 1); });
    return ((10 - (sum % 10)) % 10) === check;
  }

  function isValidForFormat(code, format) {
    if (!code) return false;
    switch (format) {
      case 'ean13': return /^\d{13}$/.test(code) && eanChecksumValid(code);
      case 'ean8': return /^\d{8}$/.test(code) && eanChecksumValid(code);
      case 'upc': return /^\d{12}$/.test(code) && eanChecksumValid(code);
      case 'upce': return upceChecksumValid(code);
      case 'itf': return isDigits(code) && code.length % 2 === 0;
      case 'code39': return /^[0-9A-Z\-. $\/+%]+$/.test(code);
      case 'code93': return /^[\x00-\x7F]+$/.test(code);
      case 'codabar': return /^[A-D][0-9\-\$:/.+]+[A-D]$/i.test(code);
      case 'code128': case 'qr': case 'pdf417': case 'aztec': case 'datamatrix': return true;
      default: return false;
    }
  }

  function upceChecksumValid(code) {
    if (!/^\d{7,8}$/.test(code)) return false;
    const numberSystem = code[0];
    if (numberSystem !== '0' && numberSystem !== '1') return false;
    if (code.length === 7) return true;
    const full = code;
    const value = full.slice(1, 7);
    const check = full[7];
    let expanded;
    switch (value[5]) {
      case '0': case '1': case '2':
        expanded = numberSystem + value.slice(0, 2) + value[5] + '0000' + value.slice(2, 5) + check;
        break;
      case '3':
        expanded = numberSystem + value.slice(0, 3) + '00000' + value.slice(3, 5) + check;
        break;
      case '4':
        expanded = numberSystem + value.slice(0, 4) + '00000' + value[4] + check;
        break;
      default:
        expanded = numberSystem + value.slice(0, 5) + '0000' + value[5] + check;
    }
    return eanChecksumValid(expanded);
  }

  function smartDetectFormat(code, hint) {
    if (hint && isValidForFormat(code, hint)) return hint;
    if (/^\d{13}$/.test(code) && eanChecksumValid(code)) return 'ean13';
    if (/^\d{12}$/.test(code) && eanChecksumValid(code)) return 'upc';
    if (/^\d{8}$/.test(code) && eanChecksumValid(code)) return 'ean8';
    if (isDigits(code) && code.length % 2 === 0) return 'itf';
    return 'code128';
  }

  function mapBarcodeFormat(format) {
    return ({
      ean_13: 'ean13', ean_8: 'ean8', code_128: 'code128', code_39: 'code39',
      code_93: 'code93', codabar: 'codabar', upc_a: 'upc', upc_e: 'upce',
      itf: 'itf', qr_code: 'qr',
      pdf417: 'pdf417', aztec: 'aztec', data_matrix: 'datamatrix'
    })[format] || 'code128';
  }

  function setDetectedFormat(format) {
    const known = FORMAT_KEYS[format] ? format : '';
    $('#card-format').value = known;
    $('#format-badge-text').textContent = known ? formatLabel(known) : t('form.automatic');
    $('#format-badge').classList.toggle('detected', Boolean(known));
  }

  function tryRenderQr(container, code) {
    if (typeof QRCode === 'undefined') return false;
    try {
      const wrap = document.createElement('div');
      wrap.className = 'qr-wrap';
      container.appendChild(wrap);
      new QRCode(wrap, { text: code, width: 320, height: 320, colorDark: '#000', colorLight: '#fff', correctLevel: QRCode.CorrectLevel.M });
      return true;
    } catch (_) { container.innerHTML = ''; return false; }
  }

  function tryRenderBarcode(container, code, format) {
    if (typeof JsBarcode === 'undefined') return false;
    try {
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      JsBarcode(svg, code, {
        format: JSBARCODE_FORMATS[format] || 'CODE128', width: 3, height: 112,
        displayValue: false, background: '#fff', lineColor: '#000', margin: 10,
        valid: valid => { if (!valid) throw new Error('invalid'); }
      });
      container.innerHTML = '';
      container.appendChild(svg);
      return true;
    } catch (_) { return false; }
  }

  let bwipPromise = null;
  function ensureBwip() {
    if (typeof bwipjs !== 'undefined') return Promise.resolve(true);
    if (bwipPromise) return bwipPromise;
    bwipPromise = new Promise(resolve => {
      const script = document.createElement('script');
      script.src = 'js/bwip-js-min.js';
      script.onload = () => resolve(true);
      script.onerror = () => { bwipPromise = null; resolve(false); };
      document.head.appendChild(script);
    });
    return bwipPromise;
  }

  async function tryRenderMatrix(container, code, format) {
    if (!BWIP_BCID[format] || !await ensureBwip() || typeof bwipjs === 'undefined') return false;
    try {
      let encodedText = code;
      if (format === 'codabar') {
        encodedText = encodedText.toUpperCase();
        if (!/^[A-D]/.test(encodedText)) encodedText = `A${encodedText}`;
        if (!/[A-D]$/.test(encodedText)) encodedText = `${encodedText}A`;
      }
      const wrap = document.createElement('div');
      wrap.className = 'matrix-wrap';
      wrap.innerHTML = bwipjs.toSVG({ bcid: BWIP_BCID[format], text: encodedText, scale: 4, backgroundcolor: 'ffffff', paddingwidth: 8, paddingheight: 8 });
      container.innerHTML = '';
      container.appendChild(wrap);
      return true;
    } catch (_) { return false; }
  }

  async function renderBarcode(container, code, format) {
    container.innerHTML = '';
    if (format === 'qr' && tryRenderQr(container, code)) return 'qr';
    if (BWIP_BCID[format]) {
      if (await tryRenderMatrix(container, code, format)) return format;
      return tryRenderQr(container, code) ? 'qr' : null;
    }
    const candidates = [];
    if (JSBARCODE_FORMATS[format]) candidates.push(format);
    const detected = smartDetectFormat(code, null);
    if (!candidates.includes(detected)) candidates.push(detected);
    if (!candidates.includes('code128')) candidates.push('code128');
    for (const candidate of candidates) if (tryRenderBarcode(container, code, candidate)) return candidate;
    return null;
  }

  function openDetail(id) {
    const card = cards.find(item => item.id === id);
    if (!card) return;
    currentCardId = id;
    const isDocument = card.category === 'documenti';
    const detail = $('.detail-content');
    detail.classList.toggle('detail-document', isDocument);
    detail.style.setProperty('--detail-color', isDocument ? '#edece6' : card.color);
    $('#detail-name').textContent = card.name;
    $('#detail-category').textContent = categoryLabel(card.category);
    $('.barcode-pass').classList.toggle('hidden', isDocument);
    $('.brightness-hint').classList.toggle('hidden', isDocument);
    $('#detail-code').textContent = card.code || '';
    $('#barcode-display').innerHTML = '';

    if (!isDocument && card.code) {
      renderBarcode($('#barcode-display'), card.code, card.format);
    }

    const photoContainer = $('#detail-photo-container');
    photoContainer.innerHTML = '';
    const photos = cardPhotos(card);
    photos.forEach((src, index) => {
      const label = document.createElement('p');
      label.className = 'detail-photo-label';
      label.textContent = index === 0 ? t('photo.front') : index === 1 ? t('photo.back') : t('photo.page', { number: index + 1 });
      const img = document.createElement('img');
      img.src = src;
      img.alt = label.textContent;
      img.className = 'detail-photo-img';
      img.addEventListener('click', () => openPhotoZoom(src, label.textContent));
      photoContainer.append(label, img);
    });
    photoContainer.classList.toggle('hidden', photos.length === 0);
    $('#detail-notes').textContent = card.notes || '';
    $('#detail-notes').classList.toggle('hidden', !card.notes);
    modalDetail.classList.remove('hidden');
    if (!isDocument && isAndroid && typeof Android.setMaxBrightness === 'function') Android.setMaxBrightness();
  }

  function closeDetail() {
    modalDetail.classList.add('hidden');
    if (isAndroid && typeof Android.restoreBrightness === 'function') Android.restoreBrightness();
  }
  $('.back-btn').addEventListener('click', closeDetail);

  $('#btn-edit').addEventListener('click', () => {
    const card = cards.find(item => item.id === currentCardId);
    if (!card) return;
    editingCardId = card.id;
    $('#card-name').value = card.name;
    $('#card-code').value = card.code || '';
    $('#card-category').value = card.category === 'documenti' ? 'fedelta' : card.category;
    $('#card-notes').value = card.notes || '';
    setDetectedFormat(card.format);
    $$('.color-dot').forEach(dot => dot.classList.toggle('active', dot.dataset.color === card.color));
    photosData = cardPhotos(card).slice();
    originalPhotosData = photosData.slice();
    renderPhotoThumbs();
    closeDetail();
    setFormKind(card.category === 'documenti' ? 'document' : 'card');
    modalAdd.classList.remove('hidden');
  });

  $('#btn-delete').addEventListener('click', () => modalConfirm.classList.remove('hidden'));
  $('#btn-cancel-delete').addEventListener('click', () => modalConfirm.classList.add('hidden'));
  $('#btn-confirm-delete').addEventListener('click', () => {
    const card = cards.find(item => item.id === currentCardId);
    if (card) cardPhotos(card).forEach(deleteLocalMedia);
    cards = cards.filter(item => item.id !== currentCardId);
    save();
    render();
    modalConfirm.classList.add('hidden');
    closeDetail();
    showToast(t('toast.deleted'));
  });

  function openPhotoZoom(src, title) {
    const overlay = document.createElement('div');
    overlay.className = 'photo-zoom-overlay';
    overlay.innerHTML = `<div class="photo-zoom-header"><button class="icon-btn photo-zoom-close" aria-label="${escAttr(t('close'))}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg></button><span class="photo-zoom-title">${escHtml(title)}</span><span class="photo-zoom-hint">${escHtml(t('zoom.hint'))}</span></div><div class="photo-zoom-body"><img class="photo-zoom-img" src="${escAttr(src)}" alt="${escAttr(title)}" draggable="false"></div>`;
    document.body.appendChild(overlay);
    const body = overlay.querySelector('.photo-zoom-body');
    const img = overlay.querySelector('.photo-zoom-img');
    const pointers = new Map();
    let scale = 1, tx = 0, ty = 0, startScale = 1, startDistance = 0;
    let startX = 0, startY = 0, startTx = 0, startTy = 0, moved = false, lastTap = 0;

    const apply = () => { img.style.transform = `translate(${tx}px,${ty}px) scale(${scale})`; };
    const clamp = () => {
      const rect = body.getBoundingClientRect();
      const maxX = Math.max(0, (scale - 1) * rect.width / 2);
      const maxY = Math.max(0, (scale - 1) * rect.height / 2);
      tx = Math.max(-maxX, Math.min(maxX, tx));
      ty = Math.max(-maxY, Math.min(maxY, ty));
    };
    const distance = (a,b) => Math.hypot(a.x-b.x,a.y-b.y);
    const reset = () => { scale = 1; tx = 0; ty = 0; apply(); };
    const close = () => overlay.remove();

    body.addEventListener('pointerdown', event => {
      try { body.setPointerCapture(event.pointerId); } catch (_) {}
      pointers.set(event.pointerId, { x:event.clientX, y:event.clientY });
      moved = false; startX = event.clientX; startY = event.clientY; startTx = tx; startTy = ty;
      if (pointers.size === 2) { const pair=[...pointers.values()]; startDistance=distance(pair[0],pair[1]); startScale=scale; }
    });
    body.addEventListener('pointermove', event => {
      if (!pointers.has(event.pointerId)) return;
      pointers.set(event.pointerId, { x:event.clientX, y:event.clientY });
      const pair=[...pointers.values()];
      if (pair.length >= 2) {
        scale=Math.max(1,Math.min(6,startScale*(distance(pair[0],pair[1])/Math.max(1,startDistance))));
        moved=true;
      } else if (scale > 1) {
        tx=startTx+(event.clientX-startX); ty=startTy+(event.clientY-startY);
        if (Math.abs(event.clientX-startX)>5 || Math.abs(event.clientY-startY)>5) moved=true;
      }
      clamp(); apply();
    });
    const pointerUp = event => {
      pointers.delete(event.pointerId);
      if (pointers.size) { const remaining=[...pointers.values()][0]; startX=remaining.x; startY=remaining.y; startTx=tx; startTy=ty; return; }
      if (scale <= 1.02) reset();
      if (moved) return;
      const now=Date.now();
      if (now-lastTap < 300) {
        img.classList.add('animating');
        if (scale > 1.05) reset(); else { scale=3; apply(); }
        setTimeout(()=>img.classList.remove('animating'),220);
        lastTap=0;
      } else lastTap=now;
    };
    body.addEventListener('pointerup', pointerUp);
    body.addEventListener('pointercancel', event => pointers.delete(event.pointerId));
    overlay.querySelector('.photo-zoom-close').addEventListener('click', close);
  }

  function showToast(message) {
    const toast = $('#toast');
    toast.textContent = message;
    toast.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.add('hidden'), 2400);
  }

  window._handleAndroidBack = function() {
    const zoom = $('.photo-zoom-overlay');
    if (zoom) { zoom.remove(); return 'handled'; }
    if (!modalBackupImport.classList.contains('hidden')) { closeBackupImport(); return 'handled'; }
    if (!modalConfirm.classList.contains('hidden')) { modalConfirm.classList.add('hidden'); return 'handled'; }
    if (!modalProject.classList.contains('hidden')) { modalProject.classList.add('hidden'); return 'handled'; }
    if (!modalScanner.classList.contains('hidden')) { stopScanner(); modalScanner.classList.add('hidden'); setScanBusy(false); return 'handled'; }
    if (!modalAdd.classList.contains('hidden')) { closeAdd(false); return 'handled'; }
    if (!modalDetail.classList.contains('hidden')) { closeDetail(); return 'handled'; }
    return 'exit';
  };

  i18n.apply();
  updateLanguageControls();
  render();

  if (!isAndroid && 'serviceWorker' in navigator && /^https?:$/.test(location.protocol)) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./sw.js').catch(() => {});
    });
  }
})();
