FROM gcr.io/distroless/java17
ENV TZ="Europe/Oslo"
ENV JAVA_TOOL_OPTIONS=-XX:+ExitOnOutOfMemoryError
WORKDIR /app
COPY build/libs/*.jar ./app.jar
EXPOSE 8080
USER nonroot
CMD ["app.jar"]
