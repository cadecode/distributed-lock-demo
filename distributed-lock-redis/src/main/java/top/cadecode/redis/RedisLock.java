package top.cadecode.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.cadecode.common.DistributedLock;

import java.util.HashMap;
import java.util.Map;
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

    private final StringRedisTemplate redisTemplate;

    private final ThreadLocal<Map<String, Integer>> countMapLocal = ThreadLocal.withInitial(HashMap::new);

    @Override
    public void lock(String name) {
        if (checkReentrant(name)) {
            storeLock(name, true);
            return;
        }
        while (true) {
            if (tryLock0(name)) {
                return;
            }
            sleep(300, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean tryLock(String name) {
        if (checkReentrant(name)) {
            storeLock(name, true);
            return true;
        }
        return tryLock0(name);
    }

    @Override
    public boolean tryLock(String name, long timeout, TimeUnit timeUnit) {
        if (checkReentrant(name)) {
            storeLock(name, true);
            return true;
        }
        long totalTime = timeUnit.toMillis(timeout);
        long current = System.currentTimeMillis();
        while (System.currentTimeMillis() - current <= totalTime) {
            if (tryLock0(name)) {
                return true;
            }
            // 休息 300 毫秒
            sleep(300, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    @Override
    public void unlock(String name) {
        if (!checkReentrant(name)) {
            return;
        }
        Integer count = countMapLocal.get().get(name);
        // 重入次数减一
        if (count > 0) {
            countMapLocal.get().put(name, --count);
        }
        // 释放锁
        if (count == 0) {
            redisTemplate.delete(name);
            countMapLocal.get().remove(name);
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
            throw new RuntimeException("锁名称不能为空");
        }
        // 判断是否重入
        return Objects.nonNull(countMapLocal.get().get(name));
    }

    /**
     * 保存重入次数到 ThreadLocal
     *
     * @param name 锁名称
     */
    private void storeLock(String name, boolean reentrant) {
        if (reentrant) {
            // 重入次数加一
            Integer count = countMapLocal.get().get(name);
            countMapLocal.get().put(name, count + 1);
            return;
        }
        // 初始化为 1
        countMapLocal.get().put(name, 1);
    }

    /**
     * 尝试设置 redis key
     *
     * @param name 锁名称
     * @return 是否设置成功
     */
    private boolean tryLock0(String name) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(name, "");
        // 设置成功
        if (Objects.equals(success, true)) {
            storeLock(name, false);
            return true;
        }
        return false;
    }

    /**
     * 休眠一定时间
     *
     * @param timeout  超时时间
     * @param timeUnit 时间单位
     */
    private void sleep(long timeout, TimeUnit timeUnit) {
        try {
            timeUnit.sleep(timeout);
        } catch (InterruptedException e) {
            // 不响应中断
        }
    }
}
