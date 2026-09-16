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
COPY config.toml rules.toml /app/
RUN mkdir -p /app/data
EXPOSE 8080
ENTRYPOINT ["/app/market-event-analyzer"]

