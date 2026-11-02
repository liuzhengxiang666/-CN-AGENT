FROM node:24.16.0-bookworm-slim
ENV PNPM_HOME=/pnpm PATH=/pnpm:/opt/venv/bin:$PATH npm_config_cache=/tmp/npm-cache PIP_CACHE_DIR=/tmp/pip-cache
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv ripgrep ca-certificates git procps curl && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir grpcio==1.73.0 grpcio-tools==1.73.0 pytest==8.4.2 && chmod -R a+rwX /opt/venv
RUN npm install -g pnpm@10.20.0
WORKDIR /harness
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY vnext/package.json vnext/package.json
RUN pnpm install --frozen-lockfile --ignore-scripts --store-dir /pnpm/store --cache-dir /pnpm/cache
COPY vnext/src vnext/src
COPY vnext/runtime vnext/runtime
COPY vnext/tsconfig.json vnext/tsconfig.json
RUN pnpm --dir vnext exec tsc
RUN mkdir /verifier-deps && cd /verifier-deps && npm install --save-exact jest@29.7.0 babel-jest@29.7.0 @babel/core@7.25.2 @exercism/babel-preset-javascript@0.2.1 core-js@3.37.1
RUN mkdir /app /data && chown node:node /app /data
WORKDIR /app
USER node
CMD ["sleep","infinity"]
