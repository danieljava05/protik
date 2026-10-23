package com.protik.identity.web;

import com.protik.identity.service.DeletionService;
import com.protik.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Starts deleting the caller's account (ADR 0020). */
@RestController
class DeletionController {

    record DeleteRequest(@NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "DeleteRequest[password=<redacted>]";
        }
    }

    record Accepted(UUID requestId) {
    }

    private final DeletionService deletion;

    DeletionController(DeletionService deletion) {
        this.deletion = deletion;
    }

    /** 202: the account is unusable now; its data is removed module by module. */
    @PostMapping("/v1/me/deletion")
    ResponseEntity<Accepted> delete(@AuthenticationPrincipal Jwt token, @Valid @RequestBody DeleteRequest request) {
        UUID id = deletion.request(AuthenticatedUser.id(token), request.password());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new Accepted(id));
    }
}
