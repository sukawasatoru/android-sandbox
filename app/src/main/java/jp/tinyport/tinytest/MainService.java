package jp.tinyport.tinytest;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MainService extends Service {
    private static final Object MONITOR;
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static CalendarWrapper sCalendarWrapper;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private ThreadPoolExecutor mExecutor;

    static {
        MONITOR = new Object();
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

        if (mExecutor != null) {
            mExecutor.shutdown();
            mExecutor = null;
        }

        mHandler = null;
        mHandlerThread.quitSafely();
        mHandlerThread = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("MainService#onStartCommand intent=%s", intent);

        if (intent == null) {
            log("MainService#onStartCommand intent is null");
            stopSelf();

            return START_NOT_STICKY;
        }

        mHandler.post(() -> {
            mExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                    intent.getIntExtra("thread", Runtime.getRuntime().availableProcessors()));
            final int loopNum = intent.getIntExtra("loop", 1000000);

            final long reentrantLoopStart = System.nanoTime();
            reentrantLoop(loopNum);
            final long reentrantLoopEnd = System.nanoTime();
            log("MainService#onStartCommand synchronizedLoop time=%s ms",
                    TimeUnit.NANOSECONDS.toMillis(reentrantLoopEnd - reentrantLoopStart));

            final long synchronizedLoopStart = System.nanoTime();
            synchronizedLoop(loopNum);
            final long synchronizedLoopEnd = System.nanoTime();
            log("MainService#onStartCommand synchronizedLoop time=%s ms",
                    TimeUnit.NANOSECONDS.toMillis(synchronizedLoopEnd - synchronizedLoopStart));

            stopSelf();
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

    private void reentrantLoop(int loopNum) {
        final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            sCalendarWrapper = new CalendarWrapper();
        } finally {
            writeLock.unlock();
        }

        final CountDownLatch latch = new CountDownLatch(loopNum);
        final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        for (int i = 0; i < loopNum; i++) {
            mExecutor.execute(() -> {
                readLock.lock();
                try {
                    stubDate(sCalendarWrapper.getCalendar().getTime());
                } finally {
                    readLock.unlock();
                }

                latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            // nothing to do.
        }

        writeLock.lock();
        try {
            sCalendarWrapper = null;
        } finally {
            writeLock.unlock();
        }
    }

    private void synchronizedLoop(int loopNum) {
        synchronized (MONITOR) {
            sCalendarWrapper = new CalendarWrapper();
        }

        final CountDownLatch latch = new CountDownLatch(loopNum);
        for (int i = 0; i < loopNum; i++) {
            mExecutor.execute(() -> {
                synchronized (MONITOR) {
                    stubDate(sCalendarWrapper.getCalendar().getTime());
                }

                latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            // nothing to do.
        }

        synchronized (MONITOR) {
            sCalendarWrapper = null;
        }
    }

    private static void stubDate(Date date) {
        // nothing to do.
    }

    private static void log(String message, Object... args) {
        Log.i("TINYTEST", String.format(Locale.getDefault(), message, args));
    }

    private static class CalendarWrapper {
        CalendarWrapper() {
        }

        Calendar getCalendar() {
            return Calendar.getInstance();
        }
    }
}
