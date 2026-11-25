FROM ubuntu:24.04 AS launcher
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev
COPY deploy/artemis-headless/launcher.c /tmp/launcher.c
RUN gcc -static -O2 -Wall -Wextra -Werror /tmp/launcher.c -o /artemis-launcher

FROM ubuntu:24.04
ARG ARTEMIS_REV=351ca8422f7b5b54e80a9c1ce03a222e02415b6b
RUN apt-get update && apt-get install -y --no-install-recommends \
    adb ca-certificates ffmpeg git libgl1 libglib2.0-0t64 python3-venv \
    && rm -rf /var/lib/apt/lists/*
RUN git init /opt/artemis \
    && git -C /opt/artemis fetch --depth=1 https://github.com/google/artemis.git "$ARTEMIS_REV" \
    && git -C /opt/artemis checkout --detach FETCH_HEAD
WORKDIR /opt/artemis
RUN python3 -m venv /tmp/bootstrap \
    && /tmp/bootstrap/bin/pip install --no-cache-dir uv==0.13.9 \
    && /tmp/bootstrap/bin/uv sync --locked --no-dev --no-install-project --no-python-downloads --no-cache \
    && rm -rf /tmp/bootstrap /opt/artemis/.git \
    && mkdir -p /runtime /opt/bridge/bin
COPY --from=launcher /artemis-launcher /artemis-launcher
COPY deploy/artemis-local/bin/adb /opt/bridge/bin/adb
COPY deploy/artemis-local/sitecustomize.py /opt/bridge/
COPY deploy/artemis-headless/run deploy/artemis-headless/action_bridge.py deploy/artemis-headless/test_action_bridge.py /opt/bridge/
RUN chmod 0755 /opt/bridge/run /opt/bridge/bin/adb \
    && PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/opt/bridge:/opt/artemis ARTEMIS_APP_DIR=/tmp/artemis-check ARTEMIS_STANDALONE=1 \
       /opt/artemis/.venv/bin/python -m unittest -q test_action_bridge \
    && rm -rf /tmp/artemis-check
