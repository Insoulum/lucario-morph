FROM python:3.12-alpine

WORKDIR /app
COPY service.py /app/service.py

ENV PORT=8080 \
    DATA_DIR=/data \
    RATE_PER_MIN=120 \
    TTL_DAYS=30

EXPOSE 8080
VOLUME ["/data"]

# Без healthcheck-зависимостей: alpine имеет wget (busybox)
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s \
  CMD wget -qO- http://127.0.0.1:${PORT}/health >/dev/null 2>&1 || exit 1

CMD ["python", "-u", "service.py"]
