package jp.tinyport.tinytest;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.OperationCanceledException;
import android.util.Log;

import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MainService extends Service {
    private final Semaphore mSemaphore;

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    public MainService() {
        mSemaphore = new Semaphore(2);
    }

    @Override
    public void onCreate() {
        log("MainService#onCreate");

        mHandlerThread = new HandlerThread("MainServiceHandler");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void onDestroy() {
        log("MainService#onDestroy");

        mHandler.getLooper().getThread().interrupt();
        mHandler.removeCallbacksAndMessages(null);
        mHandler = null;
        mHandlerThread.quitSafely();
        mHandlerThread = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("MainService#onStartCommand intent=%s", intent);

        if (intent == null || "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        mHandler.getLooper().getThread().interrupt();
        mSemaphore.acquireUninterruptibly();
        mHandler.removeCallbacksAndMessages(null);
        mHandler.post(() -> {
            Thread.interrupted();
            final int thread =
                    intent.getIntExtra("thread", Runtime.getRuntime().availableProcessors());
            try (ClosableExecutorService executor = prepareExecutor(thread)) {
                final int loopNum = intent.getIntExtra("loop", 1000000);
                log("MainService#mHandler env ei:thread=%s, ei:loop=%s", thread, loopNum);
                final long reentrantTime = time(() -> reentrantLoop(executor, loopNum));
                log("MainService#mHandler reentrantLoop time=%s ms = %s s",
                        TimeUnit.NANOSECONDS.toMillis(reentrantTime),
                        TimeUnit.NANOSECONDS.toSeconds(reentrantTime));

                final long synchronizedTime = time(() -> synchronizedLoop(executor, loopNum));
                log("MainService#mHandler synchronizedLoop time=%s ms = %s s",
                        TimeUnit.NANOSECONDS.toMillis(synchronizedTime),
                        TimeUnit.NANOSECONDS.toSeconds(synchronizedTime));
            } catch (OperationCanceledException e) {
                log("MainService#mHandler abort %s", e);
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                mSemaphore.release();
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        throw new InternalError();
    }

    private void reentrantLoop(Executor executor, int loopNum) {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        final CalendarWrapper calendarWrapper = new CalendarWrapper();
        writeLock.lock();
        try {
            calendarWrapper.setCalendar(Calendar.getInstance());
        } finally {
            writeLock.unlock();
        }

        final CountDownLatch latch = new CountDownLatch(loopNum);
        final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        for (int i = 0; i < loopNum; i++) {
            executor.execute(() -> {
                readLock.lock();
                try {
                    stubDate(calendarWrapper.getCalendar().getTime());
                } finally {
                    readLock.unlock();
                }

                latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new OperationCanceledException("MainService#reentrantLoop interrupted");
        }

        writeLock.lock();
        try {
            calendarWrapper.setCalendar(null);
        } finally {
            writeLock.unlock();
        }
    }

    private void synchronizedLoop(Executor executor, int loopNum) {
        final Object monitor = new Object();
        final CalendarWrapper calendarWrapper = new CalendarWrapper();
        synchronized (monitor) {
            calendarWrapper.setCalendar(Calendar.getInstance());
        }

        final CountDownLatch latch = new CountDownLatch(loopNum);
        for (int i = 0; i < loopNum; i++) {
            executor.execute(() -> {
                synchronized (monitor) {
                    stubDate(calendarWrapper.getCalendar().getTime());
                }

                latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new OperationCanceledException("MainService#synchronizedLoop interrupted");
        }

        synchronized (monitor) {
            calendarWrapper.setCalendar(null);
        }
    }

    private static ClosableExecutorService prepareExecutor(int threadNum) {
        final ExecutorService executor = Executors.newFixedThreadPool(threadNum);
        final CountDownLatch outerLatch = new CountDownLatch(threadNum);
        final CountDownLatch innerLatch = new CountDownLatch(1);
        try {
            for (int i = 0; i < threadNum; i++) {
                executor.execute(() -> {
                    try {
                        innerLatch.await();
                        outerLatch.countDown();
                    } catch (InterruptedException e) {
                        throw new AssertionError();
                    }
                });
            }
            innerLatch.countDown();
            outerLatch.await();
            return new ClosableExecutorService(executor);
        } catch (InterruptedException e) {
            executor.shutdown();
            throw new OperationCanceledException("MainService#prepareExecutor interrupted");
        }
    }

    private static long time(Runnable runnable) {
        final long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static void stubDate(Date date) {
        // nothing to do.
    }

    private static void log(String message, Object... args) {
        Log.i("TINYTEST", String.format(Locale.getDefault(), message, args));
    }

    private static class CalendarWrapper {
        private Calendar mCalendar;

        CalendarWrapper() {
        }

        Calendar getCalendar() {
            return mCalendar;
        }

        void setCalendar(Calendar calendar) {
            mCalendar = calendar;
        }
    }

    private static class ClosableExecutorService implements ExecutorService, AutoCloseable {
        private final ExecutorService mExecutorService;

        ClosableExecutorService(ExecutorService service) {
            mExecutorService = service;
        }

        @Override
        public void shutdown() {
            mExecutorService.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return mExecutorService.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return mExecutorService.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return mExecutorService.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return mExecutorService.awaitTermination(timeout, unit);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return mExecutorService.submit(task);
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return mExecutorService.submit(task, result);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return mExecutorService.submit(task);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return mExecutorService.invokeAll(tasks);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
                TimeUnit unit) throws InterruptedException {
            return mExecutorService.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return mExecutorService.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return mExecutorService.invokeAny(tasks, timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            mExecutorService.execute(command);
        }

        @Override
        public void close() throws Exception {
            mExecutorService.shutdown();
        }
    }
}
