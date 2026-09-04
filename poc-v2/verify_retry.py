#!/usr/bin/env python3
"""Fail-closed verifier for the conditional java-tron retry branch."""
import argparse
import json
import re
from pathlib import Path
from urllib.request import Request, urlopen

RETRY_LOG = re.compile(
    r"RETRY_POC txId=(?P<txid>[0-9a-fA-F]+) "
    r"execution=(?P<execution>[0-9]+) "
    r"result=(?P<result>[A-Z_]+) "
    r"energy=(?P<energy>[0-9]+) "
    r"cpuNanos=(?P<cpu>[0-9]+) "
    r"wallNanos=(?P<wall>[0-9]+)"
)


def rpc(base, path, payload):
    body = json.dumps(payload).encode()
    req = Request(base.rstrip("/") + path, data=body,
                  headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=20) as response:
        return json.load(response)


def tx_id_from_block_tx(tx):
    return tx.get("txID") or tx.get("txid") or tx.get("id")


def contract_result_from_block_tx(tx):
    results = tx.get("ret") or []
    if not results:
        return None
    return results[0].get("contractRet")


def find_block_transaction(block, txid):
    for tx in block.get("transactions", []):
        if tx_id_from_block_tx(tx) == txid:
            return tx
    return None


def stored_result(producer_rpc, txid, explicit_block_number=None):
    info = rpc(producer_rpc, "/wallet/gettransactioninfobyid", {"value": txid})
    block_number = explicit_block_number or info.get("blockNumber")
    if block_number is None:
        raise RuntimeError("producer transaction info has no blockNumber")
    block = rpc(producer_rpc, "/wallet/getblockbynum", {"num": int(block_number)})
    tx = find_block_transaction(block, txid)
    if tx is None:
        raise RuntimeError("transaction is not present in producer block")
    result = contract_result_from_block_tx(tx)
    if result is None:
        raise RuntimeError("producer block transaction has no contract result")
    return result, int(block_number), info


def validator_result(validator_rpc, txid):
    try:
        info = rpc(validator_rpc, "/wallet/gettransactioninfobyid", {"value": txid})
    except Exception:
        return None
    return (info.get("receipt") or {}).get("result")


def read_exec_records(log_path, txid):
    records = []
    for line in Path(log_path).read_text(errors="replace").splitlines():
        match = RETRY_LOG.search(line)
        if match and match.group("txid").lower() == txid.lower():
            record = match.groupdict()
            for key in ("execution", "energy", "cpu", "wall"):
                record[key] = int(record[key])
            records.append(record)
    return sorted(records, key=lambda item: item["execution"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--txid", required=True)
    parser.add_argument("--producer-rpc", required=True)
    parser.add_argument("--validator-rpc", required=True)
    parser.add_argument("--validator-log", required=True)
    parser.add_argument("--block-number", type=int)
    args = parser.parse_args()
    try:
        stored, block_number, _ = stored_result(
            args.producer_rpc, args.txid, args.block_number)
        records = read_exec_records(args.validator_log, args.txid)
        final_validator_result = validator_result(args.validator_rpc, args.txid)
    except Exception as exc:
        print(f"INCONCLUSIVE: {exc}")
        return 2

    print(f"transaction:              {args.txid}")
    print(f"block:                    {block_number}")
    print(f"stored block result:      {stored}")
    print(f"validator execution logs: {len(records)}")
    for record in records:
        print("  execution={execution} result={result} energy={energy} "
              "cpuNanos={cpu} wallNanos={wall}".format(**record))
    print(f"validator RPC result:     {final_validator_result or 'not finalized'}")

    if stored == "OUT_OF_TIME":
        print("NOT REPRODUCED: block itself records OUT_OF_TIME; retry predicate is false.")
        return 1
    if not records:
        print("INCONCLUSIVE: no instrumented VM execution records were found.")
        return 2
    if [record["execution"] for record in records] != [1, 2]:
        print("NOT REPRODUCED: execution ordinals are not exactly [1, 2].")
        return 1
    if records[0]["result"] != "OUT_OF_TIME":
        print("NOT REPRODUCED: first local execution was not OUT_OF_TIME.")
        return 1

    print("RETRY BRANCH ENTERED: two VM executions with a non-timeout block result.")
    if records[1]["result"] != stored:
        print("FOLLOW-UP: second result still differs from the block result; "
              "block rejection is expected.")
    else:
        print("FOLLOW-UP: second result matches the block result; inspect billing and CPU.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
