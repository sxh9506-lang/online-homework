FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home appuser
COPY target/homework-api-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-jar", "/app/app.jar"]
