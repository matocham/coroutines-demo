import kotlinx.coroutines.CoroutineExceptionHandler
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun genericExceptionHandler() = CoroutineExceptionHandler { _, exception ->
    logger.info { "Uncaught exception: $exception" }
}

class ExceptionRetriever {
    var caughtException: Throwable? = null

    fun handler() = CoroutineExceptionHandler { _, exception ->
        caughtException = exception
    }
}