package top.cadecode.common;

import java.util.concurrent.TimeUnit;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 分布式锁接口
 */
public interface DistributedLock {

    void lock(String name);

    boolean tryLock(String name);

    boolean tryLock(String name, long timeout, TimeUnit timeUnit);

    void unlock();
}
