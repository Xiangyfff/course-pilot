package com.courseplanner.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
@ConditionalOnProperty(name = "app.mq.mode", havingValue = "local", matchIfMissing = true)
public class LocalJobDispatcher implements JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalJobDispatcher.class);

    @Resource(name = "parsePool")
    private ThreadPoolTaskExecutor parsePool;

    @Resource(name = "solvePool")
    private ThreadPoolTaskExecutor solvePool;

    private final ObjectProvider<JobService> jobService;

    public LocalJobDispatcher(ObjectProvider<JobService> jobService) {
        this.jobService = jobService;
    }

    @Override
    public void dispatch(long jobId, JobType type) {
        ThreadPoolTaskExecutor pool = type == JobType.SOLVE ? solvePool : parsePool;
        try {
            pool.execute(() -> {
                try {
                    jobService.getObject().execute(jobId);
                } catch (Exception e) {
                    log.error("job {} dispatch failed", jobId, e);
                }
            });
        } catch (Exception e) {
            // 投递失败不影响任务本身：job 仍是 PENDING，定时补偿会再捞一次
            log.warn("dispatch rejected job {}: {}", jobId, e.getMessage());
        }
    }
}
