# 1단계: 빌드. JDK와 Maven으로 jar를 만든 뒤 레이어별로 풀어 둔다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# pom.xml만 먼저 복사해 의존성을 받아 둔다. 소스만 바뀌면 이 레이어는 캐시를 재사용한다.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline

COPY src src
# 테스트는 CI(ci.yml)에서 따로 돌린다.
RUN ./mvnw -q -B package -DskipTests \
 && java -Djarmode=layertools -jar target/moagwa-be-*.jar extract --destination target/layers

# 2단계: 실행. JRE와 앱만 담아 이미지를 작게 유지한다.
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app \
 && mkdir uploads && chown app:app uploads

# 자주 바뀌지 않는 레이어부터 복사해야 배포 때 바뀐 앱 레이어만 다시 받는다.
COPY --from=build /app/target/layers/dependencies/ ./
COPY --from=build /app/target/layers/spring-boot-loader/ ./
COPY --from=build /app/target/layers/snapshot-dependencies/ ./
COPY --from=build /app/target/layers/application/ ./

USER app
# 힙 크기를 컨테이너 메모리 제한 기준으로 잡는다(쿠버네티스 limits와 함께 동작).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
