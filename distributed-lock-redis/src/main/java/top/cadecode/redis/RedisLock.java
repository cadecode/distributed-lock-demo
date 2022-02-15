package top.cadecode.redis;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import top.cadecode.common.DistributedLock;

import java.lang.management.LockInfo;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author Cade Li
 * @date 2022/2/15
 * @description Redis 版分布式锁
 */
@Component
@RequiredArgsConstructor
public class RedisLock implements DistributedLock {
    // 存储锁名称
    private final ThreadLocal<String> lockNameLocal = new ThreadLocal<>();

    private final RedisTemplate<String, Object> redisTemplateForLock;

    @Override
    public void lock(String name) {
        while (true) {
            if (tryLock(name)) {
                return;
            }
            // 休息 300 毫秒
            try {
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                // 不响应中断
            }
        }
    }

    @Override
    public boolean tryLock(String name) {
        if (name == null) {
            throw new RuntimeException("锁名称不能为空");
        }
        // 判断不存在 lock，说明不是重入
        if (lockNameLocal.get() == null) {
            RedisLockInfo lockInfo = create();
            Boolean success = redisTemplateForLock.opsForValue().setIfAbsent(name, lockInfo);
            if (Objects.equals(success, true)) {
                // 设置重入的标志
                lockNameLocal.set(name);
                return true;
            }
            return false;
        }
        RedisLockInfo oldLockInfo = (RedisLockInfo) redisTemplateForLock.opsForValue().get(lockNameLocal.get());
        update(oldLockInfo);
        return true;
    }

    @Override
    public boolean tryLock(String name, long timeout, TimeUnit timeUnit) {
        // 尝试的总时间
        long totalTime = timeUnit.toMillis(timeout);
        // 当前时间
        long current = System.currentTimeMillis();
        while (System.currentTimeMillis() - current <= totalTime) {
            if (tryLock(name)) {
                return true;
            }
            // 休息 300 毫秒
            try {
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                // 不响应中断
            }
        }
        return false;
    }

    @Override
    public void unlock() {
        String lockName = lockNameLocal.get();
        if (lockName == null) {
            return;
        }
        RedisLockInfo oldLockInfo = (RedisLockInfo) redisTemplateForLock.opsForValue().get(lockName);
        RedisLockInfo newLockInfo = create();
        // 判断是否重入
        if (!compare(oldLockInfo, newLockInfo)) {
            return;
        }
        // 判断重入次数
        if (oldLockInfo.getCount() > 1) {
            oldLockInfo.setCount(oldLockInfo.getCount() - 1);
            redisTemplateForLock.opsForValue().set(lockName, oldLockInfo);
            return;
        }
        redisTemplateForLock.delete(lockName);
        lockNameLocal.remove();
    }

    /**
     * 获取锁后，更新锁信息
     * 如果是重入锁，就对 count + 1
     *
     * @param oldLockInfo 旧的锁信息
     */
    private void update(RedisLockInfo oldLockInfo) {
        // 创建新的 LockInfo
        RedisLockInfo newLockInfo = create();
        // 判断是否是重入
        if (compare(oldLockInfo, newLockInfo)) {
            // 重入次数 +1
            newLockInfo.setCount(oldLockInfo.getCount() + 1);
        }
        redisTemplateForLock.opsForValue().set(lockNameLocal.get(), newLockInfo);
    }

    /**
     * 比较锁信息，判断是否是重入
     *
     * @param oldLockInfo 旧的锁信息
     * @param newLockInfo 新的锁信息
     * @return 是否是相同的锁
     */
    private boolean compare(RedisLockInfo oldLockInfo, RedisLockInfo newLockInfo) {
        // 比 ip、线程号
        return Objects.equals(oldLockInfo.getIp(), newLockInfo.getIp())
                && Objects.equals(oldLockInfo.getThreadId(), newLockInfo.getThreadId());
    }

    /**
     * 创建一个锁信息对象
     * count 初始化为 0
     *
     * @return 锁信息对象
     */
    @SneakyThrows
    private RedisLockInfo create() {
        RedisLockInfo lockInfo = new RedisLockInfo();
        lockInfo.setIp(InetAddress.getLocalHost().getHostAddress());
        lockInfo.setThreadId(Thread.currentThread().getId());
        // 重入次数默认初始化为 1 次
        lockInfo.setCount(1L);
        return lockInfo;
    }

    /**
     * Redis 锁信息
     */
    @Data
    static class RedisLockInfo {
        private String ip;
        private Long threadId;
        private Long count;
    }
}
