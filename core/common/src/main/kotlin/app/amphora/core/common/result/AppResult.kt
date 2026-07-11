package app.amphora.core.common.result

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AmphoraError) : AppResult<Nothing>
}

inline fun <T> appResult(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        AppResult.Failure(AmphoraError.from(t))
    }

sealed class AmphoraError(open val message: String) {
    data class Io(override val message: String, val cause: Throwable? = null) : AmphoraError(message)
    data class Native(override val message: String, val cause: Throwable? = null) : AmphoraError(message)
    data class Content(override val message: String, val cause: Throwable? = null) : AmphoraError(message)
    data class Unknown(override val message: String, val cause: Throwable? = null) : AmphoraError(message)

    companion object {
        fun from(t: Throwable): AmphoraError = Unknown(t.message ?: "Unknown error", t)
    }
}
