# local-idp (ADR-033, PLAT-IV-002 a): minimal OIDC/JWT issuer, Java 25 standard library only.
# Same image runs the modes serve (local-idp), generate (load-token-generator), verify and probe.
# LOCAL PROFILE ONLY. No key material is baked into the image: keys are generated at start-up.

FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /build
COPY src/ src/
RUN javac --release 25 -Xlint:all -Werror -d classes src/LocalIdp.java \
    && jar --create --file local-idp.jar --main-class LocalIdp -C classes .

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
RUN groupadd --gid 10001 localidp \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin localidp \
    && mkdir -p /app /tokens \
    && chown 10001:10001 /tokens
COPY --from=build --chmod=0444 /build/local-idp.jar /app/local-idp.jar
USER 10001:10001
EXPOSE 9000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/local-idp.jar"]
CMD ["serve"]
