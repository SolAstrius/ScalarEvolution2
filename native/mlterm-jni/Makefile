# SPDX-License-Identifier: MPL-2.0
#
# Build a single self-contained libscev_term.{so,dylib,dll} by
# - cross-building a minimal static FreeType (the ONLY external C dep in
#   fb-embed mode — see vendor/freetype/VENDOR.md) via build-freetype.sh
# - building SolAstrius/mlterm-fb-embed (branch fb-embed) from source
#   under MLTERM_SRC against that FreeType
# - linking its libuitoolkit.a + libmlterm.a + libpobl/libmef into our
#   scev_term.c + scev_term_jni.c JNI shim
# - producing one .so the runtime can dlopen via System.load
#
# Expects:
#   MLTERM_SRC : checkout of github.com/SolAstrius/mlterm-fb-embed
#                pinned to a commit on the `fb-embed` branch
#   JAVA_HOME  : for jni.h
#   OUTPUT     : final output path for the linked library
#
# Cross-compilation (host build needs none of these):
#   TARGET       : zig -target triple (e.g. x86_64-linux-gnu). Empty = host.
#   HOST_TRIPLE  : autotools --host triple (e.g. x86_64-w64-mingw32). Empty
#                  = native. NOTE: zig's -target triple is NOT a valid
#                  autotools --host (config.sub rejects it), so the two are
#                  passed separately and deliberately differ.
#   JNI_PLATFORM_INCLUDE : dir holding the target's jni_md.h. Defaults to the
#                  JDK's own include/<os> for host builds, and the vendored
#                  jni_headers/<os> for cross builds.
#
# Toolchain: zig cc everywhere — bundles clang+lld+libc and cross-compiles
# without a second toolchain, mirroring openh264-jni and librvvm.

ifndef JAVA_HOME
$(error JAVA_HOME must be set)
endif
ifndef MLTERM_SRC
$(error MLTERM_SRC must point at a SolAstrius/mlterm-fb-embed checkout (branch fb-embed))
endif
ifndef OUTPUT
$(error OUTPUT must point at the final .so / .dylib / .dll path)
endif

# --- target / toolchain resolution -----------------------------------
TARGET      ?=
HOST_TRIPLE ?=
JOBS        ?= 4

# zig compiler, optionally cross-targeted.
ifeq ($(TARGET),)
  CC := zig cc
else
  CC := zig cc -target $(TARGET)
endif
CONFIGURE_HOST := $(if $(HOST_TRIPLE),--host=$(HOST_TRIPLE),)

# Per-target build tag; keeps FreeType prefixes and the configure stamp
# from colliding when more than one target is built from one tree.
FT_TAG := $(if $(TARGET),$(TARGET),host)

# Target OS — drives the JNI header dir and the system link libs. Sniffed
# from the triple when cross, from uname otherwise.
ifeq ($(TARGET),)
  UNAME_S := $(shell uname -s)
  ifeq ($(UNAME_S),Linux)
    TARGET_OS := linux
  else ifeq ($(UNAME_S),Darwin)
    TARGET_OS := darwin
  else
    TARGET_OS := windows
  endif
else
  ifneq (,$(findstring linux,$(TARGET)))
    TARGET_OS := linux
  else ifneq (,$(findstring macos,$(TARGET)))
    TARGET_OS := darwin
  else ifneq (,$(findstring darwin,$(TARGET)))
    TARGET_OS := darwin
  else ifneq (,$(findstring windows,$(TARGET)))
    TARGET_OS := windows
  else
    $(error cannot infer target OS from TARGET=$(TARGET))
  endif
endif

# JNI platform header dir. Host builds use the running JDK's own; cross
# builds use the vendored canonical OpenJDK headers (the host JDK has no
# subdir for a foreign OS). -I this BEFORE $(JAVA_HOME)/include so it wins.
JNI_VENDOR := $(CURDIR)/jni_headers
ifndef JNI_PLATFORM_INCLUDE
  ifeq ($(TARGET),)
    ifeq ($(TARGET_OS),linux)
      JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/linux
    else ifeq ($(TARGET_OS),darwin)
      JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/darwin
    else
      JNI_PLATFORM_INCLUDE := $(JAVA_HOME)/include/win32
    endif
  else
    ifeq ($(TARGET_OS),linux)
      JNI_PLATFORM_INCLUDE := $(JNI_VENDOR)/linux
    else ifeq ($(TARGET_OS),darwin)
      JNI_PLATFORM_INCLUDE := $(JNI_VENDOR)/darwin
    else
      JNI_PLATFORM_INCLUDE := $(JNI_VENDOR)/win32
    endif
  endif
endif

# --- FreeType (vendored, cross-built per target) ---------------------
MLTERM_BUILD    ?= $(abspath $(CURDIR)/../../build)
VENDOR_FREETYPE ?= $(abspath $(CURDIR)/../../vendor/freetype)
FT_TARBALL      ?= $(firstword $(wildcard $(VENDOR_FREETYPE)/freetype-*.tar.xz))
FT_PREFIX       ?= $(MLTERM_BUILD)/freetype/$(FT_TAG)
FT_LIB          := $(FT_PREFIX)/lib/libfreetype.a
FT_CFLAGS       := -I$(FT_PREFIX)/include/freetype2
FT_LIBS         := -L$(FT_PREFIX)/lib -lfreetype

# System libs on the final link line. fb-embed needs no fontconfig /
# libpng / libssh2 / SDL2 / zlib — just libc + math + threads, plus dlopen
# on Linux (folded into libc on macOS; not used on Windows).
ifeq ($(TARGET_OS),linux)
  SYS_LIBS := -lm -lpthread -ldl
else ifeq ($(TARGET_OS),darwin)
  SYS_LIBS := -lm -lpthread
else
  SYS_LIBS := -lm
endif

# libexecdir lives alongside the built library. In fb-embed mode the
# in-process loaders (stb_image, embed_regis) replace the registobmp /
# mlimgloader helpers, so this stays empty — kept only so the runtime's
# MLTERM_LIBEXECDIR lookup resolves to a real directory.
LIBEXEC_DIR := $(dir $(OUTPUT))mlterm-libexec

.PHONY: all configure-mlterm build-mlterm install-helpers clean

all: $(OUTPUT) install-helpers

# --- FreeType build --------------------------------------------------
$(FT_LIB):
	@test -n "$(FT_TARBALL)" || { echo "no freetype tarball under $(VENDOR_FREETYPE)"; exit 1; }
	FT_TARBALL="$(FT_TARBALL)" FT_PREFIX="$(FT_PREFIX)" \
	  TARGET="$(TARGET)" HOST_TRIPLE="$(HOST_TRIPLE)" JOBS="$(JOBS)" \
	  $(CURDIR)/build-freetype.sh

# --- mlterm configure ------------------------------------------------
# Per-target stamp: switching TARGET in one tree picks a new stamp, whose
# recipe distcleans the in-tree objects (autotools builds in-tree, so a
# stale arch would otherwise mix into the archives) before reconfiguring.
MLTERM_CONFIGURE_STAMP := $(MLTERM_SRC)/.scev-configured-$(FT_TAG)
configure-mlterm: $(MLTERM_CONFIGURE_STAMP)
$(MLTERM_CONFIGURE_STAMP): $(MLTERM_SRC)/configure.in $(FT_LIB)
	# Clean any objects left from a different target's configure in this
	# tree. No-op on a fresh checkout or the first build of a target.
	-$(MAKE) -C $(MLTERM_SRC) distclean >/dev/null 2>&1
	# mlterm ships pre-generated Makefile.in's. autoconf -f refreshes
	# configure; automake regen errors are non-fatal (the committed
	# Makefile.in's already cover us). autoreconf -fi would tear down
	# aclocal.m4 and clash with mlterm's bundled libtool macros, so avoid it.
	#
	# fb-embed sheds every dep but FreeType: fontconfig defaults off,
	# image goes through in-process stb_image, ReGIS is in-process, Sixel
	# built-in, ssh disabled, tools (registobmp/mlimgloader) dropped.
	# PKG_CONFIG_LIBDIR pins PKG_CHECK_MODULES(FT,...) to our cross
	# FreeType so the host's pkg-config paths can't leak in.
	cd $(MLTERM_SRC) && \
	  autoconf -f && \
	  (automake --add-missing --copy --foreign 2>/dev/null || true) && \
	  env -u PKG_CONFIG_PATH PKG_CONFIG_LIBDIR=$(FT_PREFIX)/lib/pkgconfig \
	    CC="$(CC)" AR="zig ar" RANLIB="zig ranlib" \
	    ./configure $(CONFIGURE_HOST) \
	      --with-gui=fb \
	      --enable-fb-embed \
	      --disable-otl \
	      --disable-ssh2 \
	      --disable-shared \
	      --enable-static \
	      --without-tools
	touch $(MLTERM_CONFIGURE_STAMP)

build-mlterm: configure-mlterm
	# Only build the targets we static-link against — skip fb-dumper /
	# mlterm-fb / man / etc. that the top-level Makefile builds via
	# MAKE_DIRS but we don't need (fb-dumper drags in xlib headers).
	$(MAKE) -C $(MLTERM_SRC)/baselib
	$(MAKE) -C $(MLTERM_SRC)/encodefilter
	# libind (Indian-script transliteration) builds before vtemu —
	# vtemu's libctl_iscii needs libind/libind.a to link.
	-$(MAKE) -C $(MLTERM_SRC)/libind
	$(MAKE) -C $(MLTERM_SRC)/vtemu
	# libctl static plugins (iscii / bidi) — vtemu's vt_line_shape.c
	# references their symbols when built without --disable-dl-ctl.
	-$(MAKE) -C $(MLTERM_SRC)/vtemu/libctl
	$(MAKE) -C $(MLTERM_SRC)/uitoolkit
	# main_loop.o + daemon.o are objects (not a lib) — build them
	# individually so the link step can pick them up.
	$(MAKE) -C $(MLTERM_SRC)/main main_loop.o daemon.o

# --- JNI shim --------------------------------------------------------
# Shim objects go in a per-target dir: switching TARGET must not reuse a
# stale-arch scev_term.o (the .c is unchanged across targets, so make
# would otherwise consider it up-to-date and link the wrong arch).
OBJ_DIR   := $(MLTERM_BUILD)/mlterm-jni-obj/$(FT_TAG)
SHIM_SRCS := scev_term.c scev_term_jni.c
SHIM_OBJS := $(addprefix $(OBJ_DIR)/,$(SHIM_SRCS:.c=.o))

CFLAGS := -O2 -fPIC -Wall \
  -DUSE_FRAMEBUFFER -DUSE_FB_EMBED \
  -I$(JNI_PLATFORM_INCLUDE) \
  -I$(JAVA_HOME)/include \
  -I$(MLTERM_SRC)/baselib/include \
  -I$(MLTERM_SRC)/baselib/src \
  -I$(MLTERM_SRC)/encodefilter/include \
  -I$(MLTERM_SRC)/encodefilter/src \
  -I$(MLTERM_SRC)/vtemu \
  -I$(MLTERM_SRC)/uitoolkit \
  -I$(MLTERM_SRC)/uitoolkit/fb \
  -I$(MLTERM_SRC)/main \
  -I$(MLTERM_SRC)/tool/registobmp \
  $(FT_CFLAGS)

# Static archives produced by mlterm's build. Order matters for the
# static link — uitoolkit references vtemu references baselib + encodefilter.
# Some are libtool-built (.libs/foo.a is the real archive); resolve each at
# make time with wildcard. Recursive (=) so paths resolve AFTER build-mlterm.
find_lib = $(firstword $(wildcard $(1)/.libs/$(2) $(1)/$(2)))
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

# Shim objects also depend on build-mlterm — pobl's "include/pobl" symlink
# dir is created by `make -C baselib`, and the shim transitively includes
# pobl/bl_types.h via ui_screen.h. Without this dep, parallel make can race.
$(OBJ_DIR)/%.o: %.c scev_term.h | build-mlterm
	@mkdir -p $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(OUTPUT): build-mlterm $(SHIM_OBJS)
	mkdir -p $(dir $(OUTPUT))
	# Wrap the mlterm archives in --start-group/--end-group so the linker
	# re-scans until no new undefined symbols remain (vtemu/libmlterm.a ↔
	# vtemu/libctl/libctl_iscii.a have circular back-references).
	$(CC) -shared $(SHIM_OBJS) \
	  -Wl,--start-group $(MLTERM_LIBS) -Wl,--end-group \
	  $(FT_LIBS) $(SYS_LIBS) -o $(OUTPUT)

install-helpers: build-mlterm
	# fb-embed renders images/ReGIS in-process, so there are no libexec
	# helpers to install — just ensure the dir exists for the runtime's
	# MLTERM_LIBEXECDIR lookup.
	mkdir -p $(LIBEXEC_DIR)/mlterm

clean:
	rm -f $(SHIM_OBJS) $(OUTPUT)
	rm -rf $(OBJ_DIR) $(LIBEXEC_DIR)
