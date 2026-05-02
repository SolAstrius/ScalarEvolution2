# SPDX-License-Identifier: MPL-2.0
#
# Build a single self-contained libscev_term.{so,dylib,dll} by
# - building SolAstrius/mlterm-fb-embed (branch fb-embed) from
#   source under MLTERM_SRC, with our flake's nix-shell deps
# - linking its libuitoolkit.a + libmlterm.a + libpobl/libmef into
#   our scev_term.c + scev_term_jni.c JNI shim
# - producing one .so the runtime can dlopen via System.load
#
# Driven from Gradle's `buildMltermNative` task. Expects:
#   MLTERM_SRC : checkout of github.com/SolAstrius/mlterm-fb-embed
#                pinned to a commit on the `fb-embed` branch
#   JAVA_HOME  : for jni.h
#   OUTPUT     : final output path for the linked .so
#
# Toolchain: zig cc, mirroring openh264-jni and librvvm. Bundles
# clang+lld+libc, cross-compiles cleanly without a second toolchain.

ifndef JAVA_HOME
$(error JAVA_HOME must be set)
endif
ifndef MLTERM_SRC
$(error MLTERM_SRC must point at a SolAstrius/mlterm-fb-embed checkout (branch fb-embed))
endif
ifndef OUTPUT
$(error OUTPUT must point at the final .so / .dylib / .dll path)
endif

UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Linux)
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/linux
endif
ifeq ($(UNAME_S),Darwin)
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/darwin
endif
ifeq (MSYS,$(findstring MSYS,$(UNAME_S)))
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/win32
endif
ifeq (MINGW,$(findstring MINGW,$(UNAME_S)))
    JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/win32
endif
ifndef JNI_PLATFORM_INCLUDE
$(error Unsupported host OS: $(UNAME_S))
endif

# We want libexecdir to live alongside the built .so so the runtime
# can locate registobmp + mlimgloader without polluting /usr/local.
# Gradle's buildMltermNative task copies them into here after build.
LIBEXEC_DIR := $(dir $(OUTPUT))mlterm-libexec

CC := zig cc

# Step 1: configure + build mlterm itself. Relies on the fb-embed
# branch's autotools setup. Because we link statically, --disable-
# everything that isn't strictly required keeps the resulting .so
# small. Tools are limited to mlimgloader + registobmp (image
# rendering helpers mlterm execs at runtime); the regular mlterm
# binary etc are skipped.
.PHONY: all configure-mlterm build-mlterm shim install-helpers clean

all: $(OUTPUT) install-helpers

# Stamp file marks "we configured with the right flags". Re-runs
# configure if the stamp's older than configure.in or doesn't exist;
# users who configured the tree by hand for testing get re-configured
# here so the libexec path + tools list match what we ship.
MLTERM_CONFIGURE_STAMP := $(MLTERM_SRC)/.scev-configured
configure-mlterm: $(MLTERM_CONFIGURE_STAMP)
$(MLTERM_CONFIGURE_STAMP): $(MLTERM_SRC)/configure.in
	cd $(MLTERM_SRC) && \
	  autoconf -f && \
	  automake --add-missing --copy --foreign 2>/dev/null || true && \
	  CC="$(CC)" ./configure \
	    --with-gui=fb \
	    --enable-fb-embed \
	    --disable-otl \
	    --disable-shared \
	    --enable-static \
	    --libexecdir=$(LIBEXEC_DIR) \
	    --with-tools=mlimgloader,registobmp
	touch $(MLTERM_CONFIGURE_STAMP)

build-mlterm: configure-mlterm
	# Only build the targets we actually static-link against — skip
	# the fb-dumper / mlterm-fb / man / etc subdirs that mlterm's
	# top-level Makefile builds via MAKE_DIRS but we don't need.
	# fb-dumper in particular pulls in xlib headers (via
	# uitoolkit/ui.h on non-fb builds of the include chain) that
	# nix-shell doesn't ship with.
	$(MAKE) -C $(MLTERM_SRC)/baselib
	$(MAKE) -C $(MLTERM_SRC)/encodefilter
	# libind (Indian-script transliteration) builds before vtemu —
	# vtemu's libctl_iscii needs libind/libind.a to link.
	-$(MAKE) -C $(MLTERM_SRC)/libind
	$(MAKE) -C $(MLTERM_SRC)/vtemu
	# libctl static plugins (iscii / bidi) — vtemu's vt_line_shape.c
	# directly references symbols from these when built without
	# --disable-dl-ctl. Build them so the final link resolves.
	-$(MAKE) -C $(MLTERM_SRC)/vtemu/libctl
	$(MAKE) -C $(MLTERM_SRC)/uitoolkit
	# main_loop.o + daemon.o are object files (not a lib) — build
	# them individually so we can pick them up at the link step.
	$(MAKE) -C $(MLTERM_SRC)/main main_loop.o daemon.o
	# libexec helpers (registobmp + mlimgloader) — install-helpers
	# pulls them from these subdirs.
	$(MAKE) -C $(MLTERM_SRC)/tool/registobmp
	$(MAKE) -C $(MLTERM_SRC)/tool/mlimgloader

# Step 2: link our JNI shim against mlterm's static archives.
SHIM_SRCS := scev_term.c scev_term_jni.c
SHIM_OBJS := $(SHIM_SRCS:.c=.o)

CFLAGS := -O2 -fPIC -Wall \
  -DUSE_FRAMEBUFFER -DUSE_FB_EMBED \
  -I$(JAVA_HOME)/include \
  -I$(JNI_PLATFORM_INCLUDE) \
  -I$(MLTERM_SRC)/baselib/include \
  -I$(MLTERM_SRC)/baselib/src \
  -I$(MLTERM_SRC)/encodefilter/include \
  -I$(MLTERM_SRC)/encodefilter/src \
  -I$(MLTERM_SRC)/vtemu \
  -I$(MLTERM_SRC)/uitoolkit \
  -I$(MLTERM_SRC)/uitoolkit/fb \
  -I$(MLTERM_SRC)/main \
  -I$(MLTERM_SRC)/tool/registobmp

# Static archives produced by mlterm's build. Order matters for
# the static link step — uitoolkit references vtemu references
# baselib + encodefilter.
#
# Some libs are libtool-built (.libs/foo.a is the real archive,
# foo.la is a wrapper) and some are plain `ar` archives at the
# top of their dir. Where mlterm's build deposits the file
# differs across configure flags + automake versions, so we
# resolve each archive at make time using `wildcard`.
find_lib = $(firstword $(wildcard $(1)/.libs/$(2) $(1)/$(2)))
# Recursive (=) not immediate (:=) — these paths must be resolved
# at link time, AFTER build-mlterm has produced the .a files.
# With := the wildcard runs at parse time and expands to nothing
# on a fresh tree.
MLTERM_LIBS = \
  $(MLTERM_SRC)/main/main_loop.o \
  $(MLTERM_SRC)/main/daemon.o \
  $(call find_lib,$(MLTERM_SRC)/uitoolkit,libuitoolkit.a) \
  $(call find_lib,$(MLTERM_SRC)/vtemu,libmlterm.a) \
  $(call find_lib,$(MLTERM_SRC)/vtemu,libmlterm_core.a) \
  $(wildcard $(MLTERM_SRC)/vtemu/libctl/libctl_iscii.a) \
  $(wildcard $(MLTERM_SRC)/vtemu/libctl/libctl_bidi.a) \
  $(wildcard $(MLTERM_SRC)/libind/libind.a) \
  $(call find_lib,$(MLTERM_SRC)/encodefilter/src,libmef.a) \
  $(call find_lib,$(MLTERM_SRC)/baselib/src,libpobl.a)

# Runtime libs we need on the link line. Use pkg-config so the
# nix-store paths come out right (zig cc's bundled lld doesn't
# search /usr/lib by default).
PKG_CFLAGS := $(shell pkg-config --cflags freetype2 fontconfig libpng libssh2 2>/dev/null)
PKG_LIBS   := $(shell pkg-config --libs freetype2 fontconfig libpng libssh2 2>/dev/null)
SYS_LIBS := $(PKG_LIBS) -lz -lm -lpthread -ldl
CFLAGS += $(PKG_CFLAGS)

# Shim objects also depend on build-mlterm — pobl's "include/pobl"
# symlink directory is created by `make -C baselib`, and the shim
# transitively includes pobl/bl_types.h via ui_screen.h. Without
# this dep, parallel make can race and try to compile the shim
# before baselib has populated its include tree.
%.o: %.c scev_term.h | build-mlterm
	$(CC) $(CFLAGS) -c $< -o $@

$(OUTPUT): build-mlterm $(SHIM_OBJS)
	mkdir -p $(dir $(OUTPUT))
	# Wrap the mlterm static archives in --start-group/--end-group so
	# the linker re-scans them until no new undefined symbols come up.
	# vtemu/libmlterm.a ↔ vtemu/libctl/libctl_iscii.a have circular
	# back-references; single-pass static linking can leave one side
	# unresolved depending on archive order.
	$(CC) -shared $(SHIM_OBJS) \
	  -Wl,--start-group $(MLTERM_LIBS) -Wl,--end-group \
	  $(SYS_LIBS) -o $(OUTPUT)

install-helpers: build-mlterm
	mkdir -p $(LIBEXEC_DIR)/mlterm
	# libtool's wrapper-vs-real-binary placement differs between
	# tools and platforms — registobmp ends up at tool/foo/foo
	# while mlimgloader lands in tool/foo/.libs/foo. Try the .libs
	# path first (the real ELF), fall back to the wrapper-named
	# top-level path.
	@for tool in mlimgloader registobmp; do \
	  src="$(MLTERM_SRC)/tool/$$tool/.libs/$$tool"; \
	  test -f "$$src" || src="$(MLTERM_SRC)/tool/$$tool/$$tool"; \
	  test -f "$$src" || { echo "$$tool not built at expected path"; exit 1; }; \
	  cp "$$src" "$(LIBEXEC_DIR)/mlterm/$$tool"; \
	done

clean:
	rm -f $(SHIM_OBJS) $(OUTPUT)
	rm -rf $(LIBEXEC_DIR)
