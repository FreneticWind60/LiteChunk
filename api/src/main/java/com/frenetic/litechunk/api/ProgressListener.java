package com.frenetic.litechunk.api;

/**
 * Progress receiver used by {@link ChunkerEngine#convert}.
 * <p>
 * Implementations must be cheap: the engine polls its task tree and calls this on the conversion thread.
 */
public interface ProgressListener {
    /**
     * @param fraction progress in the range 0..1, may move backwards while the task tree re-weights itself.
     * @param stage    a short human readable stage description, e.g. {@code 读取区块}.
     */
    void onProgress(double fraction, String stage);
}
