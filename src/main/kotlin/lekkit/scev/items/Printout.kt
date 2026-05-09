/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import java.util.Base64
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/**
 * Bitmap-backed page set carried by a [PrintoutItem] via the
 * `PRINTOUT_CONTENT` data component.
 *
 * One [Printout] = one or more rasterized pages of identical
 * dimensions, plus an optional title. The bitmap is **static** —
 * burned into the item at print time and never mutated afterwards;
 * all rendering goes through a content-keyed `DynamicTexture` cache
 * rather than the live framebuffer streaming path used for VM
 * displays.
 *
 * **Pixel format.** 4 bits per pixel, palette-indexed against a
 * 16-entry RGB palette stored alongside the pixel buffer. Two
 * pixels per byte: the **low nibble holds the even-x pixel**
 * (column 0, 2, 4, …) and the high nibble holds the odd-x pixel.
 * Row stride is `(width + 1) >>> 1` bytes; the full pixel buffer
 * holds `pageCount × stride × height` bytes laid out page-major.
 *
 * **Why 4bpp + palette.** ESC/P's natural output is monochrome,
 * but the LX-series 4-color ribbon (and any future graphics
 * encoding we choose to support) needs more than two colors. 16
 * indices is plenty of headroom — far more than any plausible
 * driver will ever use — without inflating storage to the point
 * where multi-page printouts blow the 1 MiB cap. A typical
 * 384×480 page is ~92 KiB; eight pages of that fit in ~720 KiB,
 * still inside the safety budget.
 *
 * The printer driver uses the [MONO_PALETTE] subset (paper at
 * index 0, ink at index 1) until colored ribbons exist; nothing
 * about the format changes when they arrive — drivers just write
 * higher palette indices.
 *
 * **Persistence.** Width / height / pageCount / title plus the
 * palette and a Base64-encoded pixel blob, mirroring
 * [FirmwareBlob]'s pattern. Network codec ships raw bytes
 * length-prefixed.
 *
 * **Equality.** Content-based, including palette, so the
 * renderer's texture cache can key on `Printout` directly — two
 * stacks with identical pixels and palette share one
 * `DynamicTexture`.
 */
class Printout(
    val width: Int,
    val height: Int,
    val pageCount: Int,
    val title: String,
    /** 16 RGB entries. Each int is `0x00RRGGBB`; alpha is implicit (always opaque). */
    val palette: IntArray,
    val pixels: ByteArray,
) {
    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "out-of-range dimensions: ${width}x$height"
        }
        require(pageCount in 1..MAX_PAGES) { "out-of-range page count: $pageCount" }
        require(palette.size == PALETTE_SIZE) {
            "palette must have exactly $PALETTE_SIZE entries; got ${palette.size}"
        }
        val expected = bytesPerPage(width, height) * pageCount
        require(pixels.size == expected) {
            "pixel buffer size ${pixels.size} != expected $expected for ${width}x$height × $pageCount"
        }
        require(pixels.size <= MAX_BYTES) {
            "printout exceeds size cap: ${pixels.size} > $MAX_BYTES"
        }
    }

    /**
     * Palette index (0..15) of pixel (x, y) on the given page.
     * Out-of-range coordinates return 0 (paper / first palette
     * entry) — convenient for renderers that read past the page
     * border without bounds checks.
     */
    fun pixel(page: Int, x: Int, y: Int): Int {
        if (page !in 0 until pageCount) return 0
        if (x !in 0 until width || y !in 0 until height) return 0
        val stride = (width + 1) ushr 1
        val offset = page * stride * height + y * stride + (x ushr 1)
        val byte = pixels[offset].toInt() and 0xFF
        return if ((x and 1) == 0) (byte and 0x0F) else (byte ushr 4) and 0x0F
    }

    /** RGB triple (`0x00RRGGBB`) of the pixel at (page, x, y). */
    fun rgb(page: Int, x: Int, y: Int): Int = palette[pixel(page, x, y)]

    override fun equals(other: Any?): Boolean =
        other is Printout && width == other.width && height == other.height &&
            pageCount == other.pageCount && title == other.title &&
            palette.contentEquals(other.palette) && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int {
        var h = width
        h = 31 * h + height
        h = 31 * h + pageCount
        h = 31 * h + title.hashCode()
        h = 31 * h + palette.contentHashCode()
        h = 31 * h + pixels.contentHashCode()
        return h
    }

    override fun toString(): String =
        "Printout[${width}x$height, ${pageCount}p, '$title']"

    companion object {
        /** Hard cap on individual axis lengths. Bounds the texture upload. */
        const val MAX_DIMENSION: Int = 4096

        /** Hard cap on page count per printout. */
        const val MAX_PAGES: Int = 64

        /** Hard cap on the raw pixel blob (1 MiB) — same ceiling as [FirmwareBlob]. */
        const val MAX_BYTES: Int = 1 shl 20

        /** Palette entries per printout — fixed 16 (4 bits per pixel). */
        const val PALETTE_SIZE: Int = 16

        /**
         * Default palette: index 0 = kraft / cream paper, index 1 =
         * black ink, indices 2..3 = the three remaining LX-series
         * ribbon hues (yellow / red / cyan, mixed to make the four
         * extra colors), 4..15 = pure black so out-of-range writes
         * read as ink. Drivers that don't care about color simply
         * write 0 / 1.
         */
        @JvmField
        val MONO_PALETTE: IntArray = IntArray(PALETTE_SIZE).apply {
            this[0] = 0xF6EBC8  // paper
            this[1] = 0x101010  // ink
            this[2] = 0xE0B040  // yellow ribbon band
            this[3] = 0xB02020  // magenta/red ribbon band
            this[4] = 0x2070A0  // cyan ribbon band
            for (i in 5 until PALETTE_SIZE) this[i] = 0x101010
        }

        /** Bytes one page occupies in the packed 4bpp layout. */
        @JvmStatic
        fun bytesPerPage(width: Int, height: Int): Int = ((width + 1) ushr 1) * height

        /** Build a blank (paper-only) printout — useful for tests / placeholders. */
        @JvmStatic
        fun blank(
            width: Int,
            height: Int,
            pageCount: Int = 1,
            title: String = "",
            palette: IntArray = MONO_PALETTE,
        ): Printout = Printout(
            width, height, pageCount, title,
            palette.copyOf(),
            ByteArray(bytesPerPage(width, height) * pageCount),
        )

        /** Codec for [pixels] alone, base64-string-shaped for legible NBT. */
        private val PIXEL_CODEC: Codec<ByteArray> = Codec.STRING.xmap(
            { Base64.getDecoder().decode(it) },
            { Base64.getEncoder().encodeToString(it) },
        )

        /** Codec for [palette] — fixed-length list of 24-bit RGB ints. */
        private val PALETTE_CODEC: Codec<IntArray> = Codec.INT.listOf().xmap(
            { list ->
                require(list.size == PALETTE_SIZE) {
                    "palette list size ${list.size} != $PALETTE_SIZE"
                }
                IntArray(PALETTE_SIZE) { list[it] and 0xFFFFFF }
            },
            { arr -> arr.toList() },
        )

        @JvmField
        val CODEC: Codec<Printout> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.intRange(1, MAX_DIMENSION).fieldOf("width").forGetter(Printout::width),
                Codec.intRange(1, MAX_DIMENSION).fieldOf("height").forGetter(Printout::height),
                Codec.intRange(1, MAX_PAGES).fieldOf("pages").forGetter(Printout::pageCount),
                Codec.STRING.optionalFieldOf("title", "").forGetter(Printout::title),
                PALETTE_CODEC.optionalFieldOf("palette", MONO_PALETTE.copyOf())
                    .forGetter(Printout::palette),
                PIXEL_CODEC.fieldOf("pixels").forGetter(Printout::pixels),
            ).apply(instance, ::Printout)
        }

        /** Stream codec for `palette`. */
        private val PALETTE_STREAM_CODEC: StreamCodec<ByteBuf, IntArray> = StreamCodec.of(
            { buf, arr ->
                require(arr.size == PALETTE_SIZE)
                for (entry in arr) buf.writeMedium(entry and 0xFFFFFF)
            },
            { buf ->
                IntArray(PALETTE_SIZE) { buf.readUnsignedMedium() and 0xFFFFFF }
            },
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, Printout> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Printout::width,
            ByteBufCodecs.VAR_INT, Printout::height,
            ByteBufCodecs.VAR_INT, Printout::pageCount,
            ByteBufCodecs.STRING_UTF8, Printout::title,
            PALETTE_STREAM_CODEC, Printout::palette,
            ByteBufCodecs.byteArray(MAX_BYTES), Printout::pixels,
            ::Printout,
        )
    }
}
