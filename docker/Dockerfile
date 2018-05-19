FROM debian:9

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

RUN cd $(mktemp -dt) && \
  curl -sL 'https://github.com/facebook/watchman/archive/v4.9.0.tar.gz' | tar xzf - && \
  cd watchman-* && \
  ./autogen.sh && \
  ./configure && \
  make && \
  make install

RUN cd $(mktemp -dt) && \
  curl -sL 'https://github.com/stephenh/mirror/archive/1.1.15.tar.gz' | tar xzf - && \
  cd mirror-* && \
  ./gradlew shadowJar && \
  mkdir -p /opt/mirror && \
  cp ./mirror /opt/mirror/ && \
  cp ./build/libs/mirror-*-all.jar /opt/mirror/mirror-all.jar

RUN curl -sLo /usr/local/bin/gosu "https://github.com/tianon/gosu/releases/download/1.10/gosu-$(dpkg --print-architecture)" && \
  chmod +x /usr/local/bin/gosu

WORKDIR "/opt/mirror"
ADD docker-entrypoint.sh docker-entrypoint.sh
RUN chmod a+x docker-entrypoint.sh
ENTRYPOINT ["./docker-entrypoint.sh"]
