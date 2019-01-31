/*
 *  Copyright ® 2018.   All right reserved.
 *
 *  Last modified 18-6-28 下午1:20
 *
 *
 */

package com.arms.mvvm.utils.taskscheduler;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Description:
 * @Author: xuyangyang
 * @Email: xuyangyang@ebrun.com
 * @Version: V4.9.0
 * @Create: 2018/6/28 13:20
 * @Modify:
 */
public class TaskScheduler {
    private volatile static TaskScheduler sTaskScheduler;
    private static final String TAG = "TaskScheduler";

    private Executor mParallelExecutor;
    private ExecutorService mTimeOutExecutor;
    private static final String THREAD_MAIN_MAIN = "main";
    private Handler mMainHandler = new SafeDispatchHandler(Looper.getMainLooper());

    private Map<String, Handler> mHandlerMap = new ConcurrentHashMap<>();

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 60L;

    public static TaskScheduler getInstance() {
        if (sTaskScheduler == null) {
            synchronized (TaskScheduler.class) {
                if (sTaskScheduler == null) {
                    sTaskScheduler = new TaskScheduler();
                }
            }
        }
        return sTaskScheduler;
    }

    private TaskScheduler() {

        /*
          mParallelExecutor  直接使用AsyncTask的线程，减少新线程创建带来的资源消耗
          */
        mParallelExecutor = AsyncTask.THREAD_POOL_EXECUTOR;

        /*
          没有核心线程的线程池要用 SynchronousQueue 而不是LinkedBlockingQueue，SynchronousQueue是一个只有一个任务的队列，
          这样每次就会创建非核心线程执行任务,因为线程池任务放入队列的优先级比创建非核心线程优先级大.
         */
        mTimeOutExecutor = new ThreadPoolExecutor(0, MAXIMUM_POOL_SIZE,
                KEEP_ALIVE, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                TIME_OUT_THREAD_FACTORY);

        mHandlerMap.put(THREAD_MAIN_MAIN, mMainHandler);

    }

    /**
     * 获取回调到handlerName线程的handler.一般用于在一个后台线程执行同一种任务，避免线程安全问题。如数据库，文件操作
     *
     * @param handlerName
     *         线程名
     * @return 异步任务handler
     */
    public static Handler provideHandler(String handlerName) {
        if (getInstance().mHandlerMap.containsKey(handlerName)) {
            return getInstance().mHandlerMap.get(handlerName);
        }

        HandlerThread handlerThread = new HandlerThread(handlerName, android.os.Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        Handler handler = new SafeDispatchHandler(handlerThread.getLooper());
        getInstance().mHandlerMap.put(handlerName, handler);
        return handler;
    }

    /**
     * 主线程周期性执行任务，默认立刻执行，之后间隔period执行，不需要时注意取消,每次执行时如果有相同的任务，默认会先取消
     *
     * @param task
     *         执行的任务
     */
    public static void scheduleUITask(final SchedulerTask task) {
        scheduleTask(task, THREAD_MAIN_MAIN);
    }

    /**
     * 取消周期性任务
     *
     * @param schedulerTask
     *         任务对象
     */
    public static void stopScheduleUITask(final SchedulerTask schedulerTask) {
        stopScheduleTask(schedulerTask, THREAD_MAIN_MAIN);
    }

    /**
     * 指定线程，默认立刻执行，之后间隔period执行
     *
     * @param task
     *         执行的任务
     */
    public static void scheduleTask(final SchedulerTask task, String threadName) {
        stopScheduleTask(task, threadName);
        task.canceled.compareAndSet(true, false);
        final Handler threadHandler = provideHandler(threadName);
        threadHandler.postAtTime(new Runnable() {
            @Override
            public void run() {
                if (!task.canceled.get()) {
                    task.run();
                    threadHandler.postAtTime(this, task, SystemClock.uptimeMillis() + task.periodSecond);
                }
            }
        }, task, SystemClock.uptimeMillis());
    }

    public static void stopScheduleTask(final SchedulerTask task, String threadName) {
        task.canceled.compareAndSet(false, true);
        provideHandler(threadName).removeCallbacksAndMessages(task);
    }


    /**
     * 执行一个后台任务，无回调
     **/
    public static void execute(Runnable task) {
        getInstance().mParallelExecutor.execute(task);
    }


    /**
     * 执行一个后台任务，如果不需回调
     *
     * @see #execute(Runnable)
     **/
    public static <R> void execute(Task<R> task) {
        getInstance().mParallelExecutor.execute(task);
    }

    /**
     * 取消一个任务
     *
     * @param task
     *         被取消的任务
     */
    public static void cancelTask(Task task) {
        if (task != null) {
            task.cancel();
        }
    }


    /**
     * 使用一个单独的线程池来执行超时任务，避免引起他线程不够用导致超时
     *
     * @param timeOutMillis
     *         超时时间，单位毫秒
     *         * 通过实现error(Exception) 判断是否为 TimeoutException 来判断是否超时,
     *         不能100%保证实际的超时时间就是timeOutMillis，但一般没必要那么精确
     */
    public static <R> void executeTimeOutTask(final long timeOutMillis, final Task<R> timeOutTask) {
        final Future future = getInstance().mTimeOutExecutor.submit(timeOutTask);

        getInstance().mTimeOutExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    future.get(timeOutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!timeOutTask.isCanceled()) {
                                timeOutTask.cancel();
                            }
                        }
                    });
                }

            }
        });
    }


    public static void runOnUIThread(@NonNull Runnable runnable) {

        getInstance().mMainHandler.post(runnable);
    }



    public static void removeHandlerCallback(String threadName, Runnable runnable) {
        if (getInstance().mHandlerMap.get(threadName) != null) {
            getInstance().mHandlerMap.get(threadName).removeCallbacks(runnable);
        }
    }

    public static Handler getMainHandler() {
        return getInstance().mMainHandler;
    }

    public static void runOnUIThread(Runnable runnable, long delayed) {
        getInstance().mMainHandler.postDelayed(runnable, delayed);
    }


    public static void removeUICallback(Runnable runnable) {
        removeHandlerCallback(THREAD_MAIN_MAIN, runnable);
    }


    public static boolean isMainThread() {
        return Thread.currentThread() == getInstance().mMainHandler.getLooper().getThread();
    }

    private static final ThreadFactory TIME_OUT_THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread thread = new Thread(r, "YiBang Thread #" + mCount.getAndIncrement());
            thread.setPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            return thread;
        }
    };


}
