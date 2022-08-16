/*
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 *
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qualcomm.qti.server.qtiwifi;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.function.Supplier;

/**
 * Runs code on Wifi thread, from incoming AIDL call from a binder thread,
 * in order to prevent race conditions.
 */
public class QtiWifiThreadRunner {
    private static final String TAG = "QtiWifiThreadRunner";

    /** Max wait time for posting blocking runnables */
    private static final int RUN_WITH_SCISSORS_TIMEOUT_MILLIS = 4000;
    private final Handler handler;

    public QtiWifiThreadRunner(Handler h) {
        handler = h;
    }
    public static final class Mutable<E> {
        public E value;
        public Mutable() {
            value = null;
        }
        public Mutable(E value) {
            this.value = value;
        }
    }
    /**
     * Runs a Runnable on the Wifi thread and <b>blocks</b> the calling thread until the
     * Runnable completes execution on the Wifi thread.
     *
     * BEWARE OF DEADLOCKS!!!
     *
     * @return true if the runnable executed successfully, false otherwise
     */
    public boolean run(@NonNull Runnable r) {
        if (r == null) {
            throw new IllegalArgumentException("runnable must not be null");
        }

        if (Looper.myLooper() == handler.getLooper()) {
            r.run();
            return true;
        }

        BlockingRunnable br = new BlockingRunnable(r);
        if (br.postAndWait(handler, RUN_WITH_SCISSORS_TIMEOUT_MILLIS)) {
            return true;
        } else {
            Throwable throwable = new Throwable("Qti Wifi Stack:");
            throwable.setStackTrace(handler.getLooper().getThread().getStackTrace());
            Log.e(TAG, "QtiWifiThreadRunner.run() timed out!", throwable);
        }

        return false;
    }

    private boolean runWithScissors(@NonNull Handler handler, @NonNull Runnable r,
            long timeout) {
        if (r == null) {
            throw new IllegalArgumentException("runnable must not be null");
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        if (Looper.myLooper() == handler.getLooper()) {
            r.run();
            return true;
        }
        BlockingRunnable br = new BlockingRunnable(r);
        return br.postAndWait(handler, timeout);
    }

    public <T> T call(@NonNull Supplier<T> supplier, T valueToReturnOnTimeout) {
        Mutable<T> result = new Mutable<>();
        boolean runWithScissorsSuccess = runWithScissors(handler,
                () -> result.value = supplier.get(),
                RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (runWithScissorsSuccess) {
            return result.value;
        } else {
            Throwable callerThreadThrowable = new Throwable("Caller thread Stack trace:");
            Throwable qtiwifiThreadThrowable = new Throwable("Qtiwifi thread Stack trace:");
            qtiwifiThreadThrowable.setStackTrace(handler.getLooper().getThread().getStackTrace());
            Log.e(TAG, "QtiWifiThreadRunner.call() timed out!", callerThreadThrowable);
            Log.e(TAG, "QtiWifiThreadRunner.call() timed out!", qtiwifiThreadThrowable);
            throw new RuntimeException("QtiWifiThreadRunner.call() timed out!");
        }
    }

    private static final class BlockingRunnable implements Runnable {
        private final Runnable task;
        private boolean done;

        BlockingRunnable(Runnable t) {
            task = t;
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                synchronized (this) {
                    done = true;
                    notifyAll();
                }
            }
        }

        public boolean postAndWait(Handler handler, long timeout) {
            if (!handler.post(this)) {
                return false;
            }

            synchronized (this) {
                if (timeout > 0) {
                    final long expirationTime = SystemClock.uptimeMillis() + timeout;
                    while (!done) {
                        long delay = expirationTime - SystemClock.uptimeMillis();
                        if (delay <= 0) {
                            return false; // timeout
                        }
                        try {
                            wait(delay);
                        } catch (InterruptedException ex) {
                        }
                    }
                } else {
                    while (!done) {
                        try {
                            wait();
                        } catch (InterruptedException ex) {
                        }
                    }
                }
            }
            return true;
        }
    }
}
