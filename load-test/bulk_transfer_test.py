#!/usr/bin/env python3
"""
Bulk transfer load test for payment-service.

What this actually proves: fires a real batch of concurrent transfer
requests at your locally-running payment-service, then verifies the
ledger's core invariant held -- no money was created or destroyed --
across every account it touched. This is what turns "100K+ transactions"
(or whatever number you choose) from an assumed figure into one you
actually ran and can describe.

Requires:
  - payment-service running (Eclipse, port 8090 by default)
  - `docker compose up -d` (for the Postgres it's backed by)
  - Optionally fraud-service + the rest of the stack also running, so
    transfers actually settle (ALLOW/BLOCK/REVIEW) instead of staying
    PENDING. The conservation check below is correct either way -- see
    the comment on verify_conservation().
  - `pip3 install requests` (one-time)

Usage:
  python3 load-test/bulk_transfer_test.py
  python3 load-test/bulk_transfer_test.py --transfers 50000 --accounts 100
"""

import argparse
import random
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

BASE_URL = "http://localhost:8090"
INITIAL_BALANCE_CENTS = 10_000_000  # $100,000 per account -- generous buffer
MIN_TRANSFER_CENTS = 100            # $1
MAX_TRANSFER_CENTS = 5_000          # $50


def create_account(session, owner_name):
    headers = {"Idempotency-Key": str(uuid.uuid4())}
    body = {
        "ownerName": owner_name,
        "currency": "USD",
        "initialBalanceCents": INITIAL_BALANCE_CENTS,
    }
    resp = session.post(f"{BASE_URL}/accounts", json=body, headers=headers, timeout=10)
    resp.raise_for_status()
    return resp.json()["id"]


def post_transfer(session, from_id, to_id, amount_cents):
    headers = {"Idempotency-Key": str(uuid.uuid4())}
    body = {"fromAccountId": from_id, "toAccountId": to_id, "amountCents": amount_cents}
    try:
        resp = session.post(f"{BASE_URL}/transfers", json=body, headers=headers, timeout=10)
        if resp.status_code == 201:
            data = resp.json()
            return {"ok": True, "id": data["id"], "status": data["status"], "amount": amount_cents}
        return {"ok": False, "status_code": resp.status_code, "body": resp.text[:200]}
    except requests.RequestException as ex:
        return {"ok": False, "error": str(ex)}


def get_balance(session, account_id):
    resp = session.get(f"{BASE_URL}/accounts/{account_id}", timeout=10)
    resp.raise_for_status()
    return resp.json()["balanceCents"]


def get_transfer_status(session, transfer_id):
    resp = session.get(f"{BASE_URL}/transfers/{transfer_id}", timeout=10)
    resp.raise_for_status()
    return resp.json()["status"]


def verify_conservation(session, account_ids, created_transfers, wait_seconds):
    """
    The invariant that must hold regardless of whether fraud-service is
    running: every account's spendable balance, PLUS whatever's still
    held against a not-yet-resolved transfer (PENDING/UNDER_REVIEW),
    must equal exactly what was funded in. Nothing created, nothing
    destroyed -- see docs/DECISIONS.md #11 for why a PENDING transfer
    still counts as "the money's still there, just in transit."

    If fraud-service is running, give it `wait_seconds` to actually
    resolve most transfers before checking -- otherwise everything will
    still show PENDING, which is also a valid (if less interesting)
    pass of this same check.
    """
    print(f"\nWaiting {wait_seconds}s for any in-flight fraud decisions to settle...")
    time.sleep(wait_seconds)

    print("Re-checking account balances...")
    final_balance = sum(get_balance(session, acc_id) for acc_id in account_ids)

    print("Re-checking transfer statuses for held (PENDING/UNDER_REVIEW) funds...")
    held_total = 0
    status_counts = {}
    with ThreadPoolExecutor(max_workers=30) as pool:
        futures = {pool.submit(get_transfer_status, session, t["id"]): t for t in created_transfers}
        for future in as_completed(futures):
            t = futures[future]
            status = future.result()
            status_counts[status] = status_counts.get(status, 0) + 1
            if status in ("PENDING", "UNDER_REVIEW"):
                held_total += t["amount"]

    expected_total = len(account_ids) * INITIAL_BALANCE_CENTS
    actual_total = final_balance + held_total

    print("\nFinal transfer status breakdown:")
    for status, count in sorted(status_counts.items()):
        print(f"  {status}: {count}")

    print(f"\nExpected total (funded in):     {expected_total:,} cents")
    print(f"Actual total (balances + held): {actual_total:,} cents")
    if expected_total == actual_total:
        print("PASS -- conservation holds exactly. No money created or destroyed.")
    else:
        diff = actual_total - expected_total
        print(f"MISMATCH -- off by {diff:,} cents. Investigate before trusting this number.")

    return expected_total == actual_total


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--transfers", type=int, default=10_000)
    parser.add_argument("--accounts", type=int, default=50)
    parser.add_argument("--concurrency", type=int, default=30)
    parser.add_argument("--wait-seconds", type=int, default=20)
    args = parser.parse_args()

    session = requests.Session()
    adapter = requests.adapters.HTTPAdapter(pool_connections=args.concurrency, pool_maxsize=args.concurrency)
    session.mount("http://", adapter)

    print(f"Creating {args.accounts} funded accounts...")
    account_ids = [create_account(session, f"load-test-{i}") for i in range(args.accounts)]
    print(f"Done. Each funded with {INITIAL_BALANCE_CENTS:,} cents.")

    print(f"\nFiring {args.transfers} transfers with {args.concurrency} concurrent workers...")
    start = time.time()
    created_transfers = []
    failures = 0

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = []
        for _ in range(args.transfers):
            from_id, to_id = random.sample(account_ids, 2)
            amount = random.randint(MIN_TRANSFER_CENTS, MAX_TRANSFER_CENTS)
            futures.append(pool.submit(post_transfer, session, from_id, to_id, amount))

        for i, future in enumerate(as_completed(futures), start=1):
            result = future.result()
            if result["ok"]:
                created_transfers.append(result)
            else:
                failures += 1
            if i % 1000 == 0:
                print(f"  {i}/{args.transfers} requests completed...")

    elapsed = time.time() - start
    throughput = args.transfers / elapsed if elapsed > 0 else 0

    print(f"\nFired {args.transfers} transfer requests in {elapsed:.1f}s ({throughput:.1f} req/s).")
    print(f"Accepted (201): {len(created_transfers)}  Failed/rejected: {failures}")
    print("(A nonzero failure count is fine if it's INSUFFICIENT_FUNDS rejections -- "
          "those are a correct business outcome, not a bug. Investigate anything else.")

    passed = verify_conservation(session, account_ids, created_transfers, args.wait_seconds)

    print(f"\n{'='*60}")
    print(f"SUMMARY: {len(created_transfers)} real transfers processed, "
          f"conservation check {'PASSED' if passed else 'FAILED'}, "
          f"{throughput:.1f} req/s on this machine (local Docker Compose).")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
