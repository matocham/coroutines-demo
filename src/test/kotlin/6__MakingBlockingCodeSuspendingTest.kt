import kotlinx.coroutines.*
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeLessThan
import kotlin.coroutines.resume
import kotlin.system.measureTimeMillis
import kotlin.test.Test

class MakingBlockingCodeSuspendingTest {
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun `it should take a lot of time to run multiple blocking operations simultaneously`(): Unit = runBlocking {
        val singleThreadDispatcher = newSingleThreadContext("test")
        val time = measureTimeMillis {
            withContext(singleThreadDispatcher) {
                val jobs = (1..10).map {
                    async { blockingIOOperation() }
                }

                jobs.awaitAll()
            }
        }

        // 10*100ms
        time shouldBeGreaterOrEqualTo 1000
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun `should run multiple blocking IO operations as suspending function`(): Unit = runBlocking {
        val singleThreadDispatcher = newSingleThreadContext("test")
        val time = measureTimeMillis {
            withContext(singleThreadDispatcher) {
                val jobs = (1..50).map {
                    async { ioOperationSuspending() }
                }

                jobs.awaitAll()
            }
        }

        // 50 * 100ms = 5000ms but it runs about x10 faster
        time shouldBeLessThan 600
    }
}

fun blockingIOOperation(): String {
    // Simulate a delay representing I/O operation
    Thread.sleep(100)
    return "Result of blocking I/O operation"
}

@OptIn(DelicateCoroutinesApi::class)
suspend fun ioOperationSuspending(): String = suspendCancellableCoroutine { continuation ->
    // Start blocking operation in separate thread. Thread pool can be used here
    GlobalScope.launch {
        try {
            // Call the blocking I/O operation
            val result = blockingIOOperation()
            // Resume the continuation with the result
            continuation.resume(result)
        } catch (e: Exception) {
            // Cancel the continuation if an exception occurs
            continuation.cancel(e)
        }
    }

    // Register cancellation handler to cancel the blocking operation if needed
    continuation.invokeOnCancellation {
        // Cancel the blocking operation if it is still in progress
        // eg. close open IO connections, close opened files etc
        Thread.currentThread().interrupt()
    }
}