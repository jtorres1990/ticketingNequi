import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Local OIDC/JWT issuer for the Ticketing platform (ADR-033, PLAT-IV-002 a, PLAT-IV-003 a).
 *
 * <p>Java standard library only. LOCAL PROFILE ONLY: never configure it in AWS. Keys are generated in
 * memory at start-up and never persisted; restarting the service invalidates every token issued before.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code serve}: discovery, JWKS and {@code POST /token};</li>
 *   <li>{@code generate}: pre-generates the load-test batch of CUSTOMER tokens through {@code /token};</li>
 *   <li>{@code verify}: cryptographic verification of tokens against the published JWKS (never prints tokens);</li>
 *   <li>{@code probe}: HTTP GET health probe used by the container health check.</li>
 * </ul>
 */
public final class LocalIdp {

    static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    static final Set<String> GROUPS = Set.of("ADMIN", "CUSTOMER");
    static final Map<String, List<String>> IDENTITIES = new LinkedHashMap<>();

    static {
        IDENTITIES.put("admin", List.of("ADMIN"));
        IDENTITIES.put("customer-a", List.of("CUSTOMER"));
        IDENTITIES.put("customer-b", List.of("CUSTOMER"));
        IDENTITIES.put("admin-customer", List.of("ADMIN", "CUSTOMER"));
        IDENTITIES.put("no-groups", List.of()); // no cognito:groups claim at all, as Cognito for a user without groups
    }

    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private LocalIdp() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "serve" : args[0];
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        int code = switch (mode) {
            case "serve" -> Serve.run();
            case "generate" -> Generate.run();
            case "verify" -> Verify.run(rest);
            case "probe" -> Probe.run(rest);
            default -> {
                System.err.println("usage: local-idp serve | generate | verify [options] | probe <url>...");
                yield 2;
            }
        };
        if (code != Integer.MIN_VALUE) {
            System.exit(code);
        }
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static long envLong(String name, long fallback, long min, long max) {
        String raw = env(name, Long.toString(fallback));
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    static void log(String message) {
        System.out.println(Instant.now() + " local-idp: " + message);
    }

    // ------------------------------------------------------------------ serve

    static final class Serve {

        static int run() throws Exception {
            String issuer = env("LOCAL_IDP_ISSUER", "http://local-idp:9000");
            String clientId = env("LOCAL_IDP_CLIENT_ID", "ticketing-local-client");
            long maxTtl = envLong("LOCAL_IDP_MAX_TTL_SECONDS", 86_400, 1, 7 * 86_400);
            long defaultTtl = envLong("LOCAL_IDP_DEFAULT_TTL_SECONDS", 3_600, 1, maxTtl);
            int port = (int) envLong("LOCAL_IDP_PORT", 9000, 1, 65_535);
            if (issuer.endsWith("/")) {
                throw new IllegalArgumentException("LOCAL_IDP_ISSUER must not end with '/'");
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) keys.getPublic();
            String n = B64.encodeToString(unsigned(publicKey.getModulus()));
            String e = B64.encodeToString(unsigned(publicKey.getPublicExponent()));
            // RFC 7638 thumbprint as key id.
            String kid = B64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(("{\"e\":\"" + e + "\",\"kty\":\"RSA\",\"n\":\"" + n + "\"}").getBytes(StandardCharsets.UTF_8)));

            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "RSA");
            jwk.put("use", "sig");
            jwk.put("alg", "RS256");
            jwk.put("kid", kid);
            jwk.put("n", n);
            jwk.put("e", e);
            byte[] jwks = Json.write(Map.of("keys", List.of(jwk))).getBytes(StandardCharsets.UTF_8);

            Map<String, Object> discovery = new LinkedHashMap<>();
            discovery.put("issuer", issuer);
            discovery.put("jwks_uri", issuer + "/.well-known/jwks.json");
            discovery.put("token_endpoint", issuer + "/token");
            discovery.put("response_types_supported", List.of("token"));
            discovery.put("subject_types_supported", List.of("public"));
            discovery.put("id_token_signing_alg_values_supported", List.of("RS256"));
            discovery.put("token_endpoint_auth_methods_supported", List.of("none"));
            discovery.put("claims_supported",
                    List.of("sub", "cognito:groups", "token_use", "client_id", "iss", "exp", "iat", "jti"));
            byte[] discoveryBody = Json.write(discovery).getBytes(StandardCharsets.UTF_8);

            Issuer tokenIssuer = new Issuer(issuer, clientId, kid, keys, defaultTtl, maxTtl);
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", exchange -> handle(exchange, discoveryBody, jwks, tokenIssuer));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("stopping");
                server.stop(1);
                executor.shutdown();
            }));
            server.start();
            log("serving on port " + port + " issuer=" + issuer + " client_id=" + clientId + " kid=" + kid
                    + " default_ttl=" + defaultTtl + "s max_ttl=" + maxTtl + "s (local profile only; ephemeral key)");
            return Integer.MIN_VALUE; // keep running
        }

        static void handle(HttpExchange exchange, byte[] discovery, byte[] jwks, Issuer issuer) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                switch (path) {
                    case "/.well-known/openid-configuration" -> {
                        if (!allow(exchange, method, "GET")) {
                            return;
                        }
                        send(exchange, 200, discovery);
                    }
                    case "/.well-known/jwks.json" -> {
                        if (!allow(exchange, method, "GET")) {
                            return;
                        }
                        send(exchange, 200, jwks);
                    }
                    case "/token" -> {
                        if (!allow(exchange, method, "POST")) {
                            return;
                        }
                        token(exchange, issuer);
                    }
                    default -> send(exchange, 404, error("not_found", "unknown path"));
                }
            } catch (RuntimeException ex) {
                log("internal error: " + ex.getClass().getSimpleName());
            }
        }

        static boolean allow(HttpExchange exchange, String method, String allowed) throws IOException {
            if (method.equals(allowed) || (allowed.equals("GET") && method.equals("HEAD"))) {
                return true;
            }
            exchange.getResponseHeaders().add("Allow", allowed);
            send(exchange, 405, error("method_not_allowed", "use " + allowed));
            return false;
        }

        static void token(HttpExchange exchange, Issuer issuer) throws IOException {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
                send(exchange, 400, error("invalid_request", "Content-Type must be application/x-www-form-urlencoded"));
                return;
            }
            byte[] raw = exchange.getRequestBody().readNBytes(8193);
            if (raw.length > 8192) {
                send(exchange, 400, error("invalid_request", "request body too large"));
                return;
            }
            Map<String, String> form;
            try {
                form = parseForm(new String(raw, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ex) {
                send(exchange, 400, error("invalid_request", ex.getMessage()));
                return;
            }
            String identity = form.get("identity");
            String subject = form.get("sub");
            String groupsParam = form.get("groups");
            List<String> groups;
            if (identity != null) {
                if (subject != null || groupsParam != null) {
                    send(exchange, 400, error("invalid_request", "use either identity or sub/groups"));
                    return;
                }
                groups = IDENTITIES.get(identity);
                if (groups == null) {
                    send(exchange, 400, error("invalid_request", "identity must be one of " + IDENTITIES.keySet()));
                    return;
                }
                subject = identity;
            } else {
                if (subject == null || !SUBJECT.matcher(subject).matches()) {
                    send(exchange, 400, error("invalid_request", "sub must match [A-Za-z0-9._-]{1,128}"));
                    return;
                }
                groups = new ArrayList<>();
                if (groupsParam != null && !groupsParam.isBlank()) {
                    for (String group : groupsParam.split(",")) {
                        String g = group.trim();
                        if (!GROUPS.contains(g)) {
                            send(exchange, 400, error("invalid_request", "groups must be a subset of ADMIN,CUSTOMER"));
                            return;
                        }
                        if (!groups.contains(g)) {
                            groups.add(g);
                        }
                    }
                }
            }
            long ttl = issuer.defaultTtl;
            String expiresIn = form.get("expires_in");
            if (expiresIn != null) {
                try {
                    ttl = Long.parseLong(expiresIn);
                } catch (NumberFormatException ex) {
                    ttl = -1;
                }
                if (ttl < 1 || ttl > issuer.maxTtl) {
                    send(exchange, 400, error("invalid_request", "expires_in must be between 1 and " + issuer.maxTtl));
                    return;
                }
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("access_token", issuer.issue(subject, groups, ttl));
            response.put("token_type", "Bearer");
            response.put("expires_in", ttl);
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            send(exchange, 200, Json.write(response).getBytes(StandardCharsets.UTF_8));
        }

        static Map<String, String> parseForm(String body) {
            Map<String, String> form = new LinkedHashMap<>();
            if (body.isBlank()) {
                return form;
            }
            for (String pair : body.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (form.put(key, value) != null) {
                    throw new IllegalArgumentException("duplicated parameter " + key);
                }
            }
            return form;
        }

        static byte[] error(String code, String description) {
            return Json.write(Map.of("error", code, "error_description", description)).getBytes(StandardCharsets.UTF_8);
        }

        static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            boolean head = exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(status, head ? -1 : body.length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    /** Signs RS256 access tokens with the claims of an Amazon Cognito access token. */
    static final class Issuer {
        final String issuer;
        final String clientId;
        final String kid;
        final KeyPair keys;
        final long defaultTtl;
        final long maxTtl;

        Issuer(String issuer, String clientId, String kid, KeyPair keys, long defaultTtl, long maxTtl) {
            this.issuer = issuer;
            this.clientId = clientId;
            this.kid = kid;
            this.keys = keys;
            this.defaultTtl = defaultTtl;
            this.maxTtl = maxTtl;
        }

        String issue(String subject, List<String> groups, long ttl) {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("kid", kid);
            header.put("alg", "RS256");
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", subject);
            if (!groups.isEmpty()) {
                claims.put("cognito:groups", groups);
            }
            claims.put("iss", issuer);
            claims.put("client_id", clientId);
            claims.put("token_use", "access");
            claims.put("iat", now);
            claims.put("exp", now + ttl);
            claims.put("jti", UUID.randomUUID().toString());
            String signingInput = B64.encodeToString(Json.write(header).getBytes(StandardCharsets.UTF_8)) + "."
                    + B64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
            try {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(keys.getPrivate());
                signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                return signingInput + "." + B64.encodeToString(signature.sign());
            } catch (Exception ex) {
                throw new IllegalStateException("signing failed", ex);
            }
        }
    }

    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    // --------------------------------------------------------------- generate

    /** Pre-generates the CUSTOMER token batch of the load profile (ADR-033, ADR-036, PLAT-IV-009 a). */
    static final class Generate {

        static int run() throws Exception {
            String idp = env("LOCAL_IDP_URL", "http://local-idp:9000");
            int count = (int) envLong("LOAD_TOKEN_COUNT", 1_200, 1_001, 100_000);
            long ttl = envLong("LOAD_TOKEN_TTL_SECONDS", 7_200, 60, 7 * 86_400);
            Path dir = Path.of(env("LOAD_TOKEN_OUTPUT_DIR", "/tokens"));
            String batch = env("LOAD_TOKEN_BATCH", Long.toString(Instant.now().getEpochSecond(), 36));
            if (!SUBJECT.matcher("load-customer-" + batch + "-" + count).matches()) {
                throw new IllegalArgumentException("LOAD_TOKEN_BATCH must be short and match [A-Za-z0-9._-]");
            }
            log("generating " + count + " CUSTOMER tokens, batch=" + batch + ", ttl=" + ttl + "s, via " + idp + "/token");
            long started = System.nanoTime();
            Instant issuedAt = Instant.now();

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            Semaphore inFlight = new Semaphore(32);
            String[] subjects = new String[count];
            String[] tokens = new String[count];
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Void>> futures = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    subjects[i] = String.format("load-customer-%s-%05d", batch, i + 1);
                    futures.add(executor.submit((Callable<Void>) () -> {
                        inFlight.acquire();
                        try {
                            tokens[index] = requestToken(client, idp, subjects[index], ttl);
                        } finally {
                            inFlight.release();
                        }
                        return null;
                    }));
                }
                for (Future<Void> future : futures) {
                    future.get();
                }
            }

            Set<String> distinctSubjects = new HashSet<>();
            Set<String> distinctJti = new HashSet<>();
            String issuer = null;
            String clientId = null;
            long minExp = Long.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                Map<String, Object> claims = Jwt.claims(tokens[i]);
                if (!subjects[i].equals(claims.get("sub")) || !List.of("CUSTOMER").equals(claims.get("cognito:groups"))) {
                    throw new IllegalStateException("unexpected claims for token " + (i + 1));
                }
                distinctSubjects.add((String) claims.get("sub"));
                distinctJti.add((String) claims.get("jti"));
                issuer = (String) claims.get("iss");
                clientId = (String) claims.get("client_id");
                minExp = Math.min(minExp, ((Number) claims.get("exp")).longValue());
            }
            if (distinctSubjects.size() != count || distinctJti.size() != count) {
                throw new IllegalStateException("tokens are not distinct");
            }

            Files.createDirectories(dir);
            Path csvTmp = dir.resolve(".customer-tokens.csv.tmp");
            StringBuilder csv = new StringBuilder(count * 900).append("sub,access_token\n");
            for (int i = 0; i < count; i++) {
                csv.append(subjects[i]).append(',').append(tokens[i]).append('\n');
            }
            Files.writeString(csvTmp, csv.toString(), StandardCharsets.UTF_8);
            readableByAnyUid(csvTmp);
            Files.move(csvTmp, dir.resolve("customer-tokens.csv"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("count", count);
            manifest.put("distinct_subjects", distinctSubjects.size());
            manifest.put("group", "CUSTOMER");
            manifest.put("subject_pattern", "load-customer-" + batch + "-NNNNN");
            manifest.put("batch", batch);
            manifest.put("issuer", issuer);
            manifest.put("client_id", clientId);
            manifest.put("issued_at", issuedAt.toString());
            manifest.put("expires_at", Instant.ofEpochSecond(minExp).toString());
            manifest.put("ttl_seconds", ttl);
            manifest.put("file", "customer-tokens.csv");
            manifest.put("format", "CSV with header sub,access_token");
            manifest.put("generation_ms", elapsedMs);
            Path manifestTmp = dir.resolve(".manifest.json.tmp");
            Files.writeString(manifestTmp, Json.write(manifest) + "\n", StandardCharsets.UTF_8);
            readableByAnyUid(manifestTmp);
            Files.move(manifestTmp, dir.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log("generated " + count + " distinct tokens (distinct sub=" + distinctSubjects.size() + ", distinct jti="
                    + distinctJti.size() + ") in " + elapsedMs + " ms; expires_at=" + Instant.ofEpochSecond(minExp)
                    + "; files: " + dir.resolve("customer-tokens.csv") + ", " + dir.resolve("manifest.json"));
            return 0;
        }

        static void readableByAnyUid(Path file) throws IOException {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }

        static String requestToken(HttpClient client, String idp, String subject, long ttl) throws Exception {
            String form = "sub=" + URLEncoder.encode(subject, StandardCharsets.UTF_8) + "&groups=CUSTOMER&expires_in=" + ttl;
            HttpRequest request = HttpRequest.newBuilder(URI.create(idp + "/token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("token endpoint answered " + response.statusCode() + " for " + subject);
            }
            Object token = ((Map<?, ?>) Json.parse(response.body())).get("access_token");
            if (!(token instanceof String value)) {
                throw new IllegalStateException("token endpoint answered without access_token");
            }
            return value;
        }
    }

    // ----------------------------------------------------------------- verify

    /**
     * Verifies tokens cryptographically with the keys published at the JWKS URL. Tokens are read from stdin
     * (one per line), from a batch CSV ({@code --csv}) or requested from the issuer ({@code --issue <form>}).
     * Tokens are never printed.
     */
    static final class Verify {

        static int run(String[] args) throws Exception {
            String jwksUrl = "http://local-idp:9000/.well-known/jwks.json";
            String expectedIssuer = env("LOCAL_IDP_ISSUER", "http://local-idp:9000");
            String clientId = env("LOCAL_IDP_CLIENT_ID", "ticketing-local-client");
            String tokenUrl = "http://local-idp:9000/token";
            String csv = null;
            List<String> issue = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--jwks" -> jwksUrl = args[++i];
                    case "--issuer" -> expectedIssuer = args[++i];
                    case "--client-id" -> clientId = args[++i];
                    case "--token-url" -> tokenUrl = args[++i];
                    case "--csv" -> csv = args[++i];
                    case "--issue" -> issue.add(args[++i]);
                    default -> {
                        System.err.println("unknown option " + args[i]);
                        return 2;
                    }
                }
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String jwksBody = get(client, jwksUrl);
            Map<String, PublicKey> keys = keys(jwksBody);
            System.out.println("JWKS " + jwksUrl + " keys=" + keys.keySet() + " sha256=" + sha256(jwksBody));

            List<String[]> tokens = new ArrayList<>(); // [label, token]
            if (csv != null) {
                List<String> lines = Files.readAllLines(Path.of(csv), StandardCharsets.UTF_8);
                if (lines.isEmpty() || !lines.getFirst().equals("sub,access_token")) {
                    System.out.println("INVALID csv header");
                    return 1;
                }
                for (String line : lines.subList(1, lines.size())) {
                    int comma = line.indexOf(',');
                    tokens.add(new String[] {line.substring(0, comma), line.substring(comma + 1)});
                }
            } else if (!issue.isEmpty()) {
                for (String form : issue) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        System.out.println("ISSUE-FAILED " + form + " status=" + response.statusCode() + " body=" + response.body());
                        return 1;
                    }
                    tokens.add(new String[] {form, (String) ((Map<?, ?>) Json.parse(response.body())).get("access_token")});
                }
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                int n = 0;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        tokens.add(new String[] {"stdin#" + (++n), line});
                    }
                }
            }

            boolean batch = csv != null;
            int valid = 0;
            Set<String> subjects = new HashSet<>();
            Set<String> jtis = new HashSet<>();
            for (String[] entry : tokens) {
                String result = check(entry[1], keys, expectedIssuer, clientId);
                boolean ok = result.startsWith("VALID");
                if (ok) {
                    valid++;
                    Map<String, Object> claims = Jwt.claims(entry[1]);
                    subjects.add(String.valueOf(claims.get("sub")));
                    jtis.add(String.valueOf(claims.get("jti")));
                    if (batch && !entry[0].equals(claims.get("sub"))) {
                        result = "INVALID csv sub does not match token sub";
                        valid--;
                        ok = false;
                    }
                }
                if (!batch || !ok) {
                    System.out.println(result + "  [" + entry[0] + "]");
                }
            }
            System.out.println("SUMMARY tokens=" + tokens.size() + " valid=" + valid + " distinct_sub=" + subjects.size()
                    + " distinct_jti=" + jtis.size());
            return valid == tokens.size() && !tokens.isEmpty() ? 0 : 1;
        }

        static String check(String token, Map<String, PublicKey> keys, String expectedIssuer, String clientId) {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                return "INVALID not a JWS compact token";
            }
            try {
                Map<?, ?> header = (Map<?, ?>) Json.parse(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
                if (!"RS256".equals(header.get("alg"))) {
                    return "INVALID alg " + header.get("alg");
                }
                PublicKey key = keys.get(String.valueOf(header.get("kid")));
                if (key == null) {
                    return "INVALID unknown kid";
                }
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initVerify(key);
                signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if (!signature.verify(B64D.decode(parts[2]))) {
                    return "INVALID signature";
                }
                Map<String, Object> claims = Jwt.claims(token);
                long now = Instant.now().getEpochSecond();
                long exp = ((Number) claims.get("exp")).longValue();
                if (!expectedIssuer.equals(claims.get("iss"))) {
                    return "INVALID iss " + claims.get("iss");
                }
                if (exp <= now) {
                    return "INVALID expired " + (now - exp) + "s ago";
                }
                if (!"access".equals(claims.get("token_use"))) {
                    return "INVALID token_use " + claims.get("token_use");
                }
                if (!clientId.equals(claims.get("client_id"))) {
                    return "INVALID client_id " + claims.get("client_id");
                }
                Object groups = claims.containsKey("cognito:groups") ? claims.get("cognito:groups") : "absent";
                return "VALID sub=" + claims.get("sub") + " cognito:groups=" + groups + " token_use=access client_id="
                        + claims.get("client_id") + " iss=" + claims.get("iss") + " exp_in=" + (exp - now) + "s iat="
                        + claims.get("iat") + " jti=" + String.valueOf(claims.get("jti")).substring(0, 8) + "... aud="
                        + (claims.containsKey("aud") ? claims.get("aud") : "absent");
            } catch (Exception ex) {
                return "INVALID " + ex.getClass().getSimpleName();
            }
        }

        static Map<String, PublicKey> keys(String jwks) throws Exception {
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            for (Object item : (List<?>) ((Map<?, ?>) Json.parse(jwks)).get("keys")) {
                Map<?, ?> jwk = (Map<?, ?>) item;
                if (!"RSA".equals(jwk.get("kty"))) {
                    continue;
                }
                BigInteger n = new BigInteger(1, B64D.decode((String) jwk.get("n")));
                BigInteger e = new BigInteger(1, B64D.decode((String) jwk.get("e")));
                keys.put((String) jwk.get("kid"), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
            }
            return keys;
        }
    }

    // ------------------------------------------------------------------ probe

    /** GETs every URL; exit 0 only if all answer 200. Prints status and body SHA-256 (no body). */
    static final class Probe {

        static int run(String[] urls) {
            if (urls.length == 0) {
                System.err.println("usage: probe <url>...");
                return 2;
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            int failures = 0;
            for (String url : urls) {
                try {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    System.out.println(response.statusCode() + " " + url + " sha256=" + sha256(response.body()));
                    if (response.statusCode() != 200) {
                        failures++;
                    }
                } catch (Exception ex) {
                    System.out.println("ERR " + url + " " + ex.getClass().getSimpleName());
                    failures++;
                }
            }
            return failures == 0 ? 0 : 1;
        }
    }

    static String get(HttpClient client, String url) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(url + " answered " + response.statusCode());
        }
        return response.body();
    }

    static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // -------------------------------------------------------------------- JWT

    static final class Jwt {
        @SuppressWarnings("unchecked")
        static Map<String, Object> claims(String token) {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("not a JWS compact token");
            }
            return (Map<String, Object>) Json.parse(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------- JSON

    /** Minimal JSON writer/parser (objects, arrays, strings, integers, booleans, null). */
    static final class Json {

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(out, value);
            return out.toString();
        }

        private static void write(StringBuilder out, Object value) {
            switch (value) {
                case null -> out.append("null");
                case String s -> string(out, s);
                case Number n -> out.append(n);
                case Boolean b -> out.append(b);
                case Map<?, ?> map -> {
                    out.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        string(out, String.valueOf(entry.getKey()));
                        out.append(':');
                        write(out, entry.getValue());
                    }
                    out.append('}');
                }
                case List<?> list -> {
                    out.append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            out.append(',');
                        }
                        write(out, list.get(i));
                    }
                    out.append(']');
                }
                default -> throw new IllegalArgumentException("unsupported JSON value " + value.getClass());
            }
        }

        private static void string(StringBuilder out, String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }

        static Object parse(String text) {
            Parser parser = new Parser(text);
            Object value = parser.value();
            parser.skip();
            if (parser.pos != text.length()) {
                throw new IllegalArgumentException("trailing JSON content");
            }
            return value;
        }

        private static final class Parser {
            final String s;
            int pos;

            Parser(String s) {
                this.s = s;
            }

            void skip() {
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                    pos++;
                }
            }

            Object value() {
                skip();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unexpected end of JSON");
                }
                char c = s.charAt(pos);
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            Map<String, Object> object() {
                Map<String, Object> map = new LinkedHashMap<>();
                pos++;
                skip();
                if (s.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    skip();
                    String key = string();
                    skip();
                    expect(':');
                    map.put(key, value());
                    skip();
                    if (s.charAt(pos) == ',') {
                        pos++;
                    } else {
                        expect('}');
                        return map;
                    }
                }
            }

            List<Object> array() {
                List<Object> list = new ArrayList<>();
                pos++;
                skip();
                if (s.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    skip();
                    if (s.charAt(pos) == ',') {
                        pos++;
                    } else {
                        expect(']');
                        return list;
                    }
                }
            }

            String string() {
                expect('"');
                StringBuilder out = new StringBuilder();
                while (true) {
                    char c = s.charAt(pos++);
                    if (c == '"') {
                        return out.toString();
                    }
                    if (c == '\\') {
                        char e = s.charAt(pos++);
                        switch (e) {
                            case 'u' -> {
                                out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            case 'n' -> out.append('\n');
                            case 'r' -> out.append('\r');
                            case 't' -> out.append('\t');
                            case 'b' -> out.append('\b');
                            case 'f' -> out.append('\f');
                            default -> out.append(e);
                        }
                    } else {
                        out.append(c);
                    }
                }
            }

            Object number() {
                int start = pos;
                while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                    pos++;
                }
                String n = s.substring(start, pos);
                if (n.isEmpty()) {
                    throw new IllegalArgumentException("invalid JSON at " + start);
                }
                return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n)
                        : (Object) Long.parseLong(n);
            }

            Object literal(String word, Object value) {
                if (!s.startsWith(word, pos)) {
                    throw new IllegalArgumentException("invalid JSON literal at " + pos);
                }
                pos += word.length();
                return value;
            }

            void expect(char c) {
                if (pos >= s.length() || s.charAt(pos) != c) {
                    throw new IllegalArgumentException("expected '" + c + "' at " + pos);
                }
                pos++;
            }
        }
    }
}
