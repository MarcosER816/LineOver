package dev.mark.lineover.core.engine

/**
 * Kotlin entry point of the native engine (liblineover_engine.so, built by
 * :core:engine-native). All functions are blocking: call them from a background
 * dispatcher, never from the main thread (rule R6).
 */
object LoEngine {
    init {
        System.loadLibrary("lineover_engine")
    }

    /** Semantic version of the native engine. */
    fun version(): String = nativeVersion()

    /** Applies the identity op to interleaved 8-bit samples and returns the result. */
    fun identity(samples: ByteArray): ByteArray = nativeIdentityU8(samples)

    /** Applies the identity op to interleaved float samples and returns the result. */
    fun identity(samples: FloatArray): FloatArray = nativeIdentityF32(samples)

    /**
     * GPU diagnostic: creates an off-screen GLES 3 context, clears it to the given
     * colour (components in 0..1) and reads one pixel back.
     *
     * @return the pixel as `[r, g, b, a]`, each 0..255
     * @throws IllegalStateException if the context cannot be created or GL reports an error
     */
    fun glClearReadback(
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ): IntArray = nativeGlClearReadback(red, green, blue, alpha)

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeIdentityU8(samples: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeIdentityF32(samples: FloatArray): FloatArray

    @JvmStatic
    private external fun nativeGlClearReadback(
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ): IntArray
}
