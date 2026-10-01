FROM python:3.13-slim
WORKDIR /app

# Dependencies first so a code edit does not reinstall numpy/scipy every build.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app app
RUN addgroup --system qp && adduser --system --ingroup qp qp
USER qp
EXPOSE 8084
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
