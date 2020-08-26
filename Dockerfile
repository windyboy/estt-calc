MAINTAINER fengzhq@it2000.com.cn

FROM reg.int.it2000.com.cn/library/gradle:6.5-jdk14 as builder
ENV APP_HOME=/opt/app
RUN mkdir -p $APP_HOME
WORKDIR $APP_HOME
COPY . .
RUN gradle clean assemble check


FROM openjdk:14-alpine
COPY --from=builder build/libs/estt-calc-*-all.jar estt-calc.jar
EXPOSE 8080
CMD ["java", "-Dcom.sun.management.jmxremote", "-Xmx128m", "-jar", "estt-calc.jar"]
