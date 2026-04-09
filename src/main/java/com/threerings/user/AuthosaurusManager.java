package com.threerings.user;

import java.util.List;

import static com.threerings.user.Log.log;

/**
 * Handles server-side validation of session token + account name authentication provided by game
 * clients against the authosaurus service.
 */
public class AuthosaurusManager
{
    /**
     * Creates a manager that validates sessions against the specified authosaurus server.
     *
     * @param authBaseUrl the base URL of the authosaurus server
     *        (e.g. "https://auth.puzzlepirates.com").
     */
    public AuthosaurusManager (String authBaseUrl)
    {
        _authBaseUrl = authBaseUrl.endsWith("/")
            ? authBaseUrl.substring(0, authBaseUrl.length() - 1) : authBaseUrl;
    }

    /**
     * Validates that the specified account name is authorized by the supplied session token.
     * Operates synchronously by calling the authosaurus /accounts endpoint.
     *
     * @param token the session token provided by the client.
     * @param accountName the account name the client claims to be.
     * @throws AuthException if the token is invalid, expired, or does not authorize the specified
     *         account, or if a network error occurs.
     */
    public void validateSession (String token, String accountName) throws AuthException
    {
        List<String> accounts = AuthosaurusClient.fetchAccounts(_authBaseUrl, token);

        for (String account : accounts) {
            if (account.equalsIgnoreCase(accountName)) {
                return; // validated successfully
            }
        }

        throw new AuthException(AuthException.UNAUTHORIZED_ACCOUNT);
    }

    private final String _authBaseUrl;
}
