#!/bin/sh
set -eu
TEST_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TEST_BUILD=$(mktemp -d)
trap 'rm -rf "$TEST_BUILD"' EXIT
javac -d "$TEST_BUILD" "$TEST_ROOT/app/src/main/java/org/tnet/android/PacketCodec.java" "$TEST_ROOT/app/src/main/java/org/tnet/android/TunnelConfig.java" "$TEST_ROOT/tests/ProtocolTest.java"
java -cp "$TEST_BUILD" ProtocolTest "${1:-$TEST_ROOT/tests/fixtures/crypt_table.h}"
