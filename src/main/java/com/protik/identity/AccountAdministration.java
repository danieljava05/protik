package com.protik.identity;

import java.util.UUID;

/** What staff-facing modules may ask identity to do to an account (ADR 0019). Idempotent. */
public interface AccountAdministration {

    /** Marks the account suspended and ends all its sessions. @return false if there is no such active account */
    boolean suspend(UUID accountId);

    /** @return true if an account with this ID exists in any state */
    boolean exists(UUID accountId);
}
