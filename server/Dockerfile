FROM rust:1.97-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY migrations migrations
COPY src src
RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data nxtes
COPY --from=build /src/target/release/nxtes-server /usr/local/bin/nxtes-server
USER 10001
ENV NXTES_BIND=0.0.0.0:8080 NXTES_DATA=/data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["nxtes-server"]
CMD ["serve"]
