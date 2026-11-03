package com.courseplanner.job;

/**
 * 处理一次任务时可用的上下文：业务 id、进度上报。
 * 进度写库而不是写内存，是因为 SSE 连接可能落在另一个线程/另一次请求里。
 */
public final class JobContext {

    private final JobRepository.JobRow job;
    private final JobRepository repo;
    /** 认领时拿到的 version，写进度/状态时作为围栏令牌，防止上一轮的执行者回写。 */
    private final long fencing;

    public JobContext(JobRepository.JobRow job, JobRepository repo, long fencing) {
        this.job = job;
        this.repo = repo;
        this.fencing = fencing;
    }

    public long jobId() {
        return job.id();
    }

    public long userId() {
        return job.userId();
    }

    public Long bizId() {
        return job.bizId();
    }

    public JobType type() {
        return JobType.valueOf(job.type());
    }

    public void progress(int percent, String stage) {
        progress(percent, stage, null);
    }

    public void progress(int percent, String stage, String message) {
        // 返回 0 表示任务已被 recoverStuck 重排、由新的执行者接手：这次进度不再可信，丢掉即可
        repo.progress(job.id(), fencing, percent, stage, message);
    }
}
