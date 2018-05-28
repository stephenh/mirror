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

# Build 'mirror'
COPY . /tmp/mirror
WORKDIR /tmp/mirror
RUN ./gradlew shadowJar


# ------------------------------------------------------------------- #


FROM debian:9

RUN apt-get update -q && apt-get install -qy \
  openjdk-8-jre-headless

COPY --from=mirror-builder /usr/local/bin/watchman /usr/local/bin/
RUN install -d -m 777 /usr/local/var/run/watchman

WORKDIR "/opt/mirror"
COPY --from=mirror-builder /tmp/mirror/mirror ./
COPY --from=mirror-builder /tmp/mirror/build/libs/mirror-all.jar ./
RUN chmod a+s /usr/sbin/useradd /usr/sbin/groupadd
ADD docker/docker-entrypoint.sh docker-entrypoint.sh
ENTRYPOINT ["./docker-entrypoint.sh"]
