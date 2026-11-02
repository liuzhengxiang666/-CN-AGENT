FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HOME=/state/home XDG_CACHE_HOME=/state/cache TMPDIR=/state/tmp \
    HF_HOME=/state/cache/huggingface OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2
RUN pip install --no-cache-dir openviking==0.5.3 \
    https://github.com/abetlen/llama-cpp-python/releases/download/v0.3.36/llama_cpp_python-0.3.36-py3-none-manylinux2014_x86_64.manylinux_2_17_x86_64.whl
WORKDIR /state
COPY openviking-observer.py /observer/openviking-observer.py
CMD ["python", "/observer/openviking-observer.py", "--config", "/state/ov.conf"]
