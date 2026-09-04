const TronWeb = require('tronweb');
const solc = require('solc');
const fs = require('fs');

const RPC = process.env.PRODUCER_RPC || 'http://127.0.0.1:9090';
const VALIDATOR_RPC = process.env.VALIDATOR_RPC || 'http://127.0.0.1:9190';
const PRIVATE_KEY = process.env.POC_PRIVATE_KEY;
if (!PRIVATE_KEY) throw new Error('POC_PRIVATE_KEY is required; obtain it from the isolated private-net demo configuration at runtime.');
const tronWeb = new TronWeb({ fullHost: RPC, privateKey: PRIVATE_KEY });
const validatorWeb = new TronWeb({ fullHost: VALIDATOR_RPC, privateKey: PRIVATE_KEY });

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

async function waitInfo(txid, timeoutMs = 180000) {
  const clients = [tronWeb, validatorWeb];
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    for (const client of clients) {
      try {
        const info = await client.trx.getTransactionInfo(txid);
        if (info && (info.blockNumber || info.contract_address || info.receipt)) return info;
      } catch (_) {}
    }
    await new Promise(r => setTimeout(r, 1000));
  }
  throw new Error(`timeout waiting for ${txid}`);
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
  const deployInfo = await waitInfo(deployTxid);
  const contractAddress = deployInfo.contract_address || deployInfo.contractAddress || deployTx.contract_address;
  if (!contractAddress) throw new Error(`no contract address for ${deployTxid}: ${JSON.stringify(deployInfo)}`);
  console.log(`contract=${contractAddress}`);

  const candidates = [10000, 20000, 40000, 80000, 120000, 160000, 220000, 300000, 400000];
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
      const info = await waitInfo(txid);
      const result = (info.receipt || {}).result || 'UNKNOWN';
      console.log(`candidate_result=${result}`);
      console.log(`candidate_block=${info.blockNumber || 'UNKNOWN'}`);
      console.log(`candidate_energy=${(info.receipt || {}).energy_usage_total || 0}`);
      if (result === 'SUCCESS') {
        fs.writeFileSync('poc-result.json', JSON.stringify({ txid, blockNumber: info.blockNumber, contractAddress, loop: n, producerInfo: info }, null, 2));
        return;
      }
    } catch (e) { console.log(`candidate_error=${e.message || e}`); }
  }
  throw new Error('No producer-side SUCCESS candidate found');
}
main().catch(e => { console.error(e.stack || e); process.exit(1); });
