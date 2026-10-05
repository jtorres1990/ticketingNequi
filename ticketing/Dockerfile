# Ticketing image (CMP-021; ADR-034, ADR-036; PLAT-INC-005).
# ONE image for both roles: ticketing-api and ticketing-worker run this same image and select the role only
# through the runtime configuration documented by INC-010 (TICKETING_ROLE=api|worker). No role is baked in.
# Built ONLY from ticketing/ with its own Maven Wrapper; independent of payment-mock (no shared code).
# The build stage runs "sh ./mvnw -B verify" (PLAT-IV-006 a): unit, architecture, BlockHound tests and the
# aggregate coverage gate; the *IT suites are only in the "integration" profile (they need a Docker daemon).
# Secrets never enter the image: AWS credentials and the Payment Mock API key are provided at run time only.

FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY domain/pom.xml domain/pom.xml
COPY domain/src/ domain/src/
COPY application/pom.xml application/pom.xml
COPY application/src/ application/src/
COPY infrastructure/pom.xml infrastructure/pom.xml
COPY infrastructure/src/ infrastructure/src/
COPY bootstrap/pom.xml bootstrap/pom.xml
COPY bootstrap/src/ bootstrap/src/
# Maven repository and wrapper distribution in a BuildKit cache mount (never in a layer).
# CR normalization keeps the wrapper runnable even from a CRLF checkout (ticketing/.gitattributes already forces LF).
RUN --mount=type=cache,id=ticketing-m2,target=/root/.m2 \
    sed -i 's/\r$//' mvnw \
    && sh ./mvnw -B -ntp verify

# Health probe: the JRE image has no curl/wget (PLAT-SPK-001), so a tiny JDK probe is compiled here.
COPY <<'EOF' /probe-src/HealthProbe.java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Exit 0 only if every URL answers 200. Prints nothing on success. */
public final class HealthProbe {
    public static void main(String[] urls) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            for (String url : urls) {
                HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200) {
                    System.out.println(response.statusCode() + " " + url);
                    System.exit(1);
                }
            }
            System.exit(urls.length == 0 ? 2 : 0);
        } catch (Exception e) {
            System.out.println("ERR " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
EOF
RUN javac --release 25 -Xlint:all -Werror -d /probe /probe-src/HealthProbe.java

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
RUN groupadd --gid 10001 ticketing \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin ticketing \
    && mkdir -p /app /opt/probe
# Executable artifact of the INC-010 handoff (Spring Boot executable jar of the bootstrap module).
COPY --from=build --chmod=0444 /build/bootstrap/target/ticketing-bootstrap-0.1.0-SNAPSHOT.jar /app/ticketing.jar
COPY --from=build --chmod=0444 /probe/HealthProbe.class /opt/probe/HealthProbe.class
USER 10001:10001
# 8080: application port (API under /api/v1 in the api role; /livez and /readyz in both roles).
# 8081: internal management port (actuator); published to the host only by the local Compose file.
EXPOSE 8080 8081
# Ordered shutdown (ADR-037, INC-010): SIGTERM to the JVM, up to 35 s per phase; the container stop timeout
# must exceed it (stop_grace_period 40 s, PLAT-IV-012).
STOPSIGNAL SIGTERM
# Exec form: the JVM is PID 1 and receives SIGTERM directly. JVM flags approved in PLAT-IV-012.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/ticketing.jar"]
