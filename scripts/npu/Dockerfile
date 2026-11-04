FROM --platform=linux/amd64 ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-venv python3-pip python3-dev \
      clang-14 lld-14 libc++-14-dev libc++abi-14-dev make cmake \
      libtinfo5 libncurses5 libatomic1 libgomp1 file unzip ca-certificates \
    && ln -sf /usr/bin/clang-14 /usr/bin/clang && ln -sf /usr/bin/clang++-14 /usr/bin/clang++ \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install --no-cache-dir \
      mock "numpy<2" packaging pandas pathlib2 pillow protobuf psutil pydantic pyyaml \
      rich scipy six tabulate typing-extensions xlsxwriter onnx==1.17.0 onnxruntime onnxsim
WORKDIR /workspace
