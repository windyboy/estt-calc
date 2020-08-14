FROM openjdk:14-alpine
COPY build/libs/estt-calc-*-all.jar estt-calc.jar
EXPOSE 8080
CMD ["java", "-Dcom.sun.management.jmxremote", "-Xmx128m", "-jar", "estt-calc.jar"]