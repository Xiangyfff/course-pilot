package com.courseplanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池隔离：解析是 IO 密集（等 LLM），求解是 CPU 密集。
 * 两类任务共用一个池子会出现「几个求解把解析全饿死」。
 */
@Configuration
public class AsyncConfig {

    @Bean("parsePool")
    public ThreadPoolTaskExecutor parsePool(AppProperties props) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(props.getJob().getParsePoolSize());
        e.setMaxPoolSize(props.getJob().getParsePoolSize() * 2);
        e.setQueueCapacity(100);
        e.setThreadNamePrefix("parse-");
        // 满了由调用线程执行：宁可拖慢提交方，也不要静默丢任务
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(20);
        return e;
    }

    @Bean("solvePool")
    public ThreadPoolTaskExecutor solvePool(AppProperties props) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(props.getJob().getSolvePoolSize());
        e.setMaxPoolSize(props.getJob().getSolvePoolSize());
        e.setQueueCapacity(50);
        e.setThreadNamePrefix("solve-");
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(20);
        return e;
    }
}
