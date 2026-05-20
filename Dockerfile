# ===================== 多阶段构建 =====================
# Stage 1: Maven 编译打包
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: 运行镜像
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 复制 JAR
COPY --from=builder /app/target/short-url-1.0.0.jar /app/short-url.jar

USER appuser

# 环境变量（可在 docker-compose 中覆盖）
ENV SPRING_PROFILES_ACTIVE=prod
ENV TZ=Asia/Shanghai

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://localhost:8080/ || exit 1

ENTRYPOINT ["java", "-Xmx256m", "-Xms128m", "-jar", "short-url.jar"]