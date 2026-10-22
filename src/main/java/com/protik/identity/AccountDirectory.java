package com.protik.identity;

import java.util.UUID;

/**
 * The identity module's answer to "is there such a person?" for modules that act on another user
 * (following, reporting). A yes/no only; account data stays private to identity (ADR 0016).
 */
public interface AccountDirectory {

    /** @return true if an active account has this ID */
    boolean isActive(UUID accountId);
}
