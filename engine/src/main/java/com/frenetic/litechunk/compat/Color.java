package com.frenetic.litechunk.compat;

/**
 * Minimal stand-in for {@code java.awt.Color}, which does not exist on Android.
 * <p>
 * Chunker only ever uses {@code Color} as an immutable ARGB holder: it builds values with
 * {@code new Color(r, g, b, a)} and reads them back through {@code getRed/getGreen/getBlue/getAlpha/getRGB}.
 * Re-implementing that subset keeps the engine source untouched apart from one import line per file,
 * and keeps the desktop JVM tests working (they compile against this class too).
 * <p>
 * The packed value matches AWT's {@code getRGB()} contract: non-premultiplied sRGB, alpha in the high byte.
 */
public final class Color {

    public static final Color WHITE = new Color(255, 255, 255, 255);
    public static final Color BLACK = new Color(0, 0, 0, 255);

    private final int value;

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public Color(int r, int g, int b, int a) {
        this.value = pack(r, g, b, a);
    }

    /** Creates a colour from a packed 0xRRGGBB value; alpha is forced opaque, like {@code java.awt.Color}. */
    public Color(int rgb) {
        this.value = 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    public Color(int rgba, boolean hasAlpha) {
        this.value = hasAlpha ? rgba : (0xFF000000 | (rgba & 0x00FFFFFF));
    }

    private static int pack(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public int getRed() {
        return (value >> 16) & 0xFF;
    }

    public int getGreen() {
        return (value >> 8) & 0xFF;
    }

    public int getBlue() {
        return value & 0xFF;
    }

    public int getAlpha() {
        return (value >>> 24) & 0xFF;
    }

    public int getRGB() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Color)) {
            return false;
        }
        return value == ((Color) other).value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public String toString() {
        return "Color[r=" + getRed() + ",g=" + getGreen() + ",b=" + getBlue() + ",a=" + getAlpha() + "]";
    }
}
