# FoolioBridge

Бесплатный open-source мост между **донат-сайтом** и **сервером Minecraft** (Paper / Purpur 1.20+, Java 17+).
Игрок оплачивает товар на сайте — сервер сам забирает заказ и выполняет нужные команды.

* Не нужно открывать порты: плагин сам опрашивает сайт.
* Все запросы и ответы подписаны HMAC-SHA256 — подделать заказ или повторить старый нельзя.
* Один заказ никогда не выдаётся дважды, даже если сервер упал посередине.
* Офлайн-очередь: если игрока нет на сервере, покупка ждёт его входа.
* Белый список команд, проверка ников, `/donate` с историей покупок.
* Работает с любым сайтом: протокол открыт ([PROTOCOL.md](PROTOCOL.md)), для Node.js есть готовый модуль.

## Установка на сервер

1. Положите `FoolioBridge-1.0.0.jar` в `plugins/` и запустите сервер.
2. Откройте `plugins/FoolioBridge/config.yml`:
   * `site.endpoint` — адрес API на сайте;
   * `site.secret` — длинный случайный ключ (тот же, что на сайте);
   * `site.server-id` — имя сервера (в сети Velocity у каждого бэкенда своё);
   * `security.allowed-commands` — желательно перечислить разрешённые команды (`lp`, `cmi`, `give`...).
3. `/foolio reload`, затем `/foolio status` — должно показать «OK, подпись совпадает».

Плагин ставится на тот сервер, где выдаётся донат (в сети — на бэкенд, не на прокси).

Сгенерировать секрет: `openssl rand -hex 32` или `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`.

### Команды

| Команда | Право | Что делает |
|---|---|---|
| `/donate` | `foolio.donate` (всем) | ссылка на магазин и последние покупки игрока |
| `/foolio status` | `foolio.admin` (OP) | проверка связи с сайтом, размер очереди |
| `/foolio poll` | `foolio.admin` | опросить сайт прямо сейчас |
| `/foolio reload` | `foolio.admin` | перечитать конфиг |

## Подключение сайта на Node.js

Модуль лежит в папке `node/` (без зависимостей).

```js
const { createFoolioBridge } = require('./foolio-bridge'); // или из npm, когда опубликуем

const bridge = createFoolioBridge({
  secret: process.env.FOOLIO_SECRET,
  // оплаченные, но ещё не выданные заказы для этого сервера
  getPendingOrders: async (server) => db.getPaidUndelivered(server),
  // сервер сообщил результат выдачи
  onResults: async (server, results) => {
    for (const r of results) await db.setDelivered(r.id, r.status, r.errors);
  },
});

// Express: маршрут ДО express.json()
app.post('/api/foolio', bridge.handler);
```

Формат заказа:

```js
{
  id: '1042',                       // уникальный номер заказа
  player: 'Steve',
  title: 'VIP на 30 дней',          // для /donate и сообщения игроку
  commands: ['lp user {player} parent addtemp vip 30d'],
  online_only: false,               // false = выдать, даже если игрок офлайн
}
```

Нет базы данных? Есть готовая файловая очередь `FileOrderQueue` — см. `node/examples/simple-server.js`.

## Другой язык сайта (PHP, Python...)

Нужен один POST-эндпоинт, который проверяет подпись и отвечает подписанным JSON. Всё описано в [PROTOCOL.md](PROTOCOL.md) — это около 50 строк кода на любом языке.

## Сборка из исходников

```
cd plugin
gradle build        # jar появится в plugin/build/libs/
```

Готовый jar лежит рядом с этим README. При каждом push GitHub Actions пересобирает плагин автоматически.

## Лицензия

MIT — можно использовать, менять и распространять бесплатно, в том числе на коммерческих серверах.
