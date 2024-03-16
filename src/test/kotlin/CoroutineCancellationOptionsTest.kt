import kotlinx.coroutines.*
import mu.KotlinLogging
import org.amshove.kluent.shouldBeEqualTo
import kotlin.test.Test

private val logger = KotlinLogging.logger {}

class CoroutineCancellationOptionsTest {

    @Test
    fun `single child coroutine can be cancelled without cancelling parent`() = runBlocking {
        val scope = CoroutineScope(Job())

        // root-level coroutine
        val job = scope.launch {
            // long running job
            val job1 = launch {
                delay(1000)
                logger.info { "Done with job 1" }
            }
            // child job that cancels it's sibling. Also parent can cancel child jobs
            val job2 = launch {
                delay(100)
                job1.cancel()
                delay(500)
                logger.info { "Done with job 2" }
            }
            joinAll(job1, job2)
        }
        job.join()

        // job has completed without cancellation and scope is still active
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo false
        scope.isActive shouldBeEqualTo true

        // scope can be used to launch new coroutines
        scope.launch { logger.info { "New coroutine after job completed" } }.join()
    }

    @Test
    fun `child coroutine timeout is not affecting siblings nor parent`() = runBlocking {
        val scope = CoroutineScope(Job())
        // root-level coroutine
        val job = scope.launch {
            // long running job
            val job1 = launch {
                delay(1000)
                logger.info { "Done with job 1" }
            }

            // child job that times out also cancels siblings and parent, but not whole scope
            val job2 = withTimeout(200) {
                launch {
                    delay(500)
                    logger.info { "Done with job 2" }
                }
            }
            joinAll(job1, job2)
        }
        job.join()

        // job has completed with cancellation but scope is still active
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true
        scope.isActive shouldBeEqualTo true

        // scope can be used to launch new coroutines
        scope.launch { logger.info { "New coroutine after job completed with timeout" } }.join()
    }

    @Test
    fun `uncaught exception in launch is cancelling siblings, parent and whole scope`(): Unit = runBlocking {
        var rootLevelException = ExceptionRetriever()
        val scope = CoroutineScope(Job())
        val job = scope.launch(rootLevelException.handler()) {
            // long running job
            val job1 = launch {
                delay(5000)
                logger.info { "Done with job 1" }
            }

            // child job that cancels whole scope
            val job2 = launch {
                delay(500)
                throw IllegalStateException("exception from job 2")
            }

            joinAll(job1, job2)
        }
        job.join()

        // job has completed with cancellation and scope is inactive
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true
        scope.isActive shouldBeEqualTo false

        // exception was intercepted at root level but it happens after everything is cancelled
        rootLevelException.caughtException!!.message shouldBeEqualTo "exception from job 2"
    }

    @Test
    fun `coroutine started at root level that throws exception cancels all siblings and whole scope`(): Unit =
        runBlocking {
            val scope = CoroutineScope(Job())
            val job1 = scope.launch(genericExceptionHandler()) {
                delay(500)
                throw IllegalStateException("test in job2")
            }

            // job2 will not complete because of whole scope cancellation
            val job2 = scope.launch {
                delay(1000)
                logger.info { "This is unreachable" }
            }
            joinAll(job1, job2)

            // job that thrown exception is completed and cancelled
            job1.isCompleted shouldBeEqualTo true
            job1.isCancelled shouldBeEqualTo true

            // sibling was also cancelled
            job2.isCompleted shouldBeEqualTo true
            job2.isCancelled shouldBeEqualTo true

            // whole scope is inactive
            scope.isActive shouldBeEqualTo false
        }

    @Test
    fun `uncaught exception in launch is not cancelling parent with SupervisorJob job`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launch(genericExceptionHandler()) {
            delay(500)
            throw IllegalStateException("test in job2")
        }

        job.join()

        // job that thrown exception is completed and cancelled
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true

        // whole scope is still active
        scope.isActive shouldBeEqualTo true
        scope.launch { logger.info { "New coroutine after job completed" } }.join()
    }

    @Test
    fun `async that throws exception and is not awaited cancels parent coroutine and whole scope`(): Unit =
        runBlocking {
            var rootLevelException = ExceptionRetriever()
            val scope = CoroutineScope(Job())
            val job = scope.launch(rootLevelException.handler()) {
                async {
                    delay(500)
                    throw IllegalStateException("exception from nested async")
                }
            }

            job.join()

            // job with nested async is completed and cancelled. Scope is closed
            job.isCompleted shouldBeEqualTo true
            job.isCancelled shouldBeEqualTo true
            scope.isActive shouldBeEqualTo false

            // exception was propagated to root-level coroutine because of missing await
            rootLevelException.caughtException!!.message shouldBeEqualTo "exception from nested async"
        }
}