package com.banking.userservice.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The authorization vocabulary of this service, as meta-annotations.
 *
 * <p>Writing {@code @AdminOnly} instead of {@code @PreAuthorize("hasRole('ADMIN')")} on every
 * method keeps the rules readable, typo-free (a misspelled role in a SpEL string would silently
 * deny everyone), and changeable in one place.
 *
 * <p>Roles answer "what KIND of user are you?". They never answer "is this YOUR profile?".
 * Ownership ("my own profile") comes from the caller's {@link CurrentUserId}.
 */
public final class Authorization {

    private Authorization() {
    }

    /** Self-service endpoints: only bank customers, acting on their own data. */
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @PreAuthorize("hasRole('CUSTOMER')")
    public @interface CustomerOnly {
    }

    /** Back-office reads: support agents and administrators. */
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    public @interface StaffReadAccess {
    }

    /** Back-office writes: administrators only. */
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @PreAuthorize("hasRole('ADMIN')")
    public @interface AdminOnly {
    }

    /**
     * Injects the caller's user ID: the {@code sub} claim of the VALIDATED token.
     * Never take the "who am I" from a request parameter or body, because the client controls those.
     */
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @AuthenticationPrincipal(expression = "subject")
    public @interface CurrentUserId {
    }
}
