// Sustained-load benchmark for the debit hot path. Reads pre-seeded user IDs
// from users.txt (one UUID per line), then drives a constant-arrival-rate
// debit storm against them.
//
// Run:
//   k6 run -e BASE_URL=http://localhost:58081 -e RATE=500 -e DURATION=60s \
//          scenarios/debit_load.js

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:58081';
const RATE = parseInt(__ENV.RATE || '500', 10);
const DURATION = __ENV.DURATION || '60s';

const users = new SharedArray('users', function () {
  return open('./users.txt').split('\n').filter(line => line.length > 0);
});

export const options = {
  scenarios: {
    constant_rps: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds: {
    // Goal from wallet-service/CLAUDE.md: p99 < 50 ms, error rate < 0.1 %.
    'http_req_failed': ['rate<0.001'],
    'http_req_duration{op:debit}': ['p(99)<50', 'p(95)<25'],
  },
};

const debitLatency = new Trend('debit_latency_ms', true);
const debitErrors = new Rate('debit_errors');

export default function () {
  const userId = users[Math.floor(Math.random() * users.length)];
  const idempotencyKey = `bet-${uuidv4()}`;

  const res = http.post(
    `${BASE_URL}/internal/v1/wallet/transactions/debit`,
    JSON.stringify({ userId, amount: { amount: 100, currency: 'KRW' } }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      tags: { op: 'debit' },
    },
  );

  const ok = check(res, {
    'status is 200': r => r.status === 200,
  });
  debitErrors.add(!ok);
  if (ok) {
    debitLatency.add(res.timings.duration);
  }
}
