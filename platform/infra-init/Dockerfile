# infra-init (ADR-036, PLAT-IV-004 a): official AWS CLI v2 pinned by tag and digest,
# plus POSIX scripts and literal resource definitions. Runs as an unprivileged user.
FROM amazon/aws-cli:2.37.9@sha256:92de75724b6a746951f0e8b915d86bbccd7cb55aff96cd0cb4f7017272160780

COPY infra-init.sh /opt/infra-init/infra-init.sh
COPY resources/ /opt/infra-init/resources/

# Normalise line endings defensively (Windows checkouts) and make everything read-only.
RUN sed -i 's/\r$//' /opt/infra-init/infra-init.sh /opt/infra-init/resources/* \
    && chmod 0555 /opt/infra-init/infra-init.sh \
    && chmod 0444 /opt/infra-init/resources/* \
    && sh -n /opt/infra-init/infra-init.sh

ENV HOME=/tmp \
    AWS_PAGER=""

USER 10001:10001

ENTRYPOINT ["/opt/infra-init/infra-init.sh"]
