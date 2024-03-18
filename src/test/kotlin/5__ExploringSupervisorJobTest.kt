import kotlinx.coroutines.*
import mu.KotlinLogging
import org.amshove.kluent.shouldBeEqualTo
import kotlin.test.Test

private val logger = KotlinLogging.logger {}

class ExploringSupervisorJobTest {

    @Test
    fun `supervisor job is not cancelled when child throws an exception`(): Unit = runBlocking {
        val supervisorJob = SupervisorJob()
        val scope = CoroutineScope(supervisorJob)

        // root-level coroutine that launches 2 child coroutines. It will be cancelled at the end
        val job1 = scope.launch(genericExceptionHandler()) {
            // first child job throws exception and cancels sibling and parent
            launch {
                delay(500)
                throw RuntimeException("test from launch")
            }

            // second child job gets cancelled because of structured concurrency
            launch {
                delay(1000)
                logger.info { "this will never run" }
            }
        }

        // this job will complete because of supervisor job
        val job2 = scope.launch {
            delay(1000)
            logger.info { "This is job that completes despite previous being cancelled" }
        }

        joinAll(job1, job2)

        // first job was cancelled because of exception in child coroutine
        job1.isCompleted shouldBeEqualTo true
        job1.isCancelled shouldBeEqualTo true

        // second job completed normally
        job2.isCompleted shouldBeEqualTo true
        job2.isCancelled shouldBeEqualTo false

        // scope is still active
        scope.isActive shouldBeEqualTo true
    }

    @Test
    fun `supervisorScope can be used to run a piece of code that will not cancel parent in case of exception`(): Unit = runBlocking {
        // ordinary scope as top level
        val scope = CoroutineScope(Job())
        lateinit var firstInnerJob: Job
        lateinit var secondInnerJob: Job
        val rootLevelJob = scope.launch {
            // create new scope that blocks execution until all children completed. Value can be returned from it
            supervisorScope {
                // first child job throws exception
                firstInnerJob = launch(genericExceptionHandler()) {
                    delay(500)
                    throw RuntimeException("test from launch")
                }

                // second child job finishes running and is not affected by first one failing
                secondInnerJob = launch {
                    delay(1000)
                    logger.info { "Done with job 2. Is job 1 cancelled:${firstInnerJob.isCancelled}" }
                }
            }
            logger.info { "After supervisorScope block" }
        }
        rootLevelJob.join()
        // scope job is completed without cancellation
        rootLevelJob.isCompleted shouldBeEqualTo true
        rootLevelJob.isCancelled shouldBeEqualTo false
        scope.isActive shouldBeEqualTo true

        // first child job is cancelled and second one is not
        firstInnerJob.isCompleted shouldBeEqualTo true
        firstInnerJob.isCancelled shouldBeEqualTo true

        secondInnerJob.isCompleted shouldBeEqualTo true
        secondInnerJob.isCancelled shouldBeEqualTo false

    }

    @Test
    fun `exceptions thrown inside supervisorScope are propagated to the containing coroutine`(): Unit = runBlocking {
        // ordinary scope as top level
        val scope = CoroutineScope(Job())
        val exceptionRetriever = ExceptionRetriever()
        lateinit var firstInnerJob: Deferred<Any>
        lateinit var secondInnerJob: Job
        val rootLevelJob = scope.launch(exceptionRetriever.handler()) {
            // create new scope that blocks execution until all children completed. Value can be returned from it
            supervisorScope {
                // first child job throws exception
                firstInnerJob = async {
                    delay(500)
                    throw RuntimeException("test from async")
                }

                // second child gets cancelled because of exception thrown by firstInnerJob.await()
                secondInnerJob = launch {
                    delay(1000)
                    logger.info { "this is unreachable because of exception above" }
                }

                firstInnerJob.await()
            }
            logger.info { "This is unreachable" }
        }

        rootLevelJob.join()
        // scope job is cancelled
        rootLevelJob.isCompleted shouldBeEqualTo true
        rootLevelJob.isCancelled shouldBeEqualTo true
        scope.isActive shouldBeEqualTo false

        // both child jobs were cancelled
        firstInnerJob.isCompleted shouldBeEqualTo true
        firstInnerJob.isCancelled shouldBeEqualTo true

        secondInnerJob.isCompleted shouldBeEqualTo true
        secondInnerJob.isCancelled shouldBeEqualTo true

        exceptionRetriever.caughtException!!.message shouldBeEqualTo "test from async"
    }

    @Test
    fun `supervisor scope can be created with inherited context providing non-blocking version of supervisorScope`() {
        runBlocking {
            // scope that inherits context form parent but is a supervisor job
            // separate job means that it is not structured concurrency
            val scopeWithSupervisor = CoroutineScope(coroutineContext + SupervisorJob())
            // child throws exception and is cancelled
            val firstJob = scopeWithSupervisor.launch(genericExceptionHandler()) {
                delay(500)
                throw RuntimeException("test from launch")
            }

            // second child finishes running
            val secondJob = scopeWithSupervisor.launch {
                delay(1000)
                logger.info { "Done with job 2. Is job 1 cancelled:${firstJob.isCancelled}" }
            }

            // this has to be done because scope is not in parent -> child relationship
            joinAll(firstJob, secondJob)

            // first job was cancelled because of exception
            firstJob.isCompleted shouldBeEqualTo true
            firstJob.isCancelled shouldBeEqualTo true

            // second job completed normally
            secondJob.isCompleted shouldBeEqualTo true
            secondJob.isCancelled shouldBeEqualTo false

            // scope is still active
            scopeWithSupervisor.isActive shouldBeEqualTo true
        }
    }
}