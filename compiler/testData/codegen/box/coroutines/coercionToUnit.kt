class Controller {
    var result = "fail"
    operator fun handleResult(u: Unit, c: Continuation<Nothing>) {
        result = "OK"
    }

    suspend fun <T> await(t: T, c: Continuation<T>) {
        c.resume(t)
    }
}

fun builder(coroutine c: Controller.() -> Continuation<Unit>): String {
    val controller = Controller()
    c(controller).resume(Unit)
    return controller.result
}

fun box() = builder { await(1) }
