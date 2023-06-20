package com.github.cadecode.learn.distributedlock.common;

import java.util.concurrent.TimeUnit;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 分布式锁接口
 */
public interface DistributedLock {

    /**
     * 阻塞加锁
     *
     * @param name 锁名称
     */
    void lock(String name);

    /**
     * 非阻塞加锁
     *
     * @param name 锁名称
     * @return 是否成功
     */
    boolean tryLock(String name);

    /**
     * 非阻塞加锁（支持超时）
     *
     * @param name     锁名称
     * @param timeout  超时时间
     * @param timeUnit 时间单位
     * @return 是否成功
     */
    boolean tryLock(String name, long timeout, TimeUnit timeUnit);

    /**
     * 释放锁
     *
     * @param name 锁名称
     */
    void unlock(String name);

}
