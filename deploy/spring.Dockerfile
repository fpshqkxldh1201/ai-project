# Lumi 백엔드(Spring Boot 4 / Java 21, Gradle). 빌드 컨텍스트는 저장소 맨 위 폴더(build.gradle이 있는 곳)다.
# 정적 UI(src/main/resources/static)도 jar 안에 들어가므로 8081 하나로 화면과 API를 모두 제공한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# 윈도에서 온 파일의 줄바꿈(CRLF) 때문에 gradlew 가 안 도는 일을 막는다
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src ./src
# bootJar 만 만들어 jar 가 하나(실행 가능 jar)만 나오게 한다
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 lumi
WORKDIR /app
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER lumi
ENV TZ=Asia/Seoul \
    JAVA_OPTS="-Xmx768m -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8"
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
