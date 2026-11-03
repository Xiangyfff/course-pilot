package com.courseplanner.job;

public enum JobStatus {
    /** 已入库，等待投递 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 成功，终态 */
    SUCCEEDED,
    /** 失败且不再重试，终态 */
    FAILED,
    /** 超过最大重试，进入死信，终态 */
    DEAD;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == DEAD;
    }
}
