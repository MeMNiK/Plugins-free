// E2E-симуляция FoolioTitles: реальный Paper + LuckPerms + боты mineflayer.
import fs from 'node:fs';
import mineflayer from 'mineflayer';

const VERSION = process.env.MC_VERSION || '1.20.4';
const LOG = process.env.SERVER_LOG || 'server/server.log';
const CONSOLE = process.env.SERVER_CONSOLE || 'server/console.in';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const strip = (s) => s.replace(/\x1b\[[0-9;]*m/g, '').replace(/§./g, '');

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`);
}

// ---------------------------------------------------------------- консоль сервера
function logText() { return strip(fs.readFileSync(LOG, 'utf8')); }
async function consoleCmd(cmd, expect, timeout = 6000, settle = 400) {
  const start = logText().length;
  fs.appendFileSync(CONSOLE, cmd + '\n');
  if (!expect) { await sleep(1500); return logText().slice(start); }
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    if (expect.test(logText().slice(start))) { await sleep(settle); return logText().slice(start); }
    await sleep(200);
  }
  return logText().slice(start);
}
const LP_SUFFIX = /500 - /;
async function lpInfo(name) { return consoleCmd(`lp user ${name} meta info`, new RegExp(name), 8000, 1500); }
const CONFIG = process.env.PLUGIN_CONFIG || 'server/plugins/FoolioTitles/config.yml';
async function setCooldown(sec) {
  const c = fs.readFileSync(CONFIG, 'utf8').replace(/submit-cooldown-seconds: \d+/, `submit-cooldown-seconds: ${sec}`);
  fs.writeFileSync(CONFIG, c);
  await consoleCmd('titles reload', /перезагружена/);
}
async function tokens(name) {
  const out = await consoleCmd(`titles token check ${name}`, new RegExp(`Токены игрока ${name}: \\d+`));
  const m = out.match(new RegExp(`Токены игрока ${name}: (\\d+)`));
  return m ? Number(m[1]) : -1;
}

// ---------------------------------------------------------------- боты
const bots = {};
function makeBot(username) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username, version: VERSION, auth: 'offline' });
    bot.msgs = [];
    bot.on('messagestr', (m) => { bot.msgs.push(strip(m)); });
    bot.on('kicked', (r) => console.log(`[${username}] kicked: ${JSON.stringify(r)}`));
    bot.on('error', (e) => console.log(`[${username}] error: ${e.message}`));
    bot.once('spawn', () => { bots[username] = bot; resolve(bot); });
    setTimeout(() => reject(new Error(`${username}: spawn timeout`)), 60000);
  });
}
function mark(bot) { return bot.msgs.length; }
async function waitMsg(bot, re, from = 0, timeout = 6000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    const hit = bot.msgs.slice(from).find((m) => re.test(m));
    if (hit) return hit;
    await sleep(100);
  }
  return null;
}
async function waitWindow(bot, pred, timeout = 6000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    const w = bot.currentWindow;
    if (w && pred(w)) return w;
    await sleep(100);
  }
  return null;
}
const slotName = (w, i) => (w && w.slots[i] ? w.slots[i].name : null);
const slotLabel = (w, i) => {
  const it = w && w.slots[i];
  if (!it) return '';
  try { return JSON.stringify(it.customName ?? it.nbt ?? it.components ?? ''); } catch { return ''; }
};
const isMain = (w) => w.inventoryStart === 27 && slotName(w, 11) === 'anvil' && slotName(w, 13) === 'chest';
const isCreateInput = (w) => w.inventoryStart === 27 && slotName(w, 13) === 'writable_book';
const isCreateDraft = (w) => w.inventoryStart === 27 && slotName(w, 13) === 'name_tag' && slotName(w, 15) === 'lime_concrete';
const isStorage = (w) => w.inventoryStart === 54 && slotName(w, 4) === 'book';
const isReview = (w) => w.inventoryStart === 54 && slotName(w, 4) === 'comparator';
const isReject = (w) => w.inventoryStart === 36 && slotName(w, 31) === 'writable_book';
const isPlayerTitles = (w) => w.inventoryStart === 54 && slotName(w, 4) === 'player_head';

async function click(bot, slot, button = 0, mode = 0) {
  try { await bot.clickWindow(slot, button, mode); } catch (e) { console.log(`click ${slot}/${button}/${mode}: ${e.message}`); }
  await sleep(400);
}
async function closeWin(bot) { if (bot.currentWindow) bot.closeWindow(bot.currentWindow); await sleep(300); }

async function openMain(bot) {
  await closeWin(bot);
  bot.chat('/titles');
  return waitWindow(bot, isMain);
}
/** Главное меню → Создать → ввод текста → возвращает окно (с черновиком или нет). */
async function enterTitle(bot, text, fromDraft = false) {
  let w = bot.currentWindow;
  if (!fromDraft) {
    w = await openMain(bot);
    if (!w) return { error: 'main not opened' };
    await click(bot, 11);
    w = await waitWindow(bot, isCreateInput);
    if (!w) return { error: 'create not opened' };
  }
  const m = mark(bot);
  await click(bot, 13);
  const prompt = await waitMsg(bot, /Напиши титул/, m);
  if (!prompt) return { error: 'no prompt' };
  bot.chat(text);
  await sleep(800);
  return { from: m };
}
async function submitTitle(bot, text) {
  const r = await enterTitle(bot, text);
  if (r.error) return r;
  const w = await waitWindow(bot, isCreateDraft);
  if (!w) return { error: 'no draft window' };
  const m = mark(bot);
  await click(bot, 15);
  return { from: m };
}

// ---------------------------------------------------------------- сценарии
async function main() {
  const A = await makeBot('Admin');
  const W = await makeBot('Watcher');
  await consoleCmd('op Admin', /Made Admin a server operator|already|Admin/);
  await sleep(1000);

  // 1. Токены до первого входа
  let out = await consoleCmd('titles token give Tester 3', /ещё не заходил/);
  check('Токены офлайн-игроку, который не заходил, ставятся в очередь', /ещё не заходил/.test(out), out.trim().split('\n').pop());

  const T = await makeBot('Tester');
  check('Отложенные токены выдаются при входе', await waitMsg(T, /получил 3/, 0, 8000));
  check('Баланс после входа = 3', (await tokens('Tester')) === 3);

  // 2. Главное меню
  let w = await openMain(T);
  check('Главное меню открывается', !!w);
  check('Кнопка админа не видна игроку', w && slotName(w, 22) !== 'comparator', `slot22=${slotName(w, 22)}`);

  // 3. Создание: ввод скрыт от других
  const wm = mark(W);
  let r = await enterTitle(T, '&cТестовый');
  w = await waitWindow(T, isCreateDraft);
  check('После ввода меню открывается снова с превью', !!w);
  check('В превью виден титул', w && /Тестовый/.test(slotLabel(w, 13)), slotLabel(w, 13).slice(0, 120));
  await sleep(500);
  check('Другой игрок НЕ видит введённый титул в чате', !W.msgs.slice(wm).some((m) => /Тестовый/.test(m)));

  // 4. Проверки ввода (каждый раз меню возвращается с прежним черновиком)
  const bad = [
    ["<click:run_command:'/op Tester'>Клик</click>", /неизвестные теги/, 'клик-теги'],
    ['<hover:show_text:x>Ховер</hover>', /неизвестные теги/, 'ховер-теги'],
    ['Админ', /запрещённое слово/, 'запрещённое слово'],
    ['A д м и н', /запрещённое слово/, 'обход пробелами и латиницей'],
    ['ОченьОченьДлинныйТитул', /Слишком длинный/, 'длина'],
    ['%player_ip%', /Плейсхолдеры/, 'PAPI-плейсхолдер'],
    ['{balance}', /Плейсхолдеры/, 'плейсхолдер в фигурных скобках'],
    ['&kМиг', /Мигающий/, 'мигающий текст'],
    ['&&cc', /недопустимые символы/, 'склейка цветовых кодов'],
    ['Т‮ест', /недопустимые символы/, 'RTL-символ'],
    ['отмена', /Ввод отменён/, 'отмена ввода'],
  ];
  const tokBefore = await tokens('Tester');
  for (const [text, re, label] of bad) {
    w = await waitWindow(T, isCreateDraft);
    if (!w) { check(`Ввод: ${label}`, false, 'нет окна черновика'); continue; }
    const res = await enterTitle(T, text, true);
    const hit = res.error ? null : await waitMsg(T, re, res.from);
    const back = await waitWindow(T, isCreateDraft);
    check(`Ввод отклонён: ${label}`, hit && back && /Тестовый/.test(slotLabel(back, 13)), hit || res.error || 'нет сообщения');
  }
  check('Ошибки ввода не тратят токены', (await tokens('Tester')) === tokBefore);

  // 5. Попытки вытащить предметы из меню
  await consoleCmd('give Tester diamond 1', /Gave|Выдан|diamond/i);
  await sleep(800);
  w = await waitWindow(T, isCreateDraft);
  if (w) {
    const diaSlot = w.slots.findIndex((s, i) => i >= w.inventoryStart && s && s.name === 'diamond');
    if (diaSlot >= 0) await click(T, diaSlot, 0, 1); // шифт-клик алмаза в меню
    await click(T, 0, 0, 0);   // взять стекло-заполнитель
    await click(T, 0, 0, 2);   // цифровая клавиша
    await click(T, 13, 0, 4);  // Q по титулу
    await click(T, 15, 0, 2);  // цифра по «отправить» — не должна отправить
    await click(T, 15, 0, 4);  // Q по «отправить»
    await sleep(800);
    w = T.currentWindow;
    const topHasDiamond = w && w.slots.slice(0, w.inventoryStart).some((s) => s && s.name === 'diamond');
    check('Шифт-клик не кладёт предмет игрока в меню', !topHasDiamond);
  }
  await closeWin(T);
  out = await consoleCmd('clear Tester black_stained_glass_pane 0', /No items|Не найдено|found|Найдено/i);
  check('Из меню нельзя вытащить предметы', !/Found [1-9]/i.test(out), out.trim().split('\n').pop());
  out = await consoleCmd('clear Tester diamond 0', /found|Found|Найдено|No items/i);
  check('Алмаз игрока остался у него', /Found 1/i.test(out), out.trim().split('\n').pop());
  check('Цифра/Q по кнопке «Отправить» ничего не делают', !T.msgs.some((m) => /отправлен на проверку/.test(m)));

  // 6. Отправка двойным кликом — списывается 1 токен
  r = await enterTitle(T, '&cТестовый');
  w = await waitWindow(T, isCreateDraft);
  const am = mark(A);
  let m0 = mark(T);
  T.clickWindow(15, 0, 0).catch(() => {});
  T.clickWindow(15, 0, 0).catch(() => {});
  T.clickWindow(15, 0, 0).catch(() => {});
  await sleep(2500);
  const sentCount = T.msgs.slice(m0).filter((m) => /отправлен на проверку/.test(m)).length;
  check('Тройной клик «Отправить» = одна заявка', sentCount === 1, `сообщений: ${sentCount}`);
  check('Списан ровно 1 токен (3 → 2)', (await tokens('Tester')) === 2);
  check('Админ получил уведомление о заявке', await waitMsg(A, /Новая заявка на титул от Tester/, am));
  check('Обычный игрок не получил уведомление админа', !W.msgs.some((m) => /Новая заявка/.test(m)));
  w = await waitWindow(T, isStorage);
  check('После отправки открылось хранилище с заявкой на проверке', w && slotName(w, 10) === 'clock', `slot10=${slotName(w, 10)}`);

  // 7. Вторая заявка, пока первая на проверке
  await sleep(3500); // кулдаун в тестовом конфиге = 3 сек
  r = await submitTitle(T, 'Второй');
  check('Нельзя держать две заявки на проверке', await waitMsg(T, /уже есть заявка/, r.from || 0));
  check('Токен за неудачную вторую заявку не списан', (await tokens('Tester')) === 2);

  // 8. Права: игрок не может админить
  m0 = mark(T);
  await closeWin(T);
  T.chat('/titles admin');
  await sleep(800);
  T.chat('/titles token give Tester 100');
  await sleep(800);
  check('Игрок не может открыть проверку и выдать себе токены', T.msgs.slice(m0).filter((m) => /Недостаточно прав/.test(m)).length === 2);
  check('Баланс не изменился', (await tokens('Tester')) === 2);

  // 9. Отказ №1 — токен возвращается
  await closeWin(A);
  A.chat('/titles admin');
  w = await waitWindow(A, isReview);
  check('Админ видит очередь с заявкой', w && slotName(w, 10) === 'player_head', `slot10=${slotName(w, 10)}`);
  let tm = mark(T);
  await click(A, 10, 1, 0); // ПКМ
  w = await waitWindow(A, isReject);
  check('ПКМ открывает выбор причины', !!w);
  await click(A, 10, 0, 0);
  check('Отказ №1: игрок узнаёт причину, токен возвращён', await waitMsg(T, /отклонён.*Мат или оскорбления.*Токен возвращён/, tm));
  check('Баланс после отказа №1 = 2', (await tokens('Tester')) === 2);
  w = await waitWindow(A, (x) => isReview(x) && slotName(x, 10) !== 'player_head');
  check('Очередь у админа обновилась', !!w);

  // 10. Отказ №2 — токен НЕ возвращается
  await sleep(3500);
  r = await submitTitle(T, 'Плохой');
  await sleep(1000);
  await closeWin(A);
  A.chat('/titles admin');
  w = await waitWindow(A, (x) => isReview(x) && slotName(x, 10) === 'player_head');
  tm = mark(T);
  await click(A, 10, 1, 0);
  w = await waitWindow(A, isReject);
  // своя причина через чат
  const wm2 = mark(W);
  await click(A, 31, 0, 0);
  await sleep(500);
  A.chat('Своя причина теста');
  check('Отказ №2: токен не возвращается', await waitMsg(T, /отклонён.*Своя причина теста.*не возвращается/, tm));
  check('Причину отказа, введённую в чат, не видят игроки', !W.msgs.slice(wm2).some((m) => /Своя причина теста/.test(m) && !/отклонён/.test(m)));
  check('Баланс после отказа №2 = 1', (await tokens('Tester')) === 1);

  // 11. Одобрение и надевание
  await sleep(3500);
  r = await submitTitle(T, '<gradient:#ff0000:#00ff00>Легенда</gradient>');
  await sleep(1000);
  await closeWin(A);
  A.chat('/titles admin');
  w = await waitWindow(A, (x) => isReview(x) && slotName(x, 10) === 'player_head');
  tm = mark(T);
  await click(A, 10, 0, 0);
  check('Одобрение: игрок получил уведомление', await waitMsg(T, /одобрен/, tm));
  check('Баланс после одобрения = 0', (await tokens('Tester')) === 0);
  await closeWin(A);

  w = await openMain(T);
  await click(T, 13);
  w = await waitWindow(T, (x) => isStorage(x) && slotName(x, 10) === 'name_tag');
  check('Одобренный титул лежит в хранилище', !!w);
  tm = mark(T);
  await click(T, 10, 0, 0);
  check('Титул надевается', await waitMsg(T, /надет/, tm));
  w = await waitWindow(T, (x) => isStorage(x) && slotName(x, 10) === 'enchanted_book');
  check('В хранилище титул отмечен как надетый', !!w);
  out = await lpInfo('Tester');
  check('LuckPerms: суффикс с приоритетом 500 поставлен', LP_SUFFIX.test(out), (out.match(/.*500 - .*/) || [out.slice(-200)])[0].slice(0, 200));

  // 12. Без токенов создать нельзя
  w = await openMain(T);
  await click(T, 11);
  w = await waitWindow(T, isCreateInput);
  tm = mark(T);
  await click(T, 13);
  check('Без токенов ввод титула не начинается', await waitMsg(T, /Нужен токен/, tm));

  // 13. Снять из главного меню
  w = await openMain(T);
  check('В главном меню есть кнопка «Снять»', w && slotName(w, 15) === 'barrier');
  tm = mark(T);
  await click(T, 15);
  check('Титул снимается', await waitMsg(T, /Титул снят/, tm));
  out = await lpInfo('Tester');
  check('LuckPerms: суффикс убран', !LP_SUFFIX.test(out), (out.match(/.*500 - .*/) || [''])[0].slice(0, 160));

  // 14. Удаление требует подтверждения
  w = await openMain(T);
  await click(T, 13);
  w = await waitWindow(T, (x) => isStorage(x) && slotName(x, 10) === 'name_tag');
  await click(T, 10, 1, 1); // Shift+ПКМ
  w = await waitWindow(T, (x) => isStorage(x) && slotName(x, 10) === 'red_dye');
  check('Первый Shift+ПКМ только просит подтверждение', !!w && !T.msgs.some((m) => /удалён из хранилища/.test(m)));
  tm = mark(T);
  await click(T, 10, 1, 1);
  check('Второй Shift+ПКМ удаляет титул', await waitMsg(T, /удалён из хранилища/, tm));
  w = await waitWindow(T, (x) => isStorage(x) && slotName(x, 22) === 'gray_dye');
  check('Хранилище пустое', !!w);

  // 15. Отзыв заявки и кулдаун
  await consoleCmd('titles token give Tester 2', /Выдано/);
  await sleep(3500);
  r = await submitTitle(T, 'Отзыв');
  w = await waitWindow(T, (x) => isStorage(x) && slotName(x, 10) === 'clock');
  tm = mark(T);
  await click(T, 10, 1, 0);
  check('Заявку можно отозвать, токен возвращается', await waitMsg(T, /отозвана, токен возвращён/, tm));
  check('Баланс после отзыва = 2', (await tokens('Tester')) === 2);
  await setCooldown(60);
  r = await submitTitle(T, 'Спам');
  check('Кулдаун между заявками работает', await waitMsg(T, /Подожди/, r.from || 0));
  check('Кулдаун не тратит токен', (await tokens('Tester')) === 2);
  await setCooldown(0);

  // 16. Решение, пока игрок офлайн
  r = await submitTitle(T, 'Оффлайн');
  await sleep(1000);
  T.quit();
  await sleep(1500);
  await closeWin(A);
  A.chat('/titles admin');
  w = await waitWindow(A, (x) => isReview(x) && slotName(x, 10) === 'player_head');
  await click(A, 10, 0, 0);
  await sleep(1500);
  const T2 = await makeBot('Tester');
  check('Офлайн-игрок узнаёт о решении при входе', await waitMsg(T2, /одобрен/, 0, 8000));

  // 17. Снятие титула администрацией (с подтверждением)
  w = await openMain(T2);
  await click(T2, 13);
  w = await waitWindow(T2, (x) => isStorage(x) && slotName(x, 10) === 'name_tag');
  await click(T2, 10, 0, 0);
  await waitMsg(T2, /надет/, 0);
  await closeWin(A);
  A.chat('/titles admin player Tester');
  w = await waitWindow(A, (x) => isPlayerTitles(x) && slotName(x, 10) === 'enchanted_book');
  check('Админ видит титулы игрока', !!w);
  await click(A, 10, 1, 1);
  w = await waitWindow(A, (x) => isPlayerTitles(x) && slotName(x, 10) === 'red_dye');
  check('Снятие титула просит подтверждение', !!w);
  tm = mark(T2);
  await click(A, 10, 1, 1);
  check('Игрок получил уведомление о снятии', await waitMsg(T2, /сняла твой титул/, tm));
  out = await lpInfo('Tester');
  check('LuckPerms: снятый титул убран из суффикса', !LP_SUFFIX.test(out));

  // 18. Консольные команды: границы
  out = await consoleCmd('titles token give Tester 99999999', /Неверное число/);
  check('Слишком большое число токенов отклоняется', /Неверное число/.test(out));
  out = await consoleCmd('titles token take Tester 100', /Забрано/);
  check('Баланс не уходит в минус', (await tokens('Tester')) === 0);
  out = await consoleCmd('titles strikes check Tester', /Отказов у Tester/);
  check('Счётчик отказов = 2', /Отказов у Tester: 2/.test(out), out.trim().split('\n').pop());
  out = await consoleCmd('titles strikes reset Tester', /сброшены/);
  check('Сброс отказов', /сброшены/.test(out));
  out = await consoleCmd('titles reload', /перезагружена/);
  check('Перезагрузка конфигов', /перезагружена/.test(out));

  // 19. Утечки и ошибки
  check('Наблюдатель за всё время не увидел ни одного титула в чате',
    !W.msgs.some((m) => /Тестовый|Легенда|Плохой|Отзыв|Оффлайн|Админ|player_ip/.test(m)));
  const log = logText();
  const errors = log.split('\n').filter((l) => /FoolioTitles/.test(l) && /(ERROR|Exception|SEVERE)/.test(l));
  const traces = log.split('\n').filter((l) => /dev\.foolio\.titles/.test(l) && /at |Exception/.test(l));
  check('В логе сервера нет ошибок плагина', errors.length === 0 && traces.length === 0, [...errors, ...traces].slice(0, 5).join(' || '));
}

main().catch((e) => { check('Сценарий выполнился без падения', false, e.stack); }).finally(() => {
  const failed = results.filter((r) => !r.ok);
  console.log(`\n===== ИТОГО (${VERSION}): ${results.length - failed.length}/${results.length} пройдено =====`);
  for (const f of failed) console.log(`FAIL: ${f.name} — ${f.detail}`);
  fs.writeFileSync(`results-${VERSION}.json`, JSON.stringify(results, null, 2));
  for (const b of Object.values(bots)) { try { b.quit(); } catch {} }
  setTimeout(() => process.exit(failed.length ? 1 : 0), 1500);
});
