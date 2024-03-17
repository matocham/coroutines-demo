# Coroutines exploration

In most of the cases `runBlocking` is used as a bridge between synchronous and suspending world. In that cases it is used as test body and separate scope is created for testing purposes. In the cases where `runBlocking` is actually needed it is used inside of test body

In most of the examples `launch` is used to test coroutines because `async` requires `await` on the deferred result to be called and exception to be handled
