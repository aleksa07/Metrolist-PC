package timber.log

/** Minimal stand-in for Android's Timber so the shared innertube sources compile on the JVM. */
object Timber {
    @Volatile var debug: Boolean = System.getenv("METROLIST_DEBUG") != null

    class Tree internal constructor(private val tag: String?) {
        fun v(message: String?, vararg args: Any?) = log("V", message, null)
        fun d(message: String?, vararg args: Any?) = log("D", message, null)
        fun i(message: String?, vararg args: Any?) = log("I", message, null)
        fun w(message: String?, vararg args: Any?) = log("W", message, null)
        fun w(t: Throwable?, message: String?, vararg args: Any?) = log("W", message, t)
        fun w(t: Throwable?) = log("W", null, t)
        fun e(message: String?, vararg args: Any?) = log("E", message, null)
        fun e(t: Throwable?, message: String?, vararg args: Any?) = log("E", message, t)
        fun e(t: Throwable?) = log("E", null, t)

        private fun log(level: String, message: String?, t: Throwable?) {
            if (!debug && (level == "V" || level == "D")) return
            val prefix = if (tag != null) "$level/$tag" else level
            System.err.println("$prefix: ${message.orEmpty()}${t?.let { " - $it" }.orEmpty()}")
        }
    }

    private val root = Tree(null)

    fun tag(tag: String): Tree = Tree(tag)
    fun v(message: String?, vararg args: Any?) = root.v(message, *args)
    fun d(message: String?, vararg args: Any?) = root.d(message, *args)
    fun i(message: String?, vararg args: Any?) = root.i(message, *args)
    fun w(message: String?, vararg args: Any?) = root.w(message, *args)
    fun w(t: Throwable?, message: String?, vararg args: Any?) = root.w(t, message, *args)
    fun w(t: Throwable?) = root.w(t)
    fun e(message: String?, vararg args: Any?) = root.e(message, *args)
    fun e(t: Throwable?, message: String?, vararg args: Any?) = root.e(t, message, *args)
    fun e(t: Throwable?) = root.e(t)
}
