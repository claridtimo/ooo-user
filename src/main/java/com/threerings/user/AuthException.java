package com.threerings.user;

/**
 * Exception thrown by authosaurus client and server classes to communicate authentication errors.
 */
public class AuthException extends Exception
{
    /** The email address is not associated with any accounts. */
    public static final String UNKNOWN_EMAIL_ADDRESS = "e.unknown_email_address";

    /** The session token has expired. */
    public static final String TOKEN_EXPIRED = "e.token_expired";

    /** The session token is invalid. */
    public static final String INVALID_TOKEN = "e.invalid_token";

    /** No accounts were found for the session. */
    public static final String NO_ACCOUNTS = "e.no_accounts";

    /** The specified account is not authorized by the session token. */
    public static final String UNAUTHORIZED_ACCOUNT = "e.unauthorized_account";

    /** Too many login attempts; rate limited. */
    public static final String RATE_LIMITED = "e.rate_limited";

    /** A network error occurred while communicating with the auth server. */
    public static final String NETWORK_ERROR = "e.network_error";

    /** An unexpected error occurred. */
    public static final String UNEXPECTED_ERROR = "e.unexpected_error";

    public AuthException (String code)
    {
        super(code);
    }

    public AuthException (String code, Throwable cause)
    {
        super(code, cause);
    }

    /** Returns the error code for this exception (same as {@link #getMessage()}). */
    public String getCode ()
    {
        return getMessage();
    }
}
