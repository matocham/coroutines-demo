import kotlinx.coroutines.*
import mu.KotlinLogging
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import kotlin.test.Test
import kotlin.test.fail

private val logger = KotlinLogging.logger {}

class CoroutineExceptionHandlerUsageTest {
    @Test
    fun `exceptions not caught inside launch are propagated to the root coroutine`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        val rootExceptionHandler = ExceptionRetriever()
        val innerExceptionHandler = ExceptionRetriever()

        lateinit var job1: Job
        lateinit var job2: Job
        // scope is a root coroutine - it is not a child of runBlocking
        // exception handler is installed to handle all uncaught exceptions - it is optional
        val job = scope.launch(rootExceptionHandler.handler()) {
            // first job - it will be cancelled because sibling thrown
            job1 = launch {
                delay(5000)
                logger.info { "this will never be reached" }
            }
            // child job with installed exception handler
            // child coroutines created using launch will not use exception handler but propagate it to the parent
            // exceptions should be handled inside launch
            job2 = launch(innerExceptionHandler.handler()) {
                delay(500)
                throw RuntimeException("test from launch")
            }
        }

        job.join()

        // job was cancelled
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true

        // long-running job was cancelled before completing
        job1.isCompleted shouldBeEqualTo true
        job1.isCancelled shouldBeEqualTo true

        // job with exception was cancelled
        job2.isCompleted shouldBeEqualTo true
        job2.isCancelled shouldBeEqualTo true

        // whole scope is cancelled
        scope.isActive shouldBeEqualTo false

        // only root-level coroutine caught exception
        rootExceptionHandler.caughtException shouldNotBe null
        innerExceptionHandler.caughtException shouldBe null
    }

    @Test
    fun `runBlocking will throw uncaught exceptions to synchronous code`() {
        val exceptionRetriever = ExceptionRetriever()
        try {
            runBlocking {
                // launch child job
                launch(exceptionRetriever.handler()) {
                    // another nested job that throws
                    launch {
                        delay(500)
                        throw RuntimeException("test from launch")
                    }
                }
            }
            fail("This should be unreachable")
        } catch (e: Exception) {
            e.message shouldBeEqualTo "test from launch"
        }

        // exception was not received because launch is not root-level here
        exceptionRetriever.caughtException shouldBe null
    }

    @Test
    fun `exceptions thrown in async have to be handled when await is invoked`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        // root-level coroutine started using async
        val job = scope.async {
            // child async that throws
            val value = async {
                delay(100)
                throw RuntimeException("Exception from async")
            }
            // here exception is not handled
            value.await()
        }

        try {
            job.await()
            fail("This should be unreachable")
        } catch (e: Exception) {
            e.message shouldBeEqualTo "Exception from async"
        }

        // job was cancelled and scope is no longer active
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true
        scope.isActive shouldBeEqualTo false
    }

    @Test
    fun `exceptions thrown in async inside launch are propagated to exception handlers`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        val exceptionRetriever = ExceptionRetriever()
        // process unhandled exceptions on root level
        val job = scope.launch(exceptionRetriever.handler()) {
            // child async that throws
            val value = async {
                delay(100)
                throw RuntimeException("Exception from async")
            }
            // wait for throw. This code would work in the same way without it
            value.await()
        }

        // wait for whole coroutine
        job.join()

        // job was cancelled and scope is no longer active
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true

        scope.isActive shouldBeEqualTo false

        // exception was propagated to root-level launch
        exceptionRetriever.caughtException!!.message shouldBeEqualTo "Exception from async"
    }

    @Test
    fun `exceptions thrown in async inside launch are propagated to exception handlers even without await`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        val exceptionRetriever = ExceptionRetriever()
        // process unhandled exceptions on root level
        val job = scope.launch(exceptionRetriever.handler()) {
            // child async that throws. It is not waited inside launch but still exception si propagated
            async {
                delay(100)
                throw RuntimeException("Exception from async")
            }
        }

        // wait for whole coroutine
        job.join()

        // job was cancelled and scope is no longer active
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true

        scope.isActive shouldBeEqualTo false

        // exception was propagated to root-level launch
        exceptionRetriever.caughtException!!.message shouldBeEqualTo "Exception from async"
    }

    @Test
    fun `exception handler is not required in root-level launch`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())

        // root-level job without exception handler
        val job = scope.launch {
            // inner job that throws exception
            launch {
                delay(500)
                throw RuntimeException("test from launch")
            }
        }

        job.join()

        // job is done and exception is not thrown outside the scope
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true
        scope.isActive shouldBeEqualTo false
    }

}