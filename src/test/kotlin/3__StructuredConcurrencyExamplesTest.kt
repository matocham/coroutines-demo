import kotlinx.coroutines.*
import mu.KotlinLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import kotlin.test.Test

private val logger = KotlinLogging.logger {}

class StructuredConcurrencyExamplesTest {

    @Test
    fun `parent coroutine waits for all it's children to complete`() {
        lateinit var job: Job
        lateinit var internalJob: Job

        runBlocking {
            // create outer job
            job = launch {
                logger.info { "Starting child with delay" }
                // create inner job
                internalJob = launch {
                    delay(500)
                    logger.info { "Child finished waiting" }
                }
                logger.info { "After launching new child" }
            }
            // when job is joined it also waits for its children
            job.join()

            job.isCompleted shouldBeEqualTo true
            internalJob.isCompleted shouldBeEqualTo true
        }
    }

    @Test
    fun `parent implicitly waits for child created using async even without await`(): Unit = runBlocking {
        lateinit var innerJob: Deferred<Any>
        // outer job
        val job = launch {
            logger.info { "Starting child with delay" }
            // inner job started using async
            innerJob = async {
                delay(500)
                logger.info { "Child finished waiting" }
            }
            logger.info { "After launching new child" }
        }
        // when outer job is joined then also async job is completed
        job.join()

        job.isCompleted shouldBeEqualTo true
        innerJob.isCompleted shouldBeEqualTo true
    }

    @Test
    fun `coroutines created using standalone scope are not awaited by enclosing coroutine`(): Unit = runBlocking {
        // create standalone scope
        val scope = CoroutineScope(Job())

        lateinit var innerJob: Job
        // outer job is started
        val job = launch {
            logger.info { "Launching standalone coroutine" }
            // inner job is launched using standalone scope
            innerJob = scope.launch {
                delay(500)
                logger.info { "Done with standalone coroutine" }
            }
        }
        // joining outer job is not waiting for standalone job
        job.join()

        job.isCompleted shouldBeEqualTo true
        innerJob.isCompleted shouldBeEqualTo false

        innerJob.join()

        innerJob.isCompleted shouldBeEqualTo true
        innerJob.isCancelled shouldBeEqualTo false
    }

    @Test
    fun `parent coroutine can manually wait for coroutines outside of structured concurrency`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        lateinit var innerJob: Job

        val job = launch {
            logger.info { "Launching standalone coroutine" }
            // inner job that is not connected to parent
            innerJob = scope.launch {
                delay(500)
                logger.info { "Done with standalone coroutine" }
            }
            // explicitly await for inner job
            innerJob.join()
        }
        // when outer job is done then also inner job is done
        job.join()

        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo false

        innerJob.isCompleted shouldBeEqualTo true
    }

    @Test
    fun `exceptions thrown inside standalone coroutines are not cancelling enclosing coroutine`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        val innerScope = CoroutineScope(Job())
        lateinit var innerJob: Job
        // outer job is started in one scope
        val job = scope.launch {
            logger.info { "Starting standalone coroutine and waiting" }
            // inner job is started using different scope
            // exception is printed out because launch here is root-level coroutine
            innerJob = innerScope.launch(genericExceptionHandler()) {
                delay(500)
                throw IllegalStateException("exception from inner scope")
            }
            // waiting for inner job to complete with exception
            innerJob.join()
        }
        // wait for outer job
        job.join()

        // outer job is completed without issues
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo false

        // inner job was cancelled
        innerJob.isCompleted shouldBeEqualTo true
        innerJob.isCancelled shouldBeEqualTo true
    }

    @Test
    fun `child coroutine can be given explicit parent Job to opt-out of structured concurrency`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())

        lateinit var innerJob: Job
        // outer job is started
        val job = scope.launch {
            logger.info { "Launching child coroutine" }
            // this launch is not taking part in structured concurrency because explicit parent job is specified
            innerJob = launch(Job()) {
                delay(500)
                logger.info { "Inner job done" }
            }
        }
        job.join()

        // parent did not wait for inner coroutine
        job.isCompleted shouldBeEqualTo true
        innerJob.isCompleted shouldBeEqualTo false

        innerJob.join()
        innerJob.isCompleted shouldBeEqualTo true

    }

    @Test
    fun `child coroutines inside withContext are cancelling containing coroutine`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())

        val job = scope.launch(genericExceptionHandler()) {
            withContext(Dispatchers.IO) {
                launch {
                    delay(500)
                    throw IllegalStateException("Exception from withContext coroutine")
                }
            }
        }

        job.join()

        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true
    }

    @Test
    fun `exceptions thrown inside coroutines inside withContext can be caught using try-catch`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        lateinit var thrownException: Throwable
        val job = scope.launch(genericExceptionHandler()) {
            try {
                withContext(Dispatchers.IO) {
                    launch {
                        delay(500)
                        throw IllegalStateException("Exception from withContext coroutine")
                    }
                }
            } catch (e: Exception) {
                thrownException = e
            }
        }

        job.join()

        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo false
        thrownException.message shouldBeEqualTo "Exception from withContext coroutine"
    }

    @Test
    fun `exceptions thrown inside withContext body are propagated to containing coroutine`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())

        val job = scope.launch(genericExceptionHandler()) {
            withContext(Dispatchers.IO) {
                async {
                    delay(500)
                    throw IllegalStateException("Exception from withContext coroutine")
                }.await()
            }
        }

        job.join()

        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true
    }

    @Test
    fun `exception inside coroutine started in standalone withContext is propagated to the containing coroutine`(): Unit = runBlocking {
        val scope = CoroutineScope(Job())
        val exceptionRetriever = ExceptionRetriever()

        // outer job is started
        val job = scope.launch(exceptionRetriever.handler()) {
            logger.info { "Launching child coroutine" }
            // job that will be used as a parent job by inner launch
            val parentJob = Job()
            // it is blocking and waits for all child coroutines to complete. Uncaught exception are rethrown
            withContext(parentJob) {
                // this launch is not taking part in structured concurrency because explicit parent job is specified
                launch {
                    delay(500)
                    throw IllegalStateException("failure in nested block")
                }
            }
        }

        // no need to wait for child job
        job.join()

        // outer job is completed without issues because of try-catch
        job.isCompleted shouldBeEqualTo true
        job.isCancelled shouldBeEqualTo true

        exceptionRetriever.caughtException shouldNotBe null

    }
}