FROM debian:9 as mirror-builder

# Install dependencies
RUN apt-get update -q && apt-get install -qy \
  libssl-dev \
  autoconf \
  automake \
  libtool \
  python-setuptools python-dev \
  curl \
  build-essential \
  pkg-config \
  openjdk-8-jdk-headless \
  git

# Install 'watchman'
RUN cd /tmp && \
  curl -sL 'https://github.com/facebook/watchman/archive/v4.9.0.tar.gz' | tar xzf - && \
  cd watchman-* && \
  ./autogen.sh && \
  ./configure && \
  make && \
  make install

# Install 'gosu'
RUN curl -sLo /usr/local/bin/gosu "https://github.com/tianon/gosu/releases/download/1.10/gosu-$(dpkg --print-architecture)" && \
  chmod +x /usr/local/bin/gosu

# Build 'mirror'
COPY . /tmp/mirror
WORKDIR /tmp/mirror
RUN ./gradlew shadowJar


# ------------------------------------------------------------------- #


FROM debian:9

RUN apt-get update -q && apt-get install -qy \
  openjdk-8-jre-headless

COPY --from=mirror-builder /usr/local/bin/gosu /usr/local/bin/
COPY --from=mirror-builder /usr/local/bin/watchman /usr/local/bin/
RUN install -d -m 777 /usr/local/var/run/watchman

WORKDIR "/opt/mirror"
COPY --from=mirror-builder /tmp/mirror/mirror ./
COPY --from=mirror-builder /tmp/mirror/build/libs/mirror-all.jar ./
ADD docker/docker-entrypoint.sh docker-entrypoint.sh
ENTRYPOINT ["./docker-entrypoint.sh"]
