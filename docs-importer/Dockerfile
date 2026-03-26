FROM node:20-alpine AS builder
WORKDIR /build
COPY main.js package-lock.json package.json ./
RUN npm install && npm install -g @yao-pkg/pkg
RUN pkg -t node20-alpine-x64 .

FROM alpine
ENV TEEDY_TAG= TEEDY_ADDTAGS=false TEEDY_LANG=eng TEEDY_URL='http://localhost:8080' TEEDY_USERNAME=username TEEDY_PASSWORD=password TEEDY_COPYFOLDER= TEEDY_FILEFILTER=*
RUN apk add --no-cache \
    libc6-compat \
    libstdc++ 
ADD pref /root/.config/preferences/com.sismics.docs.importer.pref
ADD env.sh /
COPY --from=builder /build/teedy-importer ./

CMD ["/bin/ash","-c","/env.sh && /teedy-importer -d"]
