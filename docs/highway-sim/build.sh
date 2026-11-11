#!/bin/sh
# Runs inside the container (podrun.sh sh /work/build.sh). Sources pinned to
# the commits used for the published results.
set -eu
cd /work
[ -d mercury ] || { git clone -q -b mercuryv2 https://github.com/Rhizomatica/mercury.git && git -C mercury checkout -q 097271d; }
[ -d js8i ] || { git clone -q https://github.com/JS8Call-improved/JS8Call-improved.git js8i && git -C js8i checkout -q bad77ff; }
make -s -C mercury/utils watterson_test
make -s -C mercury/modem/freedv >/dev/null 2>&1 || true
make -s -C mercury/modem/freedv freedv_data_raw_tx freedv_data_raw_rx
make -s -C mercury/utils mfsk_burst_file
CARGO_TARGET_DIR=/work/target cargo build -q --release --manifest-path xsim/Cargo.toml
MOC=$(pkg-config --variable=libexecdir Qt6Core)/moc
mkdir -p mocs
$MOC -I js8i js8i/JS8_Mode/JS8.h -o mocs/moc_JS8.cpp
$MOC -I js8i js8i/JS8_Mode/JS8.cpp -o mocs/JS8.moc
cp js8sim.cpp js8i/tools/js8sim.cpp
g++ -std=c++20 -O2 -march=native -Ijs8i -Ijs8i/JS8_Mode -Imocs $(pkg-config --cflags Qt6Core fftw3f) \
  js8i/tools/js8sim.cpp js8i/JS8_Mode/FrequencyTracker.cpp mocs/moc_JS8.cpp \
  $(pkg-config --libs Qt6Core fftw3f) -lpthread -o js8sim
echo BUILD-OK
