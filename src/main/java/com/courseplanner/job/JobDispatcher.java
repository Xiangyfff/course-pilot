package com.courseplanner.job;

/**
 * 任务投递通道。local 用进程内线程池，rocketmq 发到 broker；
 * 两者都只负责「把 jobId 送到执行侧」，执行侧靠 {@code claim} 保证只执行一次。
 */
public interface JobDispatcher {

    void dispatch(long jobId, JobType type);
}
