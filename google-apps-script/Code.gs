/**
 * Прайс-чекер: приём остатков инвентаризации от телефонов продавцов.
 *
 * Скрипт привязывается к вашей Google-таблице (Расширения → Apps Script).
 * Каждый телефон добавляет в лист «Инвентаризация» свой столбец «Остатки <имя продавца>»,
 * а строки товаров находит по служебному ключу (штрихкод + наименование).
 * Несколько продавцов могут работать одновременно: запись идёт по очереди (блокировка).
 *
 * ВАЖНО: замените SECRET на свой секретный ключ и вставьте такой же ключ в настройки приложения.
 */
const SECRET = 'ЗАМЕНИТЕ_НА_СВОЙ_КЛЮЧ';
const SHEET_NAME = 'Инвентаризация';
const SELLER_PREFIX = 'Остатки ';
const FIXED_HEADERS = ['Ключ (служебный)', 'Папка (группа)', 'Наименование', 'Штрихкод', 'Цена', 'Остаток в учёте'];

/** Проверка в браузере: откройте адрес /exec — должно появиться «работает». */
function doGet() {
  return out_({ ok: true, message: 'Скрипт инвентаризации работает' });
}

function doPost(e) {
  let req;
  try {
    req = JSON.parse(e.postData.contents);
  } catch (err) {
    return out_({ ok: false, error: 'Некорректный запрос' });
  }
  if (SECRET === 'ЗАМЕНИТЕ_НА_СВОЙ_КЛЮЧ') {
    return out_({ ok: false, error: 'В скрипте не заменён секретный ключ (SECRET)' });
  }
  if (String(req.secret || '') !== SECRET) {
    return out_({ ok: false, error: 'Неверный секретный ключ' });
  }

  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
  } catch (err) {
    return out_({ ok: false, error: 'Таблица занята другим телефоном, повторите попытку' });
  }
  try {
    const sheet = getSheet_();
    if (req.action === 'ping') {
      return out_({ ok: true, message: 'Соединение установлено, лист «' + SHEET_NAME + '» найден' });
    }
    if (req.action === 'upsert') {
      const seller = String(req.seller || '').trim();
      if (!seller) return out_({ ok: false, error: 'Не указано имя продавца' });
      const n = upsert_(sheet, seller, req.items || []);
      return out_({ ok: true, message: 'Записано позиций: ' + n });
    }
    return out_({ ok: false, error: 'Неизвестное действие' });
  } catch (err) {
    return out_({ ok: false, error: String(err && err.message ? err.message : err) });
  } finally {
    lock.releaseLock();
  }
}

function out_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function getSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(SHEET_NAME);
  if (!sh) sh = ss.insertSheet(SHEET_NAME);
  if (sh.getLastRow() === 0) {
    // ключ и штрихкод храним как текст, чтобы Google не превращал их в числа
    sh.getRange('A:A').setNumberFormat('@');
    sh.getRange('D:D').setNumberFormat('@');
    sh.getRange(1, 1, 1, FIXED_HEADERS.length).setValues([FIXED_HEADERS])
      .setFontWeight('bold').setBackground('#dce3ec');
    sh.setFrozenRows(1);
    sh.setFrozenColumns(3);
    sh.hideColumns(1);
  }
  return sh;
}

/** Записывает позиции в столбец продавца; возвращает число обработанных позиций. */
function upsert_(sh, seller, items) {
  const header = SELLER_PREFIX + seller;
  const lastCol = Math.max(sh.getLastColumn(), FIXED_HEADERS.length);
  const heads = sh.getRange(1, 1, 1, lastCol).getValues()[0];
  let col = heads.indexOf(header) + 1;
  if (col === 0) {
    col = lastCol + 1;
    if (col > sh.getMaxColumns()) sh.insertColumnsAfter(sh.getMaxColumns(), col - sh.getMaxColumns());
    sh.getRange(1, col).setValue(header).setFontWeight('bold').setBackground('#e6f2e3');
  }

  let lastRow = sh.getLastRow();
  const rowOf = new Map();
  if (lastRow >= 2) {
    const keys = sh.getRange(2, 1, lastRow - 1, 1).getValues();
    for (let i = 0; i < keys.length; i++) rowOf.set(String(keys[i][0]), i + 2);
  }

  // новые товары — новые строки в конце таблицы
  const newRows = [];
  for (const it of items) {
    const k = String(it.key);
    if (!rowOf.has(k)) {
      rowOf.set(k, lastRow + 1 + newRows.length);
      newRows.push([k, it.folder || '', it.name || '', String(it.barcode || ''),
        it.price === null || it.price === undefined ? '' : it.price,
        it.system === null || it.system === undefined ? '' : it.system]);
    }
  }
  if (newRows.length > 0) {
    const need = lastRow + newRows.length;
    if (need > sh.getMaxRows()) sh.insertRowsAfter(sh.getMaxRows(), need - sh.getMaxRows());
    sh.getRange(lastRow + 1, 1, newRows.length, FIXED_HEADERS.length).setValues(newRows);
    lastRow = need;
  }

  // остатки — одним блоком в столбец продавца
  const counted = items.filter(function (it) { return it.counted !== null && it.counted !== undefined; });
  if (counted.length > 0 && lastRow >= 2) {
    const rng = sh.getRange(2, col, lastRow - 1, 1);
    const vals = rng.getValues();
    for (const it of counted) vals[rowOf.get(String(it.key)) - 2][0] = Number(it.counted);
    rng.setValues(vals);
  }
  return items.length;
}
