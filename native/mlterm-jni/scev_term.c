/* SPDX-License-Identifier: MPL-2.0
 *
 * scev_term — direct mlterm primitive embed.
 *
 * Talks to vt_term + ui_font + ui_color + ui_screen + ui_display
 * directly. Does NOT call main_loop_init, ui_screen_manager_init,
 * ui_screen_manager_startup, ui_event_source_init, daemon_init,
 * vt_set_auto_restart_cmd, or bl_sig_child_start — all of which
 * the upstream "run mlterm as a program" path needs but an embed
 * host (a JVM, a game, a sandboxed launcher) doesn't.
 *
 * Lifecycle:
 *
 *   scev_term_init_once(font_path)        once per process
 *     ui_font_embed_lock_config(1)        // no ~/.mlterm reads
 *     vt_color_embed_lock_config(1)       // no ~/.mlterm/color reads
 *     ui_customize_font_file("font-fb",...)  // inject bundled font
 *     vt_term_manager_init(MAX_TERMS)
 *     vt_color_config_init()              // empty palette table
 *
 *   scev_term_new(cols, rows)             per terminal, repeatable
 *     ui_fb_embed_attach(buf, w, h, w)
 *     ui_display_open(NULL, 32)           // populates _disp
 *     vt_create_term(...)                 // VT100 parser + screen
 *     ui_font_manager_new(...)            // reads injected font map
 *     ui_color_manager_new(disp, ...)
 *     ui_screen_new(term, font, color, ...)
 *     ui_display_show_root(disp, &screen->window, ...)
 *
 *   scev_term_destroy(t)                  per terminal
 *     ui_display_remove_root(disp, &t->screen->window)
 *     ui_screen_destroy(t->screen)
 *     ui_color_manager_destroy(t->color_man)
 *     ui_font_manager_destroy(t->font_man)
 *     vt_destroy_term(t->term)
 *     ui_fb_embed_detach()
 *     free(t)
 *
 *   scev_term_shutdown()                  process exit, idempotent
 *     ui_display_close_all()
 *     vt_term_manager_final()
 *
 * The /bin/sleep placeholder PTY child is gone — this design uses
 * vt_term WITHOUT a pty. Bytes are pushed via vt_term_write_loopback
 * (see scev_term_write); render output is pumped via
 * ui_fb_embed_pump.
 *
 * mlterm globals (display, font cache, color cache) are still
 * process-wide, but each scev_term_t owns its own term + screen +
 * font_man + color_man, and the embed buffer is registered/released
 * per scev_term lifetime. Reopen works trivially. Multi-concurrent
 * is constrained by the single-buffer ui_fb_embed_attach contract;
 * one visible term at a time, which matches our use case.
 */

#include "scev_term.h"

#define _GNU_SOURCE
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <main_loop.h>            /* not called, but headers needed */
#include <ui_event_source.h>      /* ui_event_source_pump_once_nonblock */
#include <ui_screen.h>
#include <ui_window.h>            /* ui_fb_smooth_scroll_active */
#include <ui_display.h>
#include <ui_font_manager.h>
#include <ui_font_config.h>       /* ui_font_embed_lock_config, ui_customize_font_file */
#include <ui_color_manager.h>
#include <ui_shortcut.h>
#include <ui_fb_embed.h>
#include <vt_term.h>
#include <vt_term_manager.h>
#include <vt_color.h>
#include <vt_parser.h>            /* enums: ALT_COLOR_*, CS_*, NO_UNICODE_POLICY */
#include <vt_logical_visual.h>    /* enums: VERT_LTR, etc. */
#include <vt_pty.h>               /* vt_pty_embed_set_write_cb */
#include <embed_regis_text.h>     /* embed_regis_set_font_path */
#include <embed_imgloader.h>      /* embed_load_image_file (for ReGIS overlay) */

/* mlterm headers don't expose these as constants in their public
 * surface — they live inside ui_screen_flags.h / ui_bel_mode.h.
 * Pull them in. */
#include <ui_bel_mode.h>
#include <ui_screen_flags.h>

/* Lock-free SPSC ring buffer for input bytes pushed from the JNI
 * thread to the worker. Power-of-two size for cheap wraparound.
 * Single producer (Java/JNI thread calling scev_term_write),
 * single consumer (the worker thread). 64 KiB covers a fast
 * typist + a paste of a few KiB of escape sequences without
 * dropping. Drops silently on overflow — terminal-style input
 * doesn't need backpressure semantics. */
#define INPUT_RING_BYTES 65536
#define INPUT_RING_MASK  (INPUT_RING_BYTES - 1)

/* Reply ring — opposite direction. Worker writes bytes mlterm
 * pushed to vt_write_to_pty (DA / DSR / mouse-report / etc.
 * responses); JNI reader drains via scev_term_poll_reply and
 * the host forwards them upstream into the guest's UART RX
 * (same path as typed keystrokes). 4 KiB is plenty — DA replies
 * are ~30 bytes each and the host polls every render frame. */
#define REPLY_RING_BYTES 4096
#define REPLY_RING_MASK  (REPLY_RING_BYTES - 1)

struct scev_term {
  /* Geometry — set at construction, read by all threads. */
  int width;
  int height;
  int cols;
  int rows;

  /* Embed-side pixel buffer mlterm renders into (the "internal"
   * buffer registered via ui_fb_embed_attach). Worker reads from
   * here when copying into the publish buffer. */
  uint32_t *embed_buf;

  /* Two publish buffers, atomically swapped on each completed
   * worker frame. Reader (JNI render thread) does an
   * atomic_load(&publish_front) then memcpys from it; worker
   * paints into the OTHER one then atomic_exchanges. Worst case
   * the reader sees a frame that's one publish-cycle stale, never
   * a torn one. */
  uint32_t *publish_a;
  uint32_t *publish_b;
  _Atomic(uint32_t *) publish_front;

  /* Lock-free SPSC ring buffer (Java → worker). head/tail are
   * monotonic uint64 mod INPUT_RING_BYTES; the gap between them
   * tells us how full it is without ABA concerns within any
   * realistic uptime. */
  uint8_t *input_ring;
  _Atomic uint64_t input_head;   /* producer (JNI) writes here */
  _Atomic uint64_t input_tail;   /* consumer (worker) reads here */

  /* Reply ring (worker → JNI). Mirror layout — worker is the
   * producer (writes via the embed_pty_write_cb dispatch),
   * JNI poll is the consumer. */
  uint8_t *reply_ring;
  _Atomic uint64_t reply_head;
  _Atomic uint64_t reply_tail;

  /* Worker control. */
  pthread_t worker;
  _Atomic int worker_should_stop;
  int worker_started;

  /* Owned mlterm objects — TOUCHED ONLY FROM THE WORKER THREAD. */
  vt_term_t *term;
  ui_font_manager_t *font_man;
  ui_color_manager_t *color_man;
  ui_screen_t *screen;
  ui_shortcut_t shortcut;

  /* Graphics overlay plane — DEC-style "graphics layer" that
   * composites OVER the text plane. ReGIS DCS payloads land here
   * (via the get_picture_data interceptor below) instead of
   * mlterm's default "overwrite cells with picture data" path,
   * which destructively replaces the text on the screen. Same
   * pixel dims as embed_buf, ARGB host-endian, alpha=0 means
   * "transparent — show text underneath", any non-zero alpha
   * means "graphics shape, paint it". The worker composites this
   * onto embed_buf (after mlterm's text paint, before publish)
   * each tick. */
  uint32_t *overlay_buf;

  /* Saved original screen->xterm_listener.get_picture_data — we
   * wrap it with overlay_get_picture_data so .rgs files divert
   * into our overlay while .six and other formats keep the
   * upstream behaviour. */
  vt_char_t *(*orig_get_picture_data)(void *, char *, int *, int *,
                                      int *, int *, u_int32_t **,
                                      int *, int, int);
};

static void *worker_main(void *arg);

/* Single-active-term invariant lets us reach the active term from
 * the get_picture_data callback (mlterm passes screen as the
 * `self` pointer; we'd otherwise need a screen→scev_term map).
 * Same hook serves the embed pty-write callback (DA / DSR / etc.
 * replies). */
static scev_term_t *g_current = NULL;

/* Bridge for the fork's vt_pty_embed_set_write_cb — push reply
 * bytes into the active term's reply_ring. Drops on overflow
 * (reply ring is small + the host should be polling each frame;
 * if it isn't draining, dropping a stale DA response is the right
 * call rather than waiting). Called from the worker thread via
 * vt_write_to_pty(NULL, ...). */
static void scev_pty_reply_cb(const u_char *buf, size_t len) {
  scev_term_t *t = g_current;
  if (!t || !t->reply_ring || len == 0) return;

  uint64_t head = atomic_load_explicit(&t->reply_head, memory_order_relaxed);
  uint64_t tail = atomic_load_explicit(&t->reply_tail, memory_order_acquire);
  uint64_t free_space = REPLY_RING_BYTES - (head - tail);
  size_t to_write = len > free_space ? free_space : len;
  if (to_write == 0) return;

  size_t off = head & REPLY_RING_MASK;
  size_t first = (off + to_write > REPLY_RING_BYTES)
                 ? REPLY_RING_BYTES - off : to_write;
  memcpy(t->reply_ring + off, buf, first);
  if (first < to_write) {
    memcpy(t->reply_ring, buf + first, to_write - first);
  }
  atomic_store_explicit(&t->reply_head, head + to_write, memory_order_release);
}

/* Blit `src` (w_src × h_src ARGB pixels) onto the overlay plane
 * at position (dst_x, dst_y). Clips to overlay bounds. Pixels
 * with alpha == 0 are skipped (the source's transparent regions
 * leave the existing overlay state intact, which in our model is
 * "show text plane through"). */
static void overlay_blit(scev_term_t *t, const uint32_t *src,
                         int w_src, int h_src, int dst_x, int dst_y) {
  for (int sy = 0; sy < h_src; sy++) {
    int dy = dst_y + sy;
    if (dy < 0 || dy >= t->height) continue;
    for (int sx = 0; sx < w_src; sx++) {
      int dx = dst_x + sx;
      if (dx < 0 || dx >= t->width) continue;
      uint32_t p = src[sy * w_src + sx];
      if (p >> 24) {  /* alpha != 0 */
        t->overlay_buf[dy * t->width + dx] = p;
      }
    }
  }
}

/* Wrapped xterm_listener.get_picture_data: for .rgs files, load
 * the picture ourselves, blit into overlay, return NULL so
 * mlterm's show_picture skips the destructive cell-overwrite. For
 * any other format (.six, etc.) fall through to the original
 * callback so mlterm handles them as inline pictures normally. */
static vt_char_t *overlay_get_picture_data(
    void *p, char *file_path, int *num_cols, int *num_rows,
    int *num_cols_small, int *num_rows_small,
    u_int32_t **sixel_palette, int *transparent,
    int keep_aspect, int drcs_sixel) {
  size_t plen = file_path ? strlen(file_path) : 0;
  if (plen >= 4 && strcasecmp(file_path + plen - 4, ".rgs") == 0
      && g_current && g_current->overlay_buf) {
    u_char *image = NULL;
    u_int img_w = 0, img_h = 0;
    if (embed_load_image_file(file_path, 0, 0, 0, &image, &img_w, &img_h)) {
      /* Position: anchor at top-left of the terminal (col 0, row 0).
       * Real DEC ReGIS canvases are 800×480 in their own coordinate
       * space; mlterm's standard show_picture maps this to the cell
       * grid by treating each cell as a chunk. We don't scale —
       * the overlay is sampled 1:1 against the terminal pixel grid,
       * so the user's ReGIS coordinates land 1:1 on screen pixels.
       * For our 480×336 terminal that means a 800×480 ReGIS image
       * gets clipped to the visible area; future enhancement can
       * scale-to-fit. */
      overlay_blit(g_current, (const uint32_t *)image,
                   (int)img_w, (int)img_h, 0, 0);
      free(image);
    }
    /* Returning NULL tells mlterm "no picture cells to paint" —
     * it skips the goto_home + overwrite_chars dance entirely,
     * leaving the text cells under the cursor untouched. */
    return NULL;
  }
  if (g_current && g_current->orig_get_picture_data) {
    return g_current->orig_get_picture_data(p, file_path, num_cols, num_rows,
                                            num_cols_small, num_rows_small,
                                            sixel_palette, transparent,
                                            keep_aspect, drcs_sixel);
  }
  return NULL;
}

/* Composite overlay onto embed_buf in-place. Called from the
 * worker after ui_fb_embed_pump (so mlterm has already finished
 * its text paint into embed_buf). Pixels with alpha != 0 in the
 * overlay replace the embed pixel; alpha == 0 leaves text. */
static void composite_overlay(scev_term_t *t) {
  size_t n = (size_t)t->width * t->height;
  for (size_t i = 0; i < n; i++) {
    uint32_t o = t->overlay_buf[i];
    if (o >> 24) {  /* alpha != 0 */
      t->embed_buf[i] = o;
    }
  }
}

static int g_initialized = 0;
static int g_active = 0;
static char *g_font_path = NULL;

/* Cell pitch matching what the dumper renders for Cozette. The
 * fork's load_ft derives actual pitch from per-glyph metrics, but
 * we still need to size the embed buffer up front. */
#define CELL_PX_W 6
#define CELL_PX_H 14
#define MAX_TERMS 4

int scev_term_init_once(const char *font_path) {
  if (g_initialized) return 1;
  if (!font_path || !*font_path) {
    fprintf(stderr, "scev_term: font_path is required\n");
    return 0;
  }

  g_font_path = strdup(font_path);
  if (!g_font_path) return 0;

  /* Lock both config layers BEFORE the constructors that would
   * read them. From this point on, mlterm's font + color paths
   * never touch ~/.mlterm or /etc/mlterm. */
  ui_font_embed_lock_config(1);
  vt_color_embed_lock_config(1);

  /* Inject bundled font path for every charset mlterm may ask for.
   * This populates the in-memory custom_cache that
   * ui_acquire_font_config will drain (since the file readers are
   * skipped by the lock). DEFAULT and ISO10646_UCS4_1 cover ASCII
   * + Unicode lookups; mlterm picks the right one based on the
   * encoding it's running in (UTF-8 → ISO10646). */
  /* The short form "font" is what ui_customize_font_file matches
   * on (its strcmp branches expect short names — "font", "vfont",
   * "tfont", "aafont", etc.). The actual on-disk filename
   * underneath is "mlterm/font-fb" on framebuffer builds, but
   * we never read it because of the lock above. */
  ui_customize_font_file("font", "DEFAULT", g_font_path, 0);
  ui_customize_font_file("font", "ISO10646_UCS4_1", g_font_path, 0);

  /* Same font feeds the ReGIS T'…' command renderer (FreeType
   * raster, NOT mlterm's font cache — registobmp owns its own
   * face since the canvas it paints into is per-image, not the
   * terminal grid). The host extracts cozette.bdf to a temp dir
   * and we point both at it. */
  embed_regis_set_font_path(g_font_path);

  /* Set HOME to font's parent dir + create .mlterm/ inside it.
   * mlterm's DCS handlers (sixel/regis) WRITE the payload to
   * $HOME/.mlterm/<basename>.{rgs,six} before invoking the image
   * loader. With the user's real $HOME we'd pollute their home
   * dir; by pointing HOME at our scev-mlterm-XXX/ tempdir we
   * keep all temp files contained and let the OS's tmp cleanup
   * reap them at JVM exit. */
  char tmp_home[1024];
  snprintf(tmp_home, sizeof(tmp_home), "%s", g_font_path);
  char *slash = strrchr(tmp_home, '/');
  if (slash) {
    *slash = '\0';
    setenv("HOME", tmp_home, 1);
    char ml_dir[1100];
    snprintf(ml_dir, sizeof(ml_dir), "%s/.mlterm", tmp_home);
    mkdir(ml_dir, 0755);  /* ignore EEXIST */
  }

  if (!vt_term_manager_init(MAX_TERMS / 32 + 1)) {
    fprintf(stderr, "scev_term: vt_term_manager_init failed\n");
    free(g_font_path);
    g_font_path = NULL;
    return 0;
  }
  vt_color_config_init();

  /* Mlterm replies to host queries (DA / DSR / mouse reports / etc.)
   * via vt_write_to_pty(parser->pty, ...). With no real PTY the
   * fork routes those bytes to this callback instead — they end up
   * in the active term's reply_ring for the JNI host to pick up
   * via scev_term_poll_reply and forward back to the guest's UART
   * RX. Same direction as typed keystrokes, conceptually. */
  vt_pty_embed_set_write_cb(scev_pty_reply_cb);

  g_initialized = 1;
  return 1;
}

scev_term_t *scev_term_new(const char *term_type, int cols, int rows) {
  if (!g_initialized) {
    fprintf(stderr, "scev_term: scev_term_init_once must be called first\n");
    return NULL;
  }
  if (g_active) {
    fprintf(stderr, "scev_term: a term is already active "
                    "(single-buffer embed limits us to one at a time)\n");
    return NULL;
  }
  if (cols <= 0 || rows <= 0 || cols > 1000 || rows > 1000) {
    return NULL;
  }
  /* Default term_type if caller passed NULL — keeps old call sites
   * working and matches what we hardcoded before this parameter
   * existed. */
  if (!term_type || !*term_type) term_type = "xterm";

  scev_term_t *t = calloc(1, sizeof(*t));
  if (!t) return NULL;
  t->cols  = cols;
  t->rows  = rows;
  t->width  = cols * CELL_PX_W;
  t->height = rows * CELL_PX_H;

  size_t pixel_bytes = (size_t)t->width * t->height * sizeof(uint32_t);
  t->embed_buf = calloc(1, pixel_bytes);
  t->publish_a = calloc(1, pixel_bytes);
  t->publish_b = calloc(1, pixel_bytes);
  /* overlay_buf init to 0 → all transparent → text everywhere
   * shows through until ReGIS arrives. */
  t->overlay_buf = calloc(1, pixel_bytes);
  t->input_ring = malloc(INPUT_RING_BYTES);
  t->reply_ring = malloc(REPLY_RING_BYTES);
  if (!t->embed_buf || !t->publish_a || !t->publish_b ||
      !t->overlay_buf || !t->input_ring || !t->reply_ring) {
    goto err_buf;
  }
  atomic_init(&t->reply_head, 0);
  atomic_init(&t->reply_tail, 0);
  /* Initial publish_front is publish_a; first worker frame paints
   * into b, swaps in. */
  atomic_init(&t->publish_front, t->publish_a);
  atomic_init(&t->input_head, 0);
  atomic_init(&t->input_tail, 0);
  atomic_init(&t->worker_should_stop, 0);

  if (ui_fb_embed_attach(t->embed_buf, t->width, t->height, t->width) != 0) {
    fprintf(stderr, "scev_term: ui_fb_embed_attach failed\n");
    goto err_buf;
  }

  ui_display_t *disp = ui_display_open(NULL, 32);
  if (!disp) {
    fprintf(stderr, "scev_term: ui_display_open failed\n");
    goto err_attach;
  }

  /* vt_create_term defaults: UTF-8, modest scrollback, no bidi /
   * ind / multi-col-char gymnastics, normal cursor. term_type
   * is caller-supplied (vt100 / vt220 / vt340 / xterm / ...) and
   * drives mlterm's DA reply + accepted escape set. */
  t->term = vt_create_term(
      /* term_type */              term_type,
      /* cols, rows */             cols, rows,
      /* tab_size */               8,
      /* log_size (scrollback) */  1000,
      /* encoding */               VT_UTF8,
      /* is_auto_encoding */       0,
      /* use_auto_detect */        0,
      /* logging_vt_seq */         0,
      /* unicode_policy */         NO_UNICODE_POLICY,
      /* col_size_a */             1,
      /* use_char_combining */     1,
      /* use_multi_col_char */     1,
      /* use_ctl */                0,
      /* bidi_mode */              BIDI_NORMAL_MODE,
      /* bidi_separators */        NULL,
      /* use_dynamic_comb */       0,
      /* bs_mode */                BSM_DEFAULT,
      /* vertical_mode */          0,                 /* not vertical */
      /* use_local_echo */         0,
      /* win_name */               "scev-term",
      /* icon_name */              "scev-term",
      /* use_ansi_colors */        1,
      /* alt_color_mode */         0,                 /* no bold/ul/blink alt colors */
      /* use_ot_layout */          0,
      /* cursor_style */           CS_BLOCK | CS_BLINK,
      /* ignore_broadcasted */     1,
      /* use_locked_title */       0);
  if (!t->term) {
    fprintf(stderr, "scev_term: vt_create_term failed\n");
    goto err_attach;
  }

  /* Font manager. With ui_font_embed_lock_config(1) the manager
   * reads only our injected custom_cache entries — no FS scan. */
  t->font_man = ui_font_manager_new(
      /* display */                disp->display,
      /* type_engine */            TYPE_XCORE,
      /* font_present */           FONT_NOAA,
      /* font_size */              CELL_PX_H,
      /* usascii_font_cs */        ui_get_usascii_font_cs(VT_UTF8),
      /* step_in_changing_size */  1,
      /* letter_space */           0,
      /* use_bold */               0,
      /* use_italic */             0);
  if (!t->font_man) {
    fprintf(stderr, "scev_term: ui_font_manager_new failed\n");
    goto err_term;
  }

  /* Color manager. Pass literal color names; mlterm parses them
   * via its built-in name table (no rgb.txt read). */
  t->color_man = ui_color_manager_new(
      disp,
      /* fg */            "white",
      /* bg */            "black",
      /* cursor fg/bg */  NULL, NULL,
      /* bd / ul / bl / rv / it / co alt colors */
      NULL, NULL, NULL, NULL, NULL, NULL);
  if (!t->color_man) {
    fprintf(stderr, "scev_term: ui_color_manager_new failed\n");
    goto err_font;
  }

  ui_shortcut_init(&t->shortcut);

  /* Screen — wraps the term for rendering. Most knobs are off by
   * default; we use the same defaults the dumper uses. */
  t->screen = ui_screen_new(
      t->term, t->font_man, t->color_man,
      /* brightness, contrast, gamma */  100, 100, 100,
      /* alpha, fade_ratio */            255, 0,
      /* shortcut */                     &t->shortcut,
      /* screen_width_ratio */           100,
      /* mod_meta_key, mod_meta_mode */  NULL, MOD_META_NONE,
      /* bel_mode */                     BEL_NONE,
      /* receive_string_via_ucs */       0,
      /* pic_file_path */                NULL,
      /* use_transbg */                  0,
      /* use_vertical_cursor */          0,
      /* borderless */                   1,
      /* line_space */                   0,
      /* input_method */                 NULL,
      /* allow_osc52 */                  0,
      /* hmargin, vmargin */             0, 0,
      /* hide_underline */               0,
      /* underline_offset */             0,
      /* baseline_offset */              0);
  if (!t->screen) {
    fprintf(stderr, "scev_term: ui_screen_new failed\n");
    goto err_color;
  }

  /* Add the screen's root window to the display so the embed
   * pump's idling pass walks it on each call. */
  if (!ui_display_show_root(disp, &t->screen->window, 0, 0, 0,
                            "scev-term", NULL, 0)) {
    fprintf(stderr, "scev_term: ui_display_show_root failed\n");
    goto err_screen;
  }

  /* Hijack get_picture_data so .rgs files land in our overlay
   * plane instead of overwriting the cell grid. Done here, after
   * ui_screen_new (which initially set the listener to mlterm's
   * own xterm_get_picture_data) but BEFORE the worker spins up
   * (so no race between install and first DCS). */
  t->orig_get_picture_data = t->screen->xterm_listener.get_picture_data;
  t->screen->xterm_listener.get_picture_data = overlay_get_picture_data;
  g_current = t;

  /* Spawn the worker. From this moment on, NO mlterm call is
   * legal from any thread other than the worker — JNI methods on
   * the render thread go through input_ring + publish_front. */
  if (pthread_create(&t->worker, NULL, worker_main, t) != 0) {
    fprintf(stderr, "scev_term: pthread_create worker failed\n");
    goto err_screen;
  }
  t->worker_started = 1;

  g_active = 1;
  return t;

err_screen:
  ui_screen_destroy(t->screen);
  t->screen = NULL;
err_color:
  ui_color_manager_destroy(t->color_man);
  t->color_man = NULL;
err_font:
  ui_font_manager_destroy(t->font_man);
  t->font_man = NULL;
err_term:
  vt_destroy_term(t->term);
  t->term = NULL;
err_attach:
  ui_fb_embed_detach();
err_buf:
  free(t->embed_buf);
  free(t->publish_a);
  free(t->publish_b);
  free(t->overlay_buf);
  free(t->input_ring);
  free(t->reply_ring);
  free(t);
  return NULL;
}

void scev_term_destroy(scev_term_t *t) {
  if (!t) return;

  /* Signal the worker and join. From here on no mlterm call is
   * happening on any thread, so we can free safely on this one. */
  if (t->worker_started) {
    atomic_store_explicit(&t->worker_should_stop, 1, memory_order_release);
    pthread_join(t->worker, NULL);
    t->worker_started = 0;
  }

  /* Reverse-construction-order tear-down of mlterm objects.
   *
   * IMPORTANT: ui_display_remove_root() runs ui_window_final() on
   * the screen's window, which invokes window_finalized — and that
   * callback (ui_screen.c:1657) calls ui_screen_destroy(screen) +
   * free(screen) ITSELF. We must NOT then call ui_screen_destroy
   * again or we double-free the screen struct.
   *
   * On the failure path (where ui_display_show_root never ran),
   * remove_root is a no-op and we DO need the explicit destroy —
   * but those paths free their own screen via the err_screen label
   * in scev_term_new and never enter destroy. So this branch always
   * has a screen owned by the display by the time we get here. */
  u_int n_disps = 0;
  ui_display_t **disps = ui_get_opened_displays(&n_disps);
  if (n_disps > 0 && t->screen) {
    ui_display_remove_root(disps[0], &t->screen->window);
    /* screen is freed by the callback above. Drop our pointer
     * before any later code could deref it. */
    t->screen = NULL;
  }
  if (t->color_man) { ui_color_manager_destroy(t->color_man);  t->color_man = NULL; }
  if (t->font_man)  { ui_font_manager_destroy(t->font_man);    t->font_man  = NULL; }
  if (t->term)      { vt_destroy_term(t->term);                t->term      = NULL; }
  ui_fb_embed_detach();

  free(t->embed_buf);
  free(t->publish_a);
  free(t->publish_b);
  free(t->overlay_buf);
  free(t->input_ring);
  free(t->reply_ring);
  free(t);
  if (g_current == t) g_current = NULL;
  g_active = 0;
}

void scev_term_shutdown(void) {
  static int done = 0;
  if (done) return;
  done = 1;
  if (!g_initialized) return;

  ui_display_close_all();
  vt_term_manager_final();
  free(g_font_path);
  g_font_path = NULL;
  g_initialized = 0;
}

size_t scev_term_poll_reply(scev_term_t *t, uint8_t *out, size_t cap) {
  if (!t || !out || cap == 0) return 0;
  uint64_t head = atomic_load_explicit(&t->reply_head, memory_order_acquire);
  uint64_t tail = atomic_load_explicit(&t->reply_tail, memory_order_relaxed);
  uint64_t avail = head - tail;
  size_t to_read = avail > cap ? cap : (size_t)avail;
  if (to_read == 0) return 0;

  size_t off = tail & REPLY_RING_MASK;
  size_t first = (off + to_read > REPLY_RING_BYTES)
                 ? REPLY_RING_BYTES - off : to_read;
  memcpy(out, t->reply_ring + off, first);
  if (first < to_read) {
    memcpy(out + first, t->reply_ring, to_read - first);
  }
  atomic_store_explicit(&t->reply_tail, tail + to_read, memory_order_release);
  return to_read;
}

size_t scev_term_write(scev_term_t *t, const uint8_t *bytes, size_t len) {
  if (!t || !bytes || len == 0) return 0;

  /* SPSC enqueue. We're the only producer; the worker is the only
   * consumer. Acquire-load tail to see what the worker has already
   * drained, compute free space, copy what fits, release-store
   * head. Anything that doesn't fit is dropped silently — UI
   * keystrokes don't need backpressure. */
  uint64_t head = atomic_load_explicit(&t->input_head, memory_order_relaxed);
  uint64_t tail = atomic_load_explicit(&t->input_tail, memory_order_acquire);
  uint64_t free_space = INPUT_RING_BYTES - (head - tail);
  size_t to_write = len > free_space ? free_space : len;
  if (to_write == 0) return 0;

  size_t off = head & INPUT_RING_MASK;
  size_t first = (off + to_write > INPUT_RING_BYTES)
                 ? INPUT_RING_BYTES - off : to_write;
  memcpy(t->input_ring + off, bytes, first);
  if (first < to_write) {
    memcpy(t->input_ring, bytes + first, to_write - first);
  }
  atomic_store_explicit(&t->input_head, head + to_write, memory_order_release);
  return to_write;
}

int scev_term_pixel_w(scev_term_t *t) { return t ? t->width  : 0; }
int scev_term_pixel_h(scev_term_t *t) { return t ? t->height : 0; }

void scev_term_render(scev_term_t *t, uint32_t *out, int stride_px) {
  if (!t || !out) return;
  /* Snapshot the current published front buffer and memcpy from
   * it. The worker may swap fronts mid-copy; the worst case is
   * we read a frame that's now "behind" — never a torn one,
   * because the buffer we hold the pointer to isn't reclaimed
   * (it just becomes the next worker back-buffer, which the
   * worker won't paint into until its next iteration). */
  uint32_t *src = atomic_load_explicit(&t->publish_front, memory_order_acquire);
  for (int y = 0; y < t->height; y++) {
    memcpy(out + (size_t)y * stride_px,
           src + (size_t)y * t->width,
           (size_t)t->width * sizeof(uint32_t));
  }
}

void scev_term_render_abgr_ptr(scev_term_t *t, void *out_ptr, int stride_px) {
  if (!t || !out_ptr) return;
  uint32_t *src = atomic_load_explicit(&t->publish_front, memory_order_acquire);
  uint32_t *dst = (uint32_t *)out_ptr;
  for (int y = 0; y < t->height; y++) {
    const uint32_t *src_row = src + (size_t)y * t->width;
    uint32_t *dst_row = dst + (size_t)y * stride_px;
    for (int x = 0; x < t->width; x++) {
      uint32_t argb = src_row[x];
      uint32_t a = (argb >> 24) & 0xFF;
      uint32_t r = (argb >> 16) & 0xFF;
      uint32_t g = (argb >>  8) & 0xFF;
      uint32_t b =  argb        & 0xFF;
      dst_row[x] = (a << 24) | (b << 16) | (g << 8) | r;
    }
  }
}

/* ---------------- worker thread ---------------- */

/* Drain the input ring and feed bytes through the parser. Called
 * from the worker only. */
static void worker_drain_input(scev_term_t *t) {
  uint64_t head = atomic_load_explicit(&t->input_head, memory_order_acquire);
  uint64_t tail = atomic_load_explicit(&t->input_tail, memory_order_relaxed);
  if (head == tail) return;

  uint64_t avail = head - tail;
  size_t off = tail & INPUT_RING_MASK;
  /* Read up to the wraparound, push that chunk; loop will get the
   * second chunk on the next iteration via the new tail. */
  size_t first = (off + avail > INPUT_RING_BYTES)
                 ? INPUT_RING_BYTES - off : avail;
  if (t->term) {
    vt_term_write_loopback(t->term, t->input_ring + off, first);
  }
  atomic_store_explicit(&t->input_tail, tail + first, memory_order_release);
}

/* Sleep for ~16ms (1 frame at 60fps). Cheap, predictable, doesn't
 * tie us to any clock primitive that might not exist on Windows. */
static void worker_nap(void) {
  struct timespec ts = { 0, 16 * 1000 * 1000 };
  nanosleep(&ts, NULL);
}

/* Atomic publish: swap publish_front to point at the buffer we
 * just painted, return the OLD front (which becomes our next
 * back-buffer). */
static uint32_t *worker_publish(scev_term_t *t, uint32_t *back) {
  return atomic_exchange_explicit(&t->publish_front, back, memory_order_release);
}

static void *worker_main(void *arg) {
  scev_term_t *t = (scev_term_t *)arg;
  uint32_t *back = t->publish_b;   /* publish_a is the initial front */
  size_t pixel_count = (size_t)t->width * t->height;

  while (!atomic_load_explicit(&t->worker_should_stop, memory_order_acquire)) {
    /* §4.7.8 (VT100 TM, p. 4-95): "The auto XON/XOFF feature must be
     *  enabled and supported by the host computer to ensure that data
     *  is not lost when smooth scroll mode is enabled."
     *
     * Our equivalent: while a DECSCLM smooth-scroll animation is
     * mid-flight, refuse to drain new bytes from the input ring. The
     * ring fills, scev_term_write returns short to JNI, and the
     * guest's NS16550A bridge sees natural backpressure on UART RX —
     * the same flow-control behavior a real VT100 produced via
     * XON/XOFF when its 6-line/sec scroll budget was saturated. */
    if (!ui_fb_smooth_scroll_active()) {
      worker_drain_input(t);
    }

    /* Pump mlterm: parse + idling + paint dirty cells into
     * embed_buf. Non-blocking. */
    ui_fb_embed_pump();

    /* Composite the graphics overlay onto the freshly-painted text
     * frame. ReGIS DCS payloads land in t->overlay_buf via the
     * get_picture_data interceptor (alpha=0 elsewhere); the
     * non-transparent pixels overwrite the corresponding text
     * pixels in embed_buf, producing the DEC "graphics plane over
     * text plane" composition. */
    composite_overlay(t);

    /* Copy embed_buf → back, then publish. Single straight-line
     * memcpy keeps the publish atomic from the reader's POV. */
    memcpy(back, t->embed_buf, pixel_count * sizeof(uint32_t));
    back = worker_publish(t, back);

    worker_nap();
  }

  return NULL;
}
