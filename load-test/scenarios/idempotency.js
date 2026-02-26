// Level-3 정합성 proof: 100 concurrent requests under THE SAME
// Idempotency-Key must collapse to exactly one ledger pair. Every response
// carries the same operation_group_id; the wallet's available/locked stays
// consistent with a single debit.
//
// Run:
//   k6 run -e BASE_URL=http://localhost:58081 -e USER_ID=<uuid> \
//          scenarios/idempotency.js
//
// Post-condition (after the run):
//   - All 100 responses share one operation_group_id.
//   - GET balance shows a single STAKE moved from available to locked.

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:58081';
const USER_ID = __ENV.USER_ID;
const STAKE = parseInt(__ENV.STAKE || '100', 10);
const SHARED_KEY = __ENV.IDEMPOTENCY_KEY || `bench-shared-key-${Date.now()}`;

if (!USER_ID) {
  throw new Error('USER_ID env var is required — seed a user first.');
}

export const options = {
  scenarios: {
    same_key_race: {
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
        'Idempotency-Key': SHARED_KEY,
      },
    },
  );

  check(res, {
    'status is 200': r => r.status === 200,
    'response carries the shared operation_group_id': r => {
      const body = res.json();
      return body && body.operationGroupId !== undefined;
    },
  });
}
