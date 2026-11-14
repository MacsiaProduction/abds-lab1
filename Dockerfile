ARG BASE_IMAGE
FROM ${BASE_IMAGE} AS java
ARG TARGETARCH
ARG JDK_VERSION
ARG JDK_BASE_URL
ARG JDK_SHA256_AMD64
ARG JDK_SHA256_ARM64
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gzip tar && rm -rf /var/lib/apt/lists/*
RUN set -eu; case "$TARGETARCH" in amd64) arch=x64; checksum="$JDK_SHA256_AMD64" ;; arm64) arch=aarch64; checksum="$JDK_SHA256_ARM64" ;; *) exit 1 ;; esac; \
    curl --fail --location --retry 3 "$JDK_BASE_URL/jdk-${JDK_VERSION}_linux-${arch}_bin.tar.gz" -o /tmp/jdk.tar.gz; \
    echo "$checksum  /tmp/jdk.tar.gz" | sha256sum --check; \
    mkdir -p /opt/java; tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1; rm /tmp/jdk.tar.gz
ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:$PATH

FROM java AS build
WORKDIR /source
COPY gradlew settings.gradle.kts build.gradle.kts gradle.lockfile settings-gradle.lockfile ./
COPY gradle/ gradle/
COPY .editorconfig ./
COPY src/ src/
RUN --mount=type=cache,id=abds-lab1-gradle,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon check installDist

FROM java AS runtime
WORKDIR /app
COPY --from=build /source/build/install/abds-lab1/ /app/
ENTRYPOINT ["/app/bin/abds-lab1"]
CMD ["serve"]
