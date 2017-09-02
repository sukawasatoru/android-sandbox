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
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Handler mMainHandler;
    private ThreadPoolExecutor mExecutor;

    @Override
    public void onCreate() {
        log("MainService#onCreate");

        mHandlerThread = new HandlerThread("MainServiceHandler");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mHandler = new Handler(getMainLooper());
    }

    @Override
    public void onDestroy() {
        log("MainService#onDestroy");

        if (mExecutor != null) {
            mExecutor.shutdown();
            mExecutor = null;
        }

        mMainHandler.removeCallbacksAndMessages(null);
        mMainHandler = null;
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

        mHandler.post(() -> {
            final int thread =
                    intent.getIntExtra("thread", Runtime.getRuntime().availableProcessors());
            mExecutor = prepareExecutor(thread);

            final int loopNum = intent.getIntExtra("loop", 1000000);
            log("MainService#onStartCommand env thread=%s, loop=%s", thread, loopNum);

            final long reentrantTime = time(() -> reentrantLoop(loopNum));
            log("MainService#onStartCommand reentrantLoop time=%s ms = %s s",
                    TimeUnit.NANOSECONDS.toMillis(reentrantTime),
                    TimeUnit.NANOSECONDS.toSeconds(reentrantTime));

            final long synchronizedTime = time(() -> synchronizedLoop(loopNum));
            log("MainService#onStartCommand synchronizedLoop time=%s ms = %s s",
                    TimeUnit.NANOSECONDS.toMillis(synchronizedTime),
                    TimeUnit.NANOSECONDS.toSeconds(synchronizedTime));

            mMainHandler.post(() -> {
                if (mExecutor != null) {
                    mExecutor.shutdown();
                    mExecutor = null;
                }
            });
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
            mExecutor.execute(() -> {
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
            // nothing to do.
        }

        writeLock.lock();
        try {
            calendarWrapper.setCalendar(null);
        } finally {
            writeLock.unlock();
        }
    }

    private void synchronizedLoop(int loopNum) {
        final Object monitor = new Object();
        final CalendarWrapper calendarWrapper = new CalendarWrapper();
        synchronized (monitor) {
            calendarWrapper.setCalendar(Calendar.getInstance());
        }

        final CountDownLatch latch = new CountDownLatch(loopNum);
        for (int i = 0; i < loopNum; i++) {
            mExecutor.execute(() -> {
                synchronized (monitor) {
                    stubDate(calendarWrapper.getCalendar().getTime());
                }

                latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            // nothing to do.
        }

        synchronized (monitor) {
            calendarWrapper.setCalendar(null);
        }
    }

    private static ThreadPoolExecutor prepareExecutor(int threadNum) {
        final ThreadPoolExecutor executor =
                (ThreadPoolExecutor) Executors.newFixedThreadPool(threadNum);
        final CountDownLatch outerLatch = new CountDownLatch(threadNum);
        final CountDownLatch innerLatch = new CountDownLatch(1);
        for (int i = 0; i < threadNum; i++) {
            executor.execute(() -> {
                try {
                    innerLatch.await();
                    outerLatch.countDown();
                } catch (InterruptedException e) {
                    // does nothing.
                }
            });
        }
        innerLatch.countDown();
        try {
            outerLatch.await();
        } catch (InterruptedException e) {
            // does nothing.
        }
        return executor;
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
}
