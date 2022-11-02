package top.cadecode.learn.distributedlock.redis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.cadecode.learn.distributedlock.common.DistributedLock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Cade Li
 * @date 2022/2/15
 * @description Redis 版分布式锁
 */
@Component
@RequiredArgsConstructor
public class RedisLock implements DistributedLock {

    private final StringRedisTemplate redisTemplate;

    private final ThreadLocal<Map<String, LockContent>> contentMapLocal = ThreadLocal.withInitial(HashMap::new);

    // 定时续期任务线程池，合理设置大小
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(10);

    @Override
    public void lock(String name) {
        lock(name, "");
    }

    public void lock(String name, String value) {
        if (checkReentrant(name)) {
            storeLock(name, null, true);
            return;
        }
        while (true) {
            if (tryLock0(name, value)) {
                return;
            }
            sleep();
        }
    }

    @Override
    public boolean tryLock(String name) {
        return tryLock(name, "");
    }

    public boolean tryLock(String name, String value) {
        if (checkReentrant(name)) {
            storeLock(name, null, true);
            return true;
        }
        return tryLock0(name, value);
    }

    @Override
    public boolean tryLock(String name, long timeout, TimeUnit timeUnit) {
        return tryLock(name, "", timeout, timeUnit);
    }

    public boolean tryLock(String name, String value, long timeout, TimeUnit timeUnit) {
        if (checkReentrant(name)) {
            storeLock(name, null, true);
            return true;
        }
        long totalTime = timeUnit.toMillis(timeout);
        long current = System.currentTimeMillis();
        while (System.currentTimeMillis() - current <= totalTime) {
            if (tryLock0(name, value)) {
                return true;
            }
            sleep();
        }
        return false;
    }

    @Override
    public void unlock(String name) {
        if (!checkReentrant(name)) {
            return;
        }
        LockContent lockContent = contentMapLocal.get().get(name);
        Integer count = lockContent.getCount();
        if (count > 0) {
            // 重入次数减一
            lockContent.setCount(--count);
        }
        // 释放锁
        if (count == 0) {
            // 停止续期任务
            lockContent.getFuture().cancel(true);
            // 删除 Redis key
            redisTemplate.delete(name);
            // 清除重入记录
            contentMapLocal.get().remove(name);
        }
    }

    /**
     * 检查重入
     *
     * @param name 锁名称
     * @return 是否重入
     */
    private boolean checkReentrant(String name) {
        if (Objects.isNull(name)) {
            throw new RuntimeException("lock name cannot be null");
        }
        // 判断是否重入
        return Objects.nonNull(contentMapLocal.get().get(name));
    }

    /**
     * 保存重入次数到 ThreadLocal
     *
     * @param name 锁名称
     */
    private void storeLock(String name, ScheduledFuture<?> future, boolean reentrant) {
        LockContent lockContent;
        if (reentrant) {
            lockContent = contentMapLocal.get().get(name);
            // 重入次数加一
            lockContent.setCount(lockContent.getCount() + 1);
            return;
        }
        // 创建新的 LockContent
        lockContent = new LockContent(future, 1);
        contentMapLocal.get().put(name, lockContent);
    }

    /**
     * 尝试设置 redis key
     *
     * @param name 锁名称
     * @return 是否设置成功
     */
    private boolean tryLock0(String name, String value) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(name, value, 30, TimeUnit.SECONDS);
        if (Objects.equals(success, false)) {
            return false;
        }
        // 设置成功 开启续期任务
        ScheduledFuture<?> future = renewLock(name, value, contentMapLocal.get());
        storeLock(name, future, false);
        return true;
    }

    /**
     * 开启锁续期任务
     *
     * @param name 锁名称
     * @return ScheduledFuture
     */
    private ScheduledFuture<?> renewLock(String name, String value, Map<String, LockContent> contentMap) {
        // 有效期设置为 30s，每 10 秒重置
        return executor.scheduleAtFixedRate(() -> {
            Boolean success = redisTemplate.opsForValue().setIfPresent(name, value, 30, TimeUnit.SECONDS);
            if (Objects.equals(success, true)) {
                return;
            }
            // 删除锁
            contentMap.remove(name);
            // 跑出异常停止任务
            throw new RuntimeException("renew lock fail, key is " + name);
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 休眠一定时间
     */
    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            // 不响应中断
        }
    }

    /**
     * 锁内容
     * 维护续期任务和重入次数
     */
    @Data
    @AllArgsConstructor
    private static class LockContent {
        /**
         * 续期任务
         */
        private ScheduledFuture<?> future;
        /**
         * 重入次数
         */
        private Integer count;
    }
}
