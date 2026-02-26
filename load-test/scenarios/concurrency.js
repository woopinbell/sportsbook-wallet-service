// Level-3 정합성 proof: 100 concurrent debits against ONE user — each with a
// distinct Idempotency-Key — must never overdraw the account. The pessimistic
// row lock + balance precondition is what enforces this; this script smokes it.
//
// Run:
//   k6 run -e BASE_URL=http://localhost:58081 -e USER_ID=<uuid> \
//          scenarios/concurrency.js
//
// Post-condition (curl after the run):
//   GET /internal/v1/wallet/accounts/<USER_ID>/balance
//   → available + locked must equal the seeded deposit (no double-spend).

import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:58081';
const USER_ID = __ENV.USER_ID;
const STAKE = parseInt(__ENV.STAKE || '100', 10);

if (!USER_ID) {
  throw new Error('USER_ID env var is required — seed a user first.');
}

export const options = {
  scenarios: {
    concurrent_debits: {
      executor: 'per-vu-iterations',
      vus: 100,
      iterations: 1,
      maxDuration: '10s',
    },
  },
};

export default function () {
  const res = http.post(
    `${BASE_URL}/internal/v1/wallet/transactions/debit`,
    JSON.stringify({ userId: USER_ID, amount: { amount: STAKE, currency: 'KRW' } }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `race-${uuidv4()}`,
      },
    },
  );
  check(res, {
    'status is 200 or 422 (overdraft is acceptable)': r =>
      r.status === 200 || r.status === 422,
  });
}
