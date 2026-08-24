# syntax=docker/dockerfile:1

# Development/integration image for running the backend from a bind-mounted checkout.
FROM maven:3.9.11-eclipse-temurin-21

# The default mirror is reliable for the team's China-based development environment.
# Override it at build time when needed, for example:
#   docker build --build-arg UBUNTU_MIRROR=http://archive.ubuntu.com/ubuntu/ ...
ARG UBUNTU_MIRROR=https://mirrors.aliyun.com/ubuntu/

RUN set -eux; \
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
        sed -i \
            -e "s|http://archive.ubuntu.com/ubuntu/|${UBUNTU_MIRROR}|g" \
            -e "s|http://security.ubuntu.com/ubuntu/|${UBUNTU_MIRROR}|g" \
            /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    apt-get -o Acquire::Retries=5 update; \
    apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-dejavu-core \
        fonts-droid-fallback; \
    fc-cache -f; \
    test -f /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf; \
    test -f /usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf; \
    rm -rf /var/lib/apt/lists/*

ENV MAVEN_OPTS="-Dmaven.test.skip=true" \
    PRIVATE_BANK_PDF_FONT_PATH="/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"

WORKDIR /workspace

EXPOSE 8080

CMD ["mvn", "--no-transfer-progress", "spring-boot:run"]
