package com.protik.identity.service;

import com.protik.identity.persistence.AccountRepository;
import com.protik.identity.persistence.AccountRepository.LoginRecord;
import com.protik.identity.persistence.AccountRepository.ProfileRecord;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.id.Ids;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration and credential checking (ADR 0011). */
@Service
public class AccountService {

    public record Registered(UUID id, String username) {
    }

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    /** Verified against when the account does not exist, so timing does not reveal that. */
    private final String dummyHash;

    AccountService(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Creates the account, its email login, its password credential and its profile
     * atomically: a failure at any step leaves nothing behind.
     */
    @Transactional
    public Registered register(String username, String email, String password) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        PasswordPolicy.check(password, username, normalizedEmail);

        UUID id = Ids.newId();
        String hash = encoder.encode(password);
        try {
            accounts.insertAccount(id, username);
            accounts.insertEmail(Ids.newId(), id, normalizedEmail);
            accounts.insertPassword(id, hash);
            accounts.insertProfile(id, username);
        } catch (DuplicateKeyException e) {
            throw conflictFor(e);
        }
        return new Registered(id, username);
    }

    /**
     * @return the account ID if the identifier and password are right and the account is active
     * @throws ApiException INVALID_CREDENTIALS, with the same message whatever was wrong
     */
    @Transactional(readOnly = true)
    public UUID authenticate(String identifier, String password) {
        var found = accounts.findForLogin(identifier.strip());
        // Always do one hash verification, so unknown accounts cost the same time as wrong passwords.
        boolean passwordOk = encoder.matches(password, found.map(LoginRecord::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || !passwordOk || !"ACTIVE".equals(found.get().status())) {
            throw new ApiException(ProblemType.INVALID_CREDENTIALS, "The identifier or password is incorrect.");
        }
        return found.get().accountId();
    }

    @Transactional(readOnly = true)
    public ProfileRecord profile(UUID accountId) {
        return accounts.findProfile(accountId)
                .orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Account not found."));
    }

    /**
     * Which unique constraint was violated. PostgreSQL's message names it
     * ("... violates unique constraint \"accounts_username_key\""); reading the text keeps the
     * service independent of the JDBC driver. The constraint names are pinned by AuthIntegrationTests.
     */
    private static ApiException conflictFor(DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("accounts_username_key")) {
            return new ApiException(ProblemType.CONFLICT, "That username is already taken.");
        }
        return new ApiException(ProblemType.CONFLICT, "An account with that email already exists.");
    }
}
