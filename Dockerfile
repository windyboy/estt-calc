
FROM reg.int.it2000.com.cn/library/openjdk:14-alpine
MAINTAINER fengzhq@it2000.com.cn
WORKDIR /opt/app
COPY build/libs/estt-calc-*-all.jar estt-calc.jar
EXPOSE 8080
CMD ["java", "-Dcom.sun.management.jmxremote", "-Xmx128m", "-jar", "estt-calc.jar"]
