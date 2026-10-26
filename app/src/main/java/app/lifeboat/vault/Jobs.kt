package app.lifeboat.vault

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList

/** One long-running job at a time (import, backup, export...), run by [WorkService]. */
object Jobs {
    data class State(
        val seq: Int = 0, val running: Boolean = false, val title: String = "",
        val done: Int = 0, val total: Int = 0, val added: Int = 0, val dupes: Int = 0,
        val skipped: Int = 0, val failed: Int = 0, val current: String = "",
        val result: String? = null, val errors: List<String> = emptyList(),
        val pct: Boolean = false
    )

    @Volatile var state = State()
        private set
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private var pending: ((Reporter) -> String)? = null
    @Volatile var cancelRequested = false

    fun listen(l: (State) -> Unit) { listeners += l; l(state) }
    fun unlisten(l: (State) -> Unit) { listeners -= l }

    @Synchronized
    fun start(ctx: Context, title: String, work: (Reporter) -> String): Boolean {
        if (state.running) return false
        pending = work
        cancelRequested = false
        state = State(seq = state.seq + 1, running = true, title = title)
        publish()
        ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java))
        return true
    }

    @Synchronized
    fun takePending(): ((Reporter) -> String)? = pending.also { pending = null }

    fun update(f: (State) -> State) {
        synchronized(this) { state = f(state) }
        publish()
    }

    private fun publish() {
        val s = state
        main.post { listeners.forEach { it(s) } }
    }
}

class Reporter {
    val cancelled: Boolean get() = Jobs.cancelRequested
    fun total(n: Int) = Jobs.update { it.copy(total = n) }
    fun current(name: String) = Jobs.update { it.copy(current = name) }
    fun added() = Jobs.update { it.copy(done = it.done + 1, added = it.added + 1) }
    fun duplicate() = Jobs.update { it.copy(done = it.done + 1, dupes = it.dupes + 1) }
    fun skipped() = Jobs.update { it.copy(done = it.done + 1, skipped = it.skipped + 1) }
    fun step() = Jobs.update { it.copy(done = it.done + 1) }
    fun failed(name: String, e: Throwable) = Jobs.update {
        it.copy(done = it.done + 1, failed = it.failed + 1,
            errors = if (it.errors.size < 50) it.errors + "$name: ${e.message ?: e.javaClass.simpleName}" else it.errors)
    }

    /** Runs one vault add and counts the outcome. */
    fun addOne(name: String, block: () -> Vault.Added) {
        current(name)
        try {
            when (block()) {
                Vault.Added.NEW -> added()
                Vault.Added.DUPLICATE -> duplicate()
            }
        } catch (e: Exception) {
            failed(name, e)
        }
    }
}
