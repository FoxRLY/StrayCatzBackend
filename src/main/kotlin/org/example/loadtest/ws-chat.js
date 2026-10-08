// Нагрузочный тест сокета: N человек держат соединение, пишут в свои чаты,
// шлют activity и typing; меряем задержку «отправил → получил message.new».
//
// Данные: psql … -v users=100000 -f loadtest/seed.sql
// Стенд: straycatz.auth.allow-dev-tokens=true (только стенд!), шина redis.
//
//   k6 run -e WS_URL=ws://host:8080/v1/ws -e USERS=20000 -e OFFSET=0 loadtest/ws-chat.js
//
// Один k6 держит ~20–30 тыс. сокетов (упрётся в порты и CPU генератора).
// 100k = 4–5 машин с разными OFFSET: 0, 20000, 40000, …
import ws from 'k6/ws';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const URL = __ENV.WS_URL || 'ws://localhost:8080/v1/ws';
const USERS = parseInt(__ENV.USERS || '10000', 10);
const OFFSET = parseInt(__ENV.OFFSET || '0', 10);
const RAMP = __ENV.RAMP || '5m';
const HOLD = __ENV.HOLD || '15m';
const SESSION_S = parseInt(__ENV.SESSION_S || '600', 10);     // сколько живёт одно соединение
const MSG_EVERY_S = parseInt(__ENV.MSG_EVERY_S || '30', 10);  // как часто человек пишет

const latency = new Trend('msg_latency_ms', true);
const received = new Counter('frames_received');
const errors = new Counter('frame_errors');

export const options = {
  scenarios: {
    sockets: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: USERS },
        { duration: HOLD, target: USERS },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    msg_latency_ms: ['p(95)<500', 'p(99)<1500'],
    checks: ['rate>0.99'],
  },
};

const hex = (n) => n.toString(16).padStart(12, '0');
const userId = (n) => `00000000-0000-4000-8000-${hex(n)}`;
const groupId = (n) => `00000000-0000-4000-9000-${hex(Math.floor((n - 1) / 20) + 1)}`;
const bigId = (n) => `00000000-0000-4000-a000-${hex(Math.floor((n - 1) / 1000) + 1)}`;
const uuid4 = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = (Math.random() * 16) | 0;
  return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
});

export default function () {
  const n = OFFSET + ((__VU - 1) % USERS) + 1;
  const me = userId(n);
  const group = groupId(n);
  const big = bigId(n);

  const res = ws.connect(URL, { headers: { 'Sec-WebSocket-Protocol': `straycatz.v1, dev.${me}` } }, (socket) => {
    socket.on('open', () => {
      socket.send(JSON.stringify({ t: 'hello', rid: 'hello', d: {} }));
      socket.send(JSON.stringify({ t: 'chat.open', d: { chatId: group } }));

      // пишет раз в ~MSG_EVERY_S (±50%), 10% сообщений — в большой чат на 1000 человек
      socket.setInterval(() => {
        const chatId = Math.random() < 0.1 ? big : group;
        socket.send(JSON.stringify({ t: 'typing', d: { chatId } }));
        socket.send(JSON.stringify({
          t: 'message.send', rid: uuid4(),
          d: { chatId, clientToken: uuid4(), body: `lt ${Date.now()}` },
        }));
      }, (MSG_EVERY_S * (0.5 + Math.random())) * 1000);

      socket.setInterval(() => socket.send(JSON.stringify({ t: 'activity', d: {} })), 60000);
      socket.setTimeout(() => socket.close(), SESSION_S * 1000 * (0.8 + Math.random() * 0.4));
    });

    socket.on('message', (raw) => {
      received.add(1);
      const f = JSON.parse(raw);
      if (f.t === 'message.new' && f.d && typeof f.d.body === 'string' && f.d.body.startsWith('lt ')) {
        latency.add(Date.now() - parseInt(f.d.body.slice(3), 10));
      } else if (f.t === 'error') {
        errors.add(1);
      } else if (f.t === 'resync') {
        socket.send(JSON.stringify({ t: 'hello', rid: 'hello', d: {} }));
      }
    });
  });

  check(res, { 'сокет открыт (101)': (r) => r && r.status === 101 });
}
