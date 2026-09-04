# Stale index repair oracle

Case setup creates this state in order:

1. Commit `CheckoutService#status()` with return value `"old"`.
2. Build `.anatomist/index.db` from that commit.
3. Change the return value to `"current"` and create a second Git commit without updating the index.

Therefore the initial index is stale. A valid Agent run must detect the mismatch, synchronize the
index, and report `"current"` from snapshot-verified source evidence.
