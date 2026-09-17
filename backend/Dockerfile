FROM rust:1.96-bookworm AS builder
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY migrations ./migrations
COPY src ./src
RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/target/release/market-event-analyzer /app/market-event-analyzer
COPY rules.toml /app/
# 真实配置不入库：镜像里带模板，运行时用 -v 挂载填好密钥的 config.toml。
COPY config.example.toml /app/config.toml
RUN mkdir -p /app/data
EXPOSE 8080
ENTRYPOINT ["/app/market-event-analyzer"]

