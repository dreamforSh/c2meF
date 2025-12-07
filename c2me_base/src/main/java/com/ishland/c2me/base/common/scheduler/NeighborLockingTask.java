package com.ishland.c2me.base.common.scheduler;

import com.google.common.base.Preconditions;
import com.ishland.c2me.base.common.GlobalExecutors;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class NeighborLockingTask<T> implements ScheduledTask {

    private final SchedulingManager schedulingManager;
    private final long center;
    private final long[] names;
    private final BooleanSupplier isCancelled;
    private final Supplier<CompletableFuture<T>> action;
    private final String desc;
    private final boolean async;
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private boolean acquired = false;

    public NeighborLockingTask(SchedulingManager schedulingManager, long center, long[] names, BooleanSupplier isCancelled, Supplier<CompletableFuture<T>> action, String desc, boolean async) {
        this.schedulingManager = schedulingManager;
        this.center = center;
        this.names = names;
        this.isCancelled = isCancelled;
        this.action = action;
        this.desc = desc;
        this.async = async;

        this.schedulingManager.enqueue(this);
    }


    @Override
    public boolean tryPrepare() {
        final NeighborLockingManager lockingManager = this.schedulingManager.getNeighborLockingManager();
        final long[] names = this.names;
        final int len = names.length;
        
        // C2ME - Optimized: Check all locks first before acquiring any
        for (int i = 0; i < len; i++) {
            final long l = names[i];
            if (lockingManager.isLocked(l)) {
                lockingManager.addReleaseListener(l, () -> this.schedulingManager.enqueue(this));
                return false;
            }
        }
        
        // All locks available, acquire them
        for (int i = 0; i < len; i++) {
            lockingManager.acquireLock(names[i]);
        }
        acquired = true;
        return true;
    }

    @Override
    public void runTask(Runnable postAction) {
        Preconditions.checkNotNull(postAction);
        if (!acquired) throw new IllegalStateException();
        final CompletableFuture<T> future = this.action.get();
        Preconditions.checkNotNull(future, "future");
        future.handleAsync((result, throwable) -> {
            this.schedulingManager.getExecutor().execute(() -> {
                final NeighborLockingManager lockingManager = this.schedulingManager.getNeighborLockingManager();
                for (long l : names) {
                    lockingManager.releaseLock(l);
                }
            });
            try {
                postAction.run();
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger(NeighborLockingTask.class).error("Failed to execute post action", t);
            }
            if (throwable != null) this.future.completeExceptionally(throwable);
            else this.future.complete(result);
            return null;
        }, GlobalExecutors.invokingExecutor);
    }

    @Override
    public long centerPos() {
        return center;
    }

    @Override
    public boolean isAsync() {
        return async;
    }

    public CompletableFuture<T> getFuture() {
        return future;
    }
}
