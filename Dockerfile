FROM eclipse-temurin:21-jre-jammy
LABEL maintainer="fengzhq@it2000.com.cn"

WORKDIR /opt/app

# Copy the application jar
COPY build/libs/estt-calc-*-all.jar estt-calc.jar

# Create non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser && \
    chown -R appuser:appuser /opt/app

# Switch to non-root user
USER appuser

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD curl -f http://localhost:8080/health || exit 1

# Use container-aware JVM flags
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-Dcom.sun.management.jmxremote", \
     "-jar", "estt-calc.jar"]
