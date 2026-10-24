# syntax=docker/dockerfile:1
# FamilyChat backend. Multi-arch (linux/amd64, linux/arm64): every dependency ships a manylinux wheel,
# so no compiler is needed and none ends up in the final image.

FROM python:3.12-slim AS builder
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN python -m venv /opt/venv
COPY requirements.txt /tmp/requirements.txt
# Optional: behind a TLS-inspecting proxy build with  --secret id=ca,src=/path/to/ca-bundle.crt
RUN --mount=type=secret,id=ca,required=false \
    if [ -s /run/secrets/ca ]; then export PIP_CERT=/run/secrets/ca; fi; \
    /opt/venv/bin/pip install --only-binary=:all: -r /tmp/requirements.txt

FROM python:3.12-slim
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    FAMILYCHAT_ENV=production \
    FAMILYCHAT_DATA_DIR=/data
RUN groupadd --system --gid 10001 familychat \
 && useradd --system --uid 10001 --gid familychat --home-dir /app --shell /usr/sbin/nologin familychat \
 && mkdir -p /data /app \
 && chown familychat:familychat /data
COPY --from=builder /opt/venv /opt/venv
WORKDIR /app
COPY --chown=root:root app ./app
COPY --chown=root:root migrations ./migrations
COPY --chown=root:root alembic.ini uvicorn_log.json ./
COPY --chown=root:root docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh
USER familychat
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3)"]
ENTRYPOINT ["docker-entrypoint.sh"]
