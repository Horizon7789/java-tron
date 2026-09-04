from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"expected source pattern not found: {path}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "chainbase/src/main/java/org/tron/core/db/TransactionTrace.java",
    "import java.util.Objects;\n",
    "import java.lang.management.ManagementFactory;\n"
    "import java.lang.management.ThreadMXBean;\n"
    "import java.util.Objects;\n",
)

replace_once(
    "chainbase/src/main/java/org/tron/core/db/TransactionTrace.java",
    "  private Runtime runtime;\n\n  private ForkController forkController;",
    "  private Runtime runtime;\n\n"
    "  private static final ThreadMXBean RETRY_POC_CPU_TIMER =\n"
    "      ManagementFactory.getThreadMXBean();\n"
    "  private int retryPocExecCount;\n"
    "  private long retryPocCpuNanos;\n"
    "  private long retryPocWallNanos;\n\n"
    "  private ForkController forkController;",
)

replace_once(
    "chainbase/src/main/java/org/tron/core/db/TransactionTrace.java",
    """    /*  VM execute  */
    runtime.execute(transactionContext);
    setBill(transactionContext.getProgramResult().getEnergyUsed());
    setPenalty(transactionContext.getProgramResult().getEnergyPenaltyTotal());""",
    """    /*  VM execute  */
    retryPocExecCount++;
    long wallStart = System.nanoTime();
    long cpuStart = RETRY_POC_CPU_TIMER.isCurrentThreadCpuTimeSupported()
        ? RETRY_POC_CPU_TIMER.getCurrentThreadCpuTime() : -1L;
    try {
      runtime.execute(transactionContext);
    } finally {
      long wallEnd = System.nanoTime();
      retryPocWallNanos += wallEnd - wallStart;
      if (cpuStart >= 0 && RETRY_POC_CPU_TIMER.isCurrentThreadCpuTimeSupported()) {
        long cpuEnd = RETRY_POC_CPU_TIMER.getCurrentThreadCpuTime();
        if (cpuEnd >= cpuStart) {
          retryPocCpuNanos += cpuEnd - cpuStart;
        }
      }
    }
    setBill(transactionContext.getProgramResult().getEnergyUsed());
    setPenalty(transactionContext.getProgramResult().getEnergyPenaltyTotal());
    logger.info("RETRY_POC txId={} execution={} result={} energy={} cpuNanos={} wallNanos={}",
        trx.getTransactionId(), retryPocExecCount,
        transactionContext.getProgramResult().getResultCode(),
        transactionContext.getProgramResult().getEnergyUsed(),
        retryPocCpuNanos, retryPocWallNanos);""",
)

replace_once(
    "chainbase/src/main/java/org/tron/core/db/TransactionTrace.java",
    "  public void saveEnergyLeftOfOrigin(long energyLeft) {",
    """  public int getRetryPocExecCount() {
    return retryPocExecCount;
  }

  public long getRetryPocCpuNanos() {
    return retryPocCpuNanos;
  }

  public void saveEnergyLeftOfOrigin(long energyLeft) {""",
)

replace_once(
    "framework/src/main/java/org/tron/core/db/Manager.java",
    """      trace.setResult();
      if (trace.checkNeedRetry()) {""",
    """      trace.setResult();
      boolean retryPocNeedRetry = trace.checkNeedRetry();
      logger.info("RETRY_POC decision txId={} storedResult={} firstResult={} needRetry={}",
          txId, trxCap.getContractRet(), trace.getReceipt().getResult(),
          retryPocNeedRetry);
      if (retryPocNeedRetry) {""",
)

print("retry instrumentation applied")
