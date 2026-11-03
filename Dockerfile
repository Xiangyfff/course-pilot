# 多阶段构建：Maven 编译 → JRE 运行
# 本机 Docker Hub 不可达，默认走 DaoCloud 镜像站；可用 build args 覆盖
ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

# BuildKit cache mount 把 ~/.m2 留在镜像层之外：首次构建下载依赖，之后重建只做编译。
# 不用 dependency:go-offline —— 它会把所有插件的可选依赖都拉一遍，在这台机器上会卡十几分钟。
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

FROM ${JRE_IMAGE}
WORKDIR /app

# 不跑 root；数据都走外部存储，容器本身只读代码
RUN groupadd -r app && useradd -r -g app -d /app app

COPY --from=build /build/target/course-planner-*.jar /app/app.jar
RUN chown -R app:app /app
USER app

ENV APP_PORT=8080 \
    APP_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
