package com.threerings.user;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import static com.threerings.user.Log.log;

/**
 * Manages game client interaction with the authosaurus authentication service. Provides methods to
 * initiate login via the browser, poll for the resulting session token, and query accounts.
 */
public class AuthosaurusClient
{
    /**
     * Creates an authosaurus client that communicates with the specified base URL.
     *
     * @param authBaseUrl the base URL of the authosaurus server (e.g. "https://auth.puzzlepirates.com").
     */
    public AuthosaurusClient (String authBaseUrl)
    {
        _authBaseUrl = authBaseUrl.endsWith("/")
            ? authBaseUrl.substring(0, authBaseUrl.length() - 1) : authBaseUrl;
    }

    /**
     * Returns any currently saved session token, or null if no token has been stored.
     * If token persistence is disabled, this always returns null (the token is only available
     * in-memory via the session listener).
     */
    public String getSessionToken ()
    {
        return _persistToken ? _prefs.get(PREF_SESSION_TOKEN, null) : null;
    }

    /**
     * Sets whether the session token should be persisted to Java Preferences. When set to false,
     * the token is only delivered via the session listener and is not saved across client restarts.
     * Defaults to true.
     *
     * <p>If persistence is disabled and a token is already stored in preferences, it is removed.</p>
     *
     * @param persist true to save tokens to preferences, false for in-memory only.
     */
    public void setPersistToken (boolean persist)
    {
        _persistToken = persist;
        if (!persist) {
            _prefs.remove(PREF_SESSION_TOKEN);
        }
    }

    /**
     * Returns whether token persistence is enabled.
     */
    public boolean getPersistToken ()
    {
        return _persistToken;
    }

    /**
     * Registers a callback that will be notified when a new session token is received via polling.
     * The callback will be invoked on the polling thread; the caller is responsible for
     * transferring control to the appropriate UI thread.
     */
    public void onSessionStarted (Consumer<String> callback)
    {
        _listeners.add(callback);
    }

    /**
     * Generates a new random game id for this login session. This id is used to correlate the
     * browser login flow with this game client's poll requests.
     *
     * @return the generated game id.
     */
    public String generateGameId ()
    {
        _gameId = UUID.randomUUID().toString();
        return _gameId;
    }

    /**
     * Returns the current game id, or null if {@link #generateGameId} has not been called.
     */
    public String getGameId ()
    {
        return _gameId;
    }

    /**
     * Starts polling the authosaurus server for a session token associated with the current game
     * id. Polling runs on a background daemon thread and stops automatically once a token is
     * received or the login link expires.
     *
     * <p>When a token is received, it is stored in preferences and all registered listeners are
     * notified.</p>
     *
     * @throws IllegalStateException if no game id has been generated via {@link #generateGameId}.
     */
    public void startPolling ()
    {
        if (_gameId == null) {
            throw new IllegalStateException("Call generateGameId() before startPolling().");
        }

        stopPolling();

        _polling = true;
        Thread thread = new Thread(this::pollLoop, "AuthosaurusPoll");
        thread.setDaemon(true);
        thread.start();
        log.info("Started polling for game auth token", "gameId", _gameId);
    }

    /**
     * Stops any active polling.
     */
    public void stopPolling ()
    {
        _polling = false;
    }

    /**
     * Initiates a login session by calling the authosaurus /login endpoint with the supplied email
     * address and game id.
     *
     * @param email the user's email address.
     * @param gameId the game id to associate with this login request.
     * @throws AuthException if the login request fails.
     */
    public void startLogin (String email, String gameId) throws AuthException
    {
        try {
            URL url = new URL(_authBaseUrl + "/login");
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            log.info("Requesting login link", "url", url);

            String json = "{\"email\":" + jsonString(email) + ",\"id\":" + jsonString(gameId) + "}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                return; // success
            }

            // Read the error response body
            String body = readResponseBody(conn, /* error */ true);
            throw mapErrorResponse(status, body);
        } catch (AuthException ae) {
            throw ae;
        } catch (IOException e) {
            throw new AuthException(AuthException.NETWORK_ERROR, e);
        }
    }

    /**
     * Makes a synchronous HTTP request to the authosaurus /accounts endpoint to obtain the list of
     * account names authorized by the supplied session token.
     *
     * @param token the session token.
     * @return the list of account usernames.
     * @throws AuthException if a network error occurs or the endpoint returns an error.
     */
    public List<String> getAccounts (String token) throws AuthException
    {
        return fetchAccounts(_authBaseUrl, token);
    }

    /**
     * Fetches the list of accounts for the given token from the specified authosaurus base URL.
     * Shared between client and manager.
     */
    static List<String> fetchAccounts (String baseUrl, String token) throws AuthException
    {
        try {
            URL url = new URL(baseUrl + "/accounts/" + token);
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestMethod("GET");
            log.info("Requesting session accounts", "url", url);

            int status = conn.getResponseCode();
            String body = readResponseBody(conn, status >= 400);

            if (status == 200) {
                List<String> accounts = new ArrayList<>();
                for (String line : body.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        accounts.add(trimmed);
                    }
                }
                return accounts;
            }

            // Map known error responses
            if (status == 404) {
                throw new AuthException(AuthException.INVALID_TOKEN);
            } else if (status == 410) {
                throw new AuthException(AuthException.TOKEN_EXPIRED);
            } else {
                throw new AuthException(AuthException.UNEXPECTED_ERROR);
            }
        } catch (IOException e) {
            throw new AuthException(AuthException.NETWORK_ERROR, e);
        }
    }

    private void pollLoop ()
    {
        while (_polling) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!_polling) break;

            try {
                URL url = new URL(_authBaseUrl + "/api/auth/game-poll?id=" + _gameId);
                HttpURLConnection conn = (HttpURLConnection)url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);

                int status = conn.getResponseCode();
                String body = readResponseBody(conn, status >= 400);

                if (status == 410) {
                    // Login expired
                    log.info("Game auth poll: login expired.");
                    _polling = false;
                    break;
                }

                if (status != 200) {
                    log.warning("Game auth poll: unexpected status " + status);
                    continue;
                }

                // Parse "status" and "token" from the JSON response.
                // The response is simple enough to parse without a JSON library.
                String pollStatus = extractJsonString(body, "status");
                if ("complete".equals(pollStatus)) {
                    String token = extractJsonString(body, "token");
                    if (token != null && !token.isEmpty()) {
                        if (_persistToken) {
                            _prefs.put(PREF_SESSION_TOKEN, token);
                            log.info("Session token received via polling and stored.");
                        } else {
                            log.info("Session token received via polling (not persisted).");
                        }
                        _polling = false;

                        for (Consumer<String> listener : _listeners) {
                            try {
                                listener.accept(token);
                            } catch (Exception e) {
                                log.warning("Error in session listener", e);
                            }
                        }
                        break;
                    }
                }
                // status == "pending", keep polling
            } catch (Exception e) {
                log.warning("Game auth poll error", e);
            }
        }
    }

    /**
     * Extracts a string value for the given key from a simple JSON object.
     * Only handles flat objects with string values — sufficient for our poll response.
     */
    private static String extractJsonString (String json, String key)
    {
        String needle = "\"" + key + "\"";
        int keyIdx = json.indexOf(needle);
        if (keyIdx == -1) return null;
        int colonIdx = json.indexOf(':', keyIdx + needle.length());
        if (colonIdx == -1) return null;
        int openQuote = json.indexOf('"', colonIdx + 1);
        if (openQuote == -1) return null;
        int closeQuote = json.indexOf('"', openQuote + 1);
        if (closeQuote == -1) return null;
        return json.substring(openQuote + 1, closeQuote);
    }

    private static AuthException mapErrorResponse (int status, String body)
    {
        if (status == 404 && body != null && body.contains("No accounts")) {
            return new AuthException(AuthException.UNKNOWN_EMAIL_ADDRESS);
        } else if (status == 429) {
            return new AuthException(AuthException.RATE_LIMITED);
        } else {
            return new AuthException(AuthException.UNEXPECTED_ERROR);
        }
    }

    private static String readResponseBody (HttpURLConnection conn, boolean error)
        throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                 error ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /** Produce a JSON-encoded string value. */
    private static String jsonString (String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private final String _authBaseUrl;
    private final Preferences _prefs = Preferences.userNodeForPackage(AuthosaurusClient.class);
    private final CopyOnWriteArrayList<Consumer<String>> _listeners = new CopyOnWriteArrayList<>();

    private String _gameId;
    private volatile boolean _polling;
    private volatile boolean _persistToken = true;

    private static final String PREF_SESSION_TOKEN = "sessionToken";
    private static final long POLL_INTERVAL_MS = 3000;
}
