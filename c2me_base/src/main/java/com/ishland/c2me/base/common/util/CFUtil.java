package com.ishland.c2me.base.common.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;

public class CFUtil {

    public static <T> T join(CompletableFuture<T> future) {

        int spinCount = 0;
        while (!future.isDone()) {
            if (spinCount < 10000) {

                Thread.onSpinWait();
                spinCount++;
            } else {

                LockSupport.parkNanos("Waiting for future", 1L);
                spinCount = 0;
            }
        }
        return future.join();
    }

}
