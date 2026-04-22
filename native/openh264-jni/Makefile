# SPDX-License-Identifier: MPL-2.0
#
# Builds a single self-contained libscev_h264.so (or .dylib/.dll) by
# statically linking OpenH264 (Cisco) into a thin JNI wrapper. The
# resulting shared object has NO runtime dependency on a host-installed
# libopenh264 — players download the mod jar and it works.
#
# Driven from Gradle's `buildH264Native` task. Expects:
#   - OPENH264_SRC : path to a checkout of https://github.com/cisco/openh264
#   - JAVA_HOME    : for jni.h
#   - OUTPUT       : final output path for the linked .so
#
# Toolchain: zig cc / zig c++ everywhere. Zig bundles clang + lld +
# libc(++) headers, cross-compiles without a second toolchain, and
# produces output matching the existing `librvvm` pattern. OpenH264's
# own Makefile respects CC/CXX/CCAS overrides, so we inject zig for
# its build too.
#
# ASM: USE_ASM=No disables the NASM-built SIMD kernels. The pure-C
# fallback is ~2-3× slower on encode but portable and doesn't require
# NASM in the build environment. Trading realtime-encoder CPU headroom
# for toolchain simplicity is the right call at MVP; the perf win can
# be reclaimed later by wiring in a NASM dependency.

ifndef JAVA_HOME
$(error JAVA_HOME must be set)
endif
ifndef OPENH264_SRC
$(error OPENH264_SRC must point at a Cisco openh264 source checkout)
endif
ifndef OUTPUT
$(error OUTPUT must point at the final .so / .dylib / .dll path)
endif

# Host OS sniff for the JNI header's platform subdirectory.
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Linux)
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/linux
endif
ifeq ($(UNAME_S),Darwin)
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/darwin
endif
# MinGW/MSYS builds set UNAME_S to things like MINGW64_NT-10.0 on Windows;
# pattern-match rather than hard-code.
ifeq (MSYS,$(findstring MSYS,$(UNAME_S)))
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/win32
endif
ifeq (MINGW,$(findstring MINGW,$(UNAME_S)))
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/win32
endif

ifndef JNI_PLATFORM_INCLUDE
$(error Unsupported host OS: $(UNAME_S))
endif

OPENH264_A := $(OPENH264_SRC)/libopenh264.a
OPENH264_INCLUDE := $(OPENH264_SRC)/codec/api/wels

CC := zig cc
CXX := zig c++

# Build flags for the wrapper. We pass the JDK + OpenH264 public API
# headers; the wrapper doesn't reach into OpenH264 internals.
CFLAGS := -fPIC -O2 -Wall -Wextra \
          -I$(JAVA_HOME)/include \
          -I$(JNI_PLATFORM_INCLUDE) \
          -I$(OPENH264_INCLUDE)

# Link flags. -shared for the .so, -lpthread for OpenH264's threadpool.
# Using zig c++ as the driver so libc++ and C++ runtime symbols from
# OpenH264's C++ internals resolve without us having to hand-list them.
LDFLAGS := -shared -lpthread

OBJS := scev_h264.o

.PHONY: all clean openh264

all: $(OUTPUT)

# Build the OpenH264 static library with zig as the compiler.
# OpenH264's Makefile: ENABLE64BIT=Yes + BUILDTYPE=Release are the
# standard knobs; HAVE_GTEST=No skips their gtest sources we don't need;
# USE_STACK_PROTECTOR=No avoids the -fstack-protector-strong flag that
# zig's clang doesn't always have runtime support for out of the box.
$(OPENH264_A):
	@echo "==> Building OpenH264 static lib via $(CC)..."
	$(MAKE) -C $(OPENH264_SRC) \
	    CC="$(CC)" CXX="$(CXX)" CCAS="$(CC)" \
	    USE_ASM=No \
	    ENABLE64BIT=Yes \
	    BUILDTYPE=Release \
	    HAVE_GTEST=No \
	    USE_STACK_PROTECTOR=No \
	    libopenh264.a
	@test -f $@ || (echo "OpenH264 build did not produce $@"; exit 1)

scev_h264.o: scev_h264.c $(OPENH264_A)
	@echo "==> Compiling JNI wrapper..."
	$(CC) $(CFLAGS) -c scev_h264.c -o $@

# Final link. Statically embeds libopenh264.a into the output .so, so
# the result is self-contained modulo system libc + libc++ + pthread,
# which every Java client already has loaded anyway. zig c++ drives
# the link to pull in the C++ runtime OpenH264 needs.
$(OUTPUT): $(OBJS) $(OPENH264_A)
	@echo "==> Linking $(OUTPUT)..."
	@mkdir -p $(dir $(OUTPUT))
	$(CXX) $(LDFLAGS) $(OBJS) $(OPENH264_A) -o $(OUTPUT)
	@echo "==> Built $$(du -h $(OUTPUT) | cut -f1) $(OUTPUT)"

clean:
	rm -f $(OBJS) $(OUTPUT)

# `make openh264-clean` to blow away the openh264 build intermediates
# separately from our wrapper — useful when toggling BUILDTYPE or
# switching openh264 tags.
openh264-clean:
	$(MAKE) -C $(OPENH264_SRC) clean
