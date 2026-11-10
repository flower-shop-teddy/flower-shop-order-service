# Image chạy order-service.
# Jar đã được Maven build sẵn ở bước trước (mvn verify), Dockerfile chỉ việc chép vào,
# nên Docker KHÔNG cần tải thư viện hay token GitHub Packages.
# Build thử ở máy:  mvn -q package -DskipTests && docker build -t order-serviceocal .

# JRE (chỉ để chạy, không có trình biên dịch) -> image nhỏ hơn, ít lỗ hổng hơn JDK.
# Ghim major version 21 cho khớp <java.version> trong parent.
FROM eclipse-temurin:21-jre

# Gắn image với repo trên GitHub (trang package sẽ hiện README, quyền theo repo)
LABEL org.opencontainers.image.source="https://github.com/flower-shop-teddy/flower-shop-order-service"

# Không chạy bằng root: nếu app bị chiếm quyền, kẻ tấn công không có quyền root trong container
RUN groupadd --system app && useradd --system --gid app --no-create-home app

WORKDIR /app
COPY target/order-service-*.jar app.jar
USER app

EXPOSE 8085

# MaxRAMPercentage: JVM dùng tối đa 75% RAM mà container được cấp (thay vì đoán theo RAM máy chủ)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
