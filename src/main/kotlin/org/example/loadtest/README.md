# Нагрузочный тест сокетов

1. Стенд: `straycatz.bus.transport=redis`, `straycatz.auth.allow-dev-tokens=true` (**только стенд**),
   2+ ноды за балансировщиком, Redis, Postgres (+ PgBouncer).
2. Данные: `psql -h … -U straycatz -d straycatz -v users=100000 -f loadtest/seed.sql`
   (100k людей, группы по 20, чаты по 1000, по ~10 друзей).
3. Генераторы: один k6 держит ~20–30 тыс. сокетов. На 100k — 4–5 машин:
   ```bash
   ulimit -n 200000
   k6 run -e WS_URL=wss://stand/v1/ws -e USERS=20000 -e OFFSET=0     loadtest/ws-chat.js
   k6 run -e WS_URL=wss://stand/v1/ws -e USERS=20000 -e OFFSET=20000 loadtest/ws-chat.js
   # … OFFSET=40000, 60000, 80000
   ```
4. Что смотреть:
   - `msg_latency_ms` p95 < 500 мс, p99 < 1,5 с (порог в скрипте);
   - лог нод раз в 10 минут: «шина: N соединений, M людей, очередь K» — очередь должна быть около нуля;
   - Postgres: `pg_stat_activity` (соединения), `pg_stat_statements` (топ запросов);
   - Redis: `INFO stats` (`instantaneous_ops_per_sec`), `INFO clients`;
   - нода: CPU, heap, GC-паузы; закрытия 4508 (медленный клиент) в логе.
5. Сценарий рестарта: под нагрузкой перезапусти одну ноду — её клиенты получат 1012 и
   переподключатся к остальным; задержка у остальных не должна вырасти.

Параметры: `RAMP` (5m), `HOLD` (15m), `SESSION_S` (600 — сколько живёт соединение),
`MSG_EVERY_S` (30 — как часто человек пишет).
