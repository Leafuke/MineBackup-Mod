package com.leafuke.minebackup.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.*;

/** Single-threaded virtual time; no sleeping and no background callbacks. */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    private final PriorityQueue<Task<?>> tasks = new PriorityQueue<>();
    private long now = 1_000_000_000L;
    private long sequence;
    private boolean stopped;
    long now() { return now; }
    void advance(Duration duration) {
        long end = now + duration.toNanos();
        while (!tasks.isEmpty() && tasks.peek().due <= end) {
            var task = tasks.remove();
            now = task.due;
            if (!task.isCancelled()) task.run();
        }
        now = end;
    }
    @Override public void shutdown() { stopped = true; }
    @Override public List<Runnable> shutdownNow() {
        stopped = true;
        List<Runnable> pending = new ArrayList<>(tasks);
        tasks.forEach(t -> t.cancel(false)); tasks.clear(); return pending;
    }
    @Override public boolean isShutdown() { return stopped; }
    @Override public boolean isTerminated() { return stopped && tasks.isEmpty(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    @Override public void execute(Runnable task) { schedule(task, 0, TimeUnit.NANOSECONDS); }
    @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return add(Executors.callable(task, null), delay, unit, 0);
    }
    @Override public <V> ScheduledFuture<V> schedule(Callable<V> task, long delay, TimeUnit unit) {
        return add(task, delay, unit, 0);
    }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long delay, long period, TimeUnit unit) {
        return add(Executors.callable(task, null), delay, unit, unit.toNanos(period));
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay, long period, TimeUnit unit) {
        return scheduleAtFixedRate(task, delay, period, unit);
    }
    private <V> Task<V> add(Callable<V> action, long delay, TimeUnit unit, long period) {
        if (stopped) throw new RejectedExecutionException();
        var task = new Task<V>(action, now + unit.toNanos(delay), period, sequence++);
        tasks.add(task); return task;
    }
    private final class Task<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {
        private long due;
        private final long period, order;
        Task(Callable<V> action, long due, long period, long order) {
            super(action); this.due = due; this.period = period; this.order = order;
        }
        @Override public boolean isPeriodic() { return period > 0; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(due - now, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed value) {
            Task<?> other = (Task<?>) value;
            int comparison = Long.compare(due, other.due);
            return comparison != 0 ? comparison : Long.compare(order, other.order);
        }
        @Override public void run() {
            if (period == 0) super.run();
            else if (super.runAndReset() && !isCancelled() && !stopped) { due += period; tasks.add(this); }
        }
    }
}
