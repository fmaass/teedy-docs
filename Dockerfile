FROM ubuntu:24.04@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90

ENV DEBIAN_FRONTEND=noninteractive

ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
# TARGETARCH is supplied by buildx per-arch (amd64/arm64) and matches the Debian
# OpenJDK path suffix; default keeps a plain `docker build` (e.g. compose) on amd64.
# Note: the runtime launches `java` from PATH, so JAVA_HOME is informational only.
ARG TARGETARCH=amd64
ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk-${TARGETARCH}/
ENV JAVA_OPTIONS="-Dfile.encoding=UTF-8 -Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV JETTY_VERSION=12.0.21
ENV JETTY_HOME=/opt/jetty

RUN apt-get update && \
    apt-get -y -q --no-install-recommends install \
    procps curl unzip wget tzdata openjdk-25-jre-headless \
    ffmpeg \
    mediainfo \
    tesseract-ocr \
    tesseract-ocr-ara \
    tesseract-ocr-ces \
    tesseract-ocr-chi-sim \
    tesseract-ocr-chi-tra \
    tesseract-ocr-dan \
    tesseract-ocr-deu \
    tesseract-ocr-fin \
    tesseract-ocr-fra \
    tesseract-ocr-heb \
    tesseract-ocr-hin \
    tesseract-ocr-hun \
    tesseract-ocr-ita \
    tesseract-ocr-jpn \
    tesseract-ocr-kor \
    tesseract-ocr-lav \
    tesseract-ocr-nld \
    tesseract-ocr-nor \
    tesseract-ocr-pol \
    tesseract-ocr-por \
    tesseract-ocr-rus \
    tesseract-ocr-spa \
    tesseract-ocr-swe \
    tesseract-ocr-tha \
    tesseract-ocr-tur \
    tesseract-ocr-ukr \
    tesseract-ocr-vie \
    tesseract-ocr-sqi \
    && apt-get clean && \
    rm -rf /var/lib/apt/lists/*
RUN dpkg-reconfigure -f noninteractive tzdata

# SHA-512 pinned for JETTY_VERSION 12.0.21 (cross-checked against Maven Central .sha1).
# Bump this when JETTY_VERSION changes, or the build fails closed.
ENV JETTY_SHA512=f005b33d8062de928862cd2b63e5a07d43454fd69753e2d7959415baa07bb258de7579e659535839c9f31c160941c86b21f78f0ef20d6e8cf40d573eb653d7d4
RUN wget -nv -O /tmp/jetty.tar.gz \
    "https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-home/${JETTY_VERSION}/jetty-home-${JETTY_VERSION}.tar.gz" \
    && echo "${JETTY_SHA512}  /tmp/jetty.tar.gz" | sha512sum -c - \
    && tar xzf /tmp/jetty.tar.gz -C /opt \
    && rm /tmp/jetty.tar.gz \
    && mv /opt/jetty* /opt/jetty \
    && useradd jetty -U -s /bin/false \
    && chown -R jetty:jetty /opt/jetty \
    && mkdir /opt/jetty/webapps \
    && chmod +x /opt/jetty/bin/jetty.sh

EXPOSE 8080

RUN mkdir /app && \
    cd /app && \
    java -jar /opt/jetty/start.jar --add-modules=server,http,ee10-deploy,ee10-webapp

COPY docs.xml /app/webapps/docs.xml
COPY docs-web/target/docs-web-*.war /app/webapps/docs.war

RUN mkdir -p /data && chown -R jetty:jetty /app /data

WORKDIR /app
USER jetty

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/api/user || exit 1

CMD ["sh", "-c", "exec java ${JAVA_OPTIONS} -jar /opt/jetty/start.jar"]
