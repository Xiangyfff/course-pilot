package com.courseplanner.job;

/**
 * 任务处理器。实现类只关心「这一段业务怎么做」，
 * 状态机、重试、进度落库由 {@link JobService} 统一处理。
 */
public interface JobHandler {

    JobType type();

    void handle(JobContext ctx);
}
