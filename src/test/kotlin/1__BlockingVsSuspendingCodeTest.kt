import kotlinx.coroutines.*
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessThan
import kotlin.system.measureTimeMillis
import kotlin.test.Test

class BlockingVsSuspendingCodeTest {

    @Test
    fun `non-blocking calls should be very fast and last less than 200ms`() {
        val nonBlockingCalls = measureTimeMillis {
            runBlocking {
                for (i in 1..100) {
                    launch {
                        doApiCall()
                    }
                }
            }
        }
        nonBlockingCalls shouldBeLessThan 250
    }

    @Test
    fun `blocking calls should be very slow when using default dispatcher`() {
        val callOnDefault = measureTimeMillis {
            runBlocking {
                for (i in 1..100) {
                    launch(Dispatchers.Default) {
                        doApiCallBlocking()
                    }
                }
            }
        }
        callOnDefault shouldBeGreaterThan 900
    }

    @Test
    fun `blocking calls should be faster when using IO dispatcher as it has more threads`() {
        val callOnIO = measureTimeMillis {
            runBlocking {
                for (i in 1..100) {
                    launch(Dispatchers.IO) {
                        doApiCallBlocking()
                    }
                }
            }
        }
        callOnIO shouldBeLessThan 300
    }

    private suspend fun doApiCall() = coroutineScope {
        delay(100)
        "Job done"
    }

    suspend fun doApiCallBlocking() = coroutineScope {
        Thread.sleep(100)
        "Job done"
    }
}