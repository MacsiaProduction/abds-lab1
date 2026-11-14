ARG JDK_IMAGE
FROM ${JDK_IMAGE} AS build
WORKDIR /source
COPY gradlew settings.gradle.kts build.gradle.kts gradle.lockfile settings-gradle.lockfile ./
COPY gradle/ gradle/
COPY .editorconfig ./
COPY src/ src/
RUN --mount=type=cache,id=abds-lab1-gradle,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon check installDist

FROM ${JDK_IMAGE} AS runtime
WORKDIR /app
COPY --from=build /source/build/install/abds-lab1/ /app/
ENTRYPOINT ["/app/bin/abds-lab1"]
CMD ["serve"]
