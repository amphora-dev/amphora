# CI image for Amphora JVM tests, APK assemble, and ARM emulator runs.
# Android SDK / emulator packages are installed at job time (CNB volume cache).
FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        adb \
        ca-certificates \
        curl \
        file \
        git \
        unzip \
        zip \
        libpulse0 \
        libnss3 \
        libx11-6 \
        libgl1 \
        libxcb1 \
        libxcomposite1 \
        libxcursor1 \
        libxdamage1 \
        libxi6 \
        libxtst6 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/emulator:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"
