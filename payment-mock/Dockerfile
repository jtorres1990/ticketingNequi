# Payment Mock image (ADR-030, ADR-034, ADR-036; PLAT-INC-004).
# Built ONLY from payment-mock/ with its own Maven Wrapper; independent of ticketing.
# The build stage runs the complete test suite (PLAT-IV-006 a: "sh ./mvnw -B verify", no -DskipTests).
# Secrets never enter the image: PAYMENT_MOCK_API_KEY is provided at run time only.

FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# Maven repository and wrapper distribution in a BuildKit cache mount (never in a layer).
RUN --mount=type=cache,id=payment-mock-m2,target=/root/.m2 \
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
RUN groupadd --gid 10001 paymentmock \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin paymentmock \
    && mkdir -p /app /opt/probe
COPY --from=build --chmod=0444 /build/target/payment-mock.jar /app/payment-mock.jar
COPY --from=build --chmod=0444 /probe/HealthProbe.class /opt/probe/HealthProbe.class
USER 10001:10001
EXPOSE 8090
# Exec form: the JVM is PID 1 and receives SIGTERM directly.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/payment-mock.jar"]
