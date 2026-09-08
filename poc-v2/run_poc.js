const TronWeb = require('tronweb');
const solc = require('solc');
const fs = require('fs');

const RPC = process.env.PRODUCER_RPC || 'http://127.0.0.1:9090';
const VALIDATOR_RPC = process.env.VALIDATOR_RPC || 'http://127.0.0.1:9190';
const PRIVATE_KEY = process.env.POC_PRIVATE_KEY;
if (!PRIVATE_KEY) throw new Error('POC_PRIVATE_KEY is required; obtain it from the isolated private-net demo configuration at runtime.');
const tronWeb = new TronWeb({ fullHost: RPC, privateKey: PRIVATE_KEY });

const source = `pragma solidity ^0.5.17;
contract RetryCalibrator {
    uint256 public sink;
    function burn(uint256 n) public {
        uint256 x = sink;
        for (uint256 i = 0; i < n; i++) {
            x = x + i;
            x = x ^ (i + 1);
        }
        sink = x;
    }
}`;

function compile() {
  const input = { language: 'Solidity', sources: { 'RetryCalibrator.sol': { content: source } },
    settings: { optimizer: { enabled: false }, outputSelection: { '*': { '*': ['abi', 'evm.bytecode.object'] } } } };
  const out = JSON.parse(solc.compile(JSON.stringify(input)));
  if (out.errors) {
    const fatal = out.errors.filter(e => e.severity === 'error');
    if (fatal.length) throw new Error(fatal.map(e => e.formattedMessage).join('\n'));
  }
  const c = out.contracts['RetryCalibrator.sol'].RetryCalibrator;
  return { abi: c.abi, bytecode: c.evm.bytecode.object };
}

function txIdOf(tx) {
  return tx.txID || tx.txid || tx.id;
}

function blockResult(tx) {
  const ret = tx && tx.ret;
  return Array.isArray(ret) && ret.length ? ret[0].contractRet : undefined;
}

async function findCommittedTransaction(txid, timeoutMs = 180000) {
  const start = Date.now();
  let lastBlock = null;
  while (Date.now() - start < timeoutMs) {
    try {
      const now = await tronWeb.trx.getCurrentBlock();
      const number = now && now.block_header && now.block_header.raw_data && now.block_header.raw_data.number;
      if (typeof number !== 'number') throw new Error('producer getnowblock returned no block number');
      if (number !== lastBlock) {
        lastBlock = number;
        for (let n = number; n >= Math.max(0, number - 12); n--) {
          const block = await tronWeb.trx.getBlockByNum(n);
          const tx = (block && block.transactions || []).find(item => txIdOf(item) === txid);
          if (tx) {
            let info = {};
            try { info = await tronWeb.trx.getTransactionInfo(txid); } catch (_) {}
            return { blockNumber: n, block, tx, info };
          }
        }
      }
    } catch (e) {}
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error(`timeout waiting for committed transaction ${txid}`);
}

async function waitCommitted(txid, timeoutMs = 180000) {
  const committed = await findCommittedTransaction(txid, timeoutMs);
  const info = committed.info || {};
  return {
    ...committed,
    result: (info.receipt || {}).result || blockResult(committed.tx) || 'UNKNOWN',
    energy: (info.receipt || {}).energy_usage_total || 0,
    contractAddress: info.contract_address || info.contractAddress
  };
}

async function main() {
  const owner = tronWeb.address.fromPrivateKey(PRIVATE_KEY);
  const { abi, bytecode } = compile();

  const deployTx = await tronWeb.transactionBuilder.createSmartContract({
    abi: JSON.stringify(abi), bytecode, name: 'RetryCalibrator', feeLimit: 100_000_000,
    callValue: 0, userFeePercentage: 100, originEnergyLimit: 10_000_000
  }, owner);
  const signedDeploy = await tronWeb.trx.sign(deployTx, PRIVATE_KEY);
  const deployBroadcast = await tronWeb.trx.sendRawTransaction(signedDeploy);
  if (!deployBroadcast.result) throw new Error(`deployment broadcast failed: ${JSON.stringify(deployBroadcast)}`);
  const deployTxid = deployBroadcast.txid || deployTx.txID;
  console.log(`deployment_txid=${deployTxid}`);

  const deploy = await waitCommitted(deployTxid);
  console.log(`deployment_block=${deploy.blockNumber}`);
  console.log(`deployment_result=${deploy.result}`);
  console.log(`deployment_energy=${deploy.energy}`);

  const contractAddress = deploy.contractAddress || deployTx.contract_address;
  if (!contractAddress) throw new Error(`deployment committed in block ${deploy.blockNumber}, but no contract address was returned by transaction-info`);
  console.log(`contract=${contractAddress}`);

  const candidates = [10000, 20000, 40000, 80000, 120000, 160000, 220000, 300000, 400000];
  const submitted = [];
  for (const n of candidates) {
    console.log(`candidate=${n}`);
    try {
      const built = await tronWeb.transactionBuilder.triggerSmartContract(
        contractAddress, 'burn(uint256)', { feeLimit: 100_000_000, callValue: 0 },
        [{ type: 'uint256', value: n }], owner);
      const signed = await tronWeb.trx.sign(built.transaction, PRIVATE_KEY);
      const sent = await tronWeb.trx.sendRawTransaction(signed);
      const txid = sent.txid || built.transaction.txID;
      console.log(`txid=${txid}`);
      if (!sent.result) { console.log(`broadcast_failed=${JSON.stringify(sent)}`); continue; }
      const committed = await waitCommitted(txid);
      console.log(`candidate_result=${committed.result}`);
      console.log(`candidate_block=${committed.blockNumber}`);
      console.log(`candidate_energy=${committed.energy}`);
      submitted.push({ txid, loop: n, committed });
    } catch (e) {
      console.log(`candidate_error=${e.message || e}`);
    }
  }

  // Do not select based on producer SUCCESS. The validator log is the source
  // of truth for whether the retry predicate was actually entered.
  const logPath = process.env.VALIDATOR_LOG;
  if (!logPath) throw new Error('VALIDATOR_LOG is required for retry candidate selection');
  const logText = fs.readFileSync(logPath, 'utf8');
  const byTx = new Map();
  const retryRe = /RETRY_POC txId=([0-9a-fA-F]+) execution=([0-9]+) result=([A-Z_]+)/g;
  let match;
  while ((match = retryRe.exec(logText)) !== null) {
    const [, txid, execution, result] = match;
    if (!byTx.has(txid)) byTx.set(txid, []);
    byTx.get(txid).push({ execution: Number(execution), result });
  }

  const eligible = submitted.filter(item => {
    const records = (byTx.get(item.txid) || []).sort((a, b) => a.execution - b.execution);
    const exactOrdinals = records.map(r => r.execution).join(',') === '1,2';
    const firstTimedOut = records[0] && records[0].result === 'OUT_OF_TIME';
    return item.committed.result !== 'OUT_OF_TIME' && exactOrdinals && firstTimedOut;
  });

  if (!eligible.length) {
    console.error('RETRY NOT REPRODUCED: no candidate had validator execution [1, 2] with execution 1 OUT_OF_TIME and a non-timeout producer result.');
    throw new Error('retry not reproduced');
  }

  const selected = eligible[0];
  const selectedRecords = byTx.get(selected.txid).sort((a, b) => a.execution - b.execution);
  console.log(`selected_retry_txid=${selected.txid}`);
  console.log(`selected_retry_loop=${selected.loop}`);
  console.log(`selected_retry_records=${JSON.stringify(selectedRecords)}`);

  fs.writeFileSync('poc-result.json', JSON.stringify({
    txid: selected.txid,
    blockNumber: selected.committed.blockNumber,
    contractAddress,
    loop: selected.loop,
    producerInfo: selected.committed.info,
    producerBlockTransaction: selected.committed.tx,
    validatorExecutions: selectedRecords,
    selection: 'validator-log retry evidence'
  }, null, 2));
}
main().catch(e => { console.error(e.stack || e); process.exit(1); });
