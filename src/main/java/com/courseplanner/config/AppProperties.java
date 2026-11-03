package com.courseplanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Auth auth = new Auth();
    private final Llm llm = new Llm();
    private final Job job = new Job();
    private final Solver solver = new Solver();
    private final Cache cache = new Cache();
    private final Mq mq = new Mq();

    public Auth getAuth() { return auth; }
    public Llm getLlm() { return llm; }
    public Job getJob() { return job; }
    public Solver getSolver() { return solver; }
    public Cache getCache() { return cache; }
    public Mq getMq() { return mq; }

    public static class Auth {
        private String jwtSecret = "dev-only-secret-please-change-in-prod-0123456789";
        private long tokenTtlHours = 72;
        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String v) { this.jwtSecret = v; }
        public long getTokenTtlHours() { return tokenTtlHours; }
        public void setTokenTtlHours(long v) { this.tokenTtlHours = v; }
    }

    public static class Llm {
        /** mock | openai-compatible */
        private String provider = "mock";
        private String baseUrl = "http://127.0.0.1:11434/v1";
        private String apiKey = "";
        private String model = "qwen2.5:7b";
        private int timeoutSeconds = 60;
        /** 每百万 token 的价格（分），用于成本核算展示 */
        private long pricePerMillionPromptCents = 100;
        private long pricePerMillionCompletionCents = 300;
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public long getPricePerMillionPromptCents() { return pricePerMillionPromptCents; }
        public void setPricePerMillionPromptCents(long v) { this.pricePerMillionPromptCents = v; }
        public long getPricePerMillionCompletionCents() { return pricePerMillionCompletionCents; }
        public void setPricePerMillionCompletionCents(long v) { this.pricePerMillionCompletionCents = v; }
    }

    public static class Job {
        private int parsePoolSize = 4;
        private int solvePoolSize = 2;
        private int maxRetry = 3;
        private long timeoutSeconds = 300;
        public int getParsePoolSize() { return parsePoolSize; }
        public void setParsePoolSize(int v) { this.parsePoolSize = v; }
        public int getSolvePoolSize() { return solvePoolSize; }
        public void setSolvePoolSize(int v) { this.solvePoolSize = v; }
        public int getMaxRetry() { return maxRetry; }
        public void setMaxRetry(int v) { this.maxRetry = v; }
        public long getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(long v) { this.timeoutSeconds = v; }
    }

    public static class Solver {
        private int semesters = 8;
        private double maxCreditsPerTerm = 25;
        private int planCount = 3;
        private long timeBudgetMs = 1500;
        public int getSemesters() { return semesters; }
        public void setSemesters(int v) { this.semesters = v; }
        public double getMaxCreditsPerTerm() { return maxCreditsPerTerm; }
        public void setMaxCreditsPerTerm(double v) { this.maxCreditsPerTerm = v; }
        public int getPlanCount() { return planCount; }
        public void setPlanCount(int v) { this.planCount = v; }
        public long getTimeBudgetMs() { return timeBudgetMs; }
        public void setTimeBudgetMs(long v) { this.timeBudgetMs = v; }
    }

    public static class Cache {
        /** caffeine | redis */
        private String mode = "caffeine";
        private long programTtlSeconds = 600;
        private long solveTtlSeconds = 1800;
        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }
        public long getProgramTtlSeconds() { return programTtlSeconds; }
        public void setProgramTtlSeconds(long v) { this.programTtlSeconds = v; }
        public long getSolveTtlSeconds() { return solveTtlSeconds; }
        public void setSolveTtlSeconds(long v) { this.solveTtlSeconds = v; }
    }

    public static class Mq {
        /** local | rocketmq */
        private String mode = "local";
        private String nameServer = "127.0.0.1:9876";
        private String topic = "course-planner-job";
        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }
        public String getNameServer() { return nameServer; }
        public void setNameServer(String v) { this.nameServer = v; }
        public String getTopic() { return topic; }
        public void setTopic(String v) { this.topic = v; }
    }
}
