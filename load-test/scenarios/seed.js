// Seeds N user accounts each with an initial deposit, then writes the IDs to
// stdout as JSON for the debit_load script to pick up.
//
// Run:
//   k6 run --vus 10 --iterations 200 -e BASE_URL=http://localhost:58081 \
//       -e DEPOSIT_KRW=10000000000 scenarios/seed.js > results/raw/users.json

import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:58081';
const DEPOSIT = parseInt(__ENV.DEPOSIT_KRW || '10000000000', 10);

export default function () {
  const userId = uuidv4();

  const open = http.post(
    `${BASE_URL}/internal/v1/wallet/accounts`,
    JSON.stringify({ userId, currency: 'KRW' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(open, { 'account opened': r => r.status === 200 });

  const dep = http.post(
    `${BASE_URL}/internal/v1/wallet/transactions/deposit`,
    JSON.stringify({ userId, amount: { amount: DEPOSIT, currency: 'KRW' } }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `seed-${userId}`,
      },
    },
  );
  check(dep, { 'seed deposit ok': r => r.status === 200 });

  console.log(userId);
}
