package top.cadecode.common;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 分布式锁接口
 */
public interface DistributedLock {

    void lock(String name);

    boolean tryLock(String name);

    void unlock();
}
