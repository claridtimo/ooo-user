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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

import static com.threerings.user.Log.log;

/**
 * Manages game client interaction with the authosaurus authentication service. Provides methods to
 * start/stop a local HTTP listener for receiving session tokens, initiate login, and query
 * accounts.
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
     */
    public String getSessionToken ()
    {
        return _prefs.get(PREF_SESSION_TOKEN, null);
    }

    /**
     * Registers a callback that will be notified when a new session token is received by the auth
     * listener. The callback may be invoked on the HTTP server's request-handling thread; the
     * caller is responsible for transferring control to the appropriate UI thread.
     */
    public void onSessionStarted (Consumer<String> callback)
    {
        _listeners.add(callback);
    }

    /**
     * Starts a local HTTP listener for receiving the session token delivered during the login
     * flow. A random port in the range 49152–65535 is chosen on first use and persisted via Java
     * preferences so that the same port is reused for future login sessions.
     *
     * @return the port on which the listener is accepting connections.
     */
    public int startAuthListener ()
    {
        int port = getOrCreateListenerPort();

        if (_server != null) {
            log.warning("Auth listener already running on port " + port);
            return port;
        }

        try {
            _server = new NanoHTTPD("127.0.0.1", port) {
                @Override public Response serve (IHTTPSession session) {
                    if (!"/auth".equals(session.getUri())) {
                        return newFixedLengthResponse(Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found");
                    }
                    return handleAuthRequest(session);
                }
            };
            _server.start();
            log.info("Auth listener started on port " + port);
        } catch (IOException e) {
            log.warning("Failed to start auth listener on port " + port, e);
        }

        return port;
    }

    /**
     * Stops any active auth listener. If the listener is not running, logs a warning and no-ops.
     */
    public void stopAuthListener ()
    {
        if (_server == null) {
            log.warning("Auth listener is not running.");
            return;
        }

        _server.stop();
        _server = null;
        log.info("Auth listener stopped.");
    }

    /**
     * Initiates a login session by calling the authosaurus /login endpoint with the supplied email
     * address and port.
     *
     * @param email the user's email address.
     * @param port the local listener port to which the session token should be delivered.
     * @throws AuthException if the login request fails.
     */
    public void startLogin (String email, int port) throws AuthException
    {
        try {
            URL url = new URL(_authBaseUrl + "/login");
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            log.info("Requesting login link", "url", url);

            String json = "{\"email\":" + jsonString(email) + ",\"port\":" + port + "}";
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

    private NanoHTTPD.Response handleAuthRequest (NanoHTTPD.IHTTPSession session)
    {
        NanoHTTPD.Method method = session.getMethod();

        // Handle CORS preflight
        if (NanoHTTPD.Method.OPTIONS.equals(method)) {
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, "");
            addCorsHeaders(response);
            return response;
        }

        if (!NanoHTTPD.Method.POST.equals(method)) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.METHOD_NOT_ALLOWED, NanoHTTPD.MIME_PLAINTEXT, "Method not allowed");
        }

        String token;
        try {
            // NanoHTTPD requires parsing body into files map for POST
            Map<String, String> bodyMap = new java.util.HashMap<>();
            session.parseBody(bodyMap);
            // For plain text/non-form POSTs, the body is in "postData"
            token = bodyMap.getOrDefault("postData", "").trim();
        } catch (IOException | NanoHTTPD.ResponseException e) {
            log.warning("Failed to read auth request body", e);
            return NanoHTTPD.newFixedLengthResponse(
                Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Error reading body");
        }

        if (token.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "Empty token");
        }

        // Store the session token
        _prefs.put(PREF_SESSION_TOKEN, token);
        log.info("Session token received and stored.");

        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
            Status.OK, NanoHTTPD.MIME_PLAINTEXT, "");
        addCorsHeaders(response);

        // Notify listeners
        for (Consumer<String> listener : _listeners) {
            try {
                listener.accept(token);
            } catch (Exception e) {
                log.warning("Error in session listener", e);
            }
        }

        return response;
    }

    private static void addCorsHeaders (NanoHTTPD.Response response)
    {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    private int getOrCreateListenerPort ()
    {
        int port = _prefs.getInt(PREF_LISTENER_PORT, -1);
        if (port == -1) {
            port = 49152 + (int)(Math.random() * (65535 - 49152 + 1));
            _prefs.putInt(PREF_LISTENER_PORT, port);
        }
        return port;
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
    private NanoHTTPD _server;

    private static final String PREF_LISTENER_PORT = "authListenerPort";
    private static final String PREF_SESSION_TOKEN = "sessionToken";
}
