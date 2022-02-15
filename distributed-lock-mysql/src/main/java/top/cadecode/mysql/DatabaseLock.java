package top.cadecode.mysql;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import top.cadecode.common.DistributedLock;
import top.cadecode.mysql.domain.LockInfo;
import top.cadecode.mysql.mapper.LockInfoMapper;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 数据库 ForUpdate 实现分布式锁
 */
@Component
@RequiredArgsConstructor
public class DatabaseLock implements DistributedLock {

    // 存储事务状态，用于提交释放锁
    private final ThreadLocal<TransactionStatus> statusLocal = new ThreadLocal<>();
    // 存储锁名称
    private final ThreadLocal<String> lockNameLocal = new ThreadLocal<>();
    // 定义事务规则，每次申请锁开启新事务
    private final TransactionDefinition definition = new TransactionDefinition() {
        @Override
        public int getPropagationBehavior() {
            return PROPAGATION_REQUIRES_NEW;
        }
    };

    private final LockInfoMapper lockInfoMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * 阻塞的获取锁
     * 利用 for update 获取不到锁就阻塞等待特性
     * 当锁在表中不存在时，先插入，再次 for update
     *
     * @param name 锁唯一名称
     */
    @Override
    public void lock(String name) {
        startTransaction(name);
        LockInfo oldLockInfo = getOrAdd(name, lockInfoMapper::getLockInfoForUpdate);
        // 获得锁后，修改 lock 信息
        update(oldLockInfo);
    }

    /**
     * 非阻塞的获取锁
     * 利用 for update nowait 获取不到锁就报错的特性
     *
     * @param name 锁唯一名称
     * @return 是否获取锁
     */
    @Override
    public boolean tryLock(String name) {
        startTransaction(name);
        LockInfo oldLockInfo = null;
        try {
            oldLockInfo = getOrAdd(name, lockInfoMapper::getLockInfoForUpdateNoWait);
        } catch (Exception e) {
            // 进入此处表示 for update nowait 失败，清除事务
            clearTransaction();
            return false;
        }
        // 获取锁后，修改 lock 信息
        update(oldLockInfo);
        return true;
    }


    /**
     * 非阻塞的获取锁，在指定时间内反复重试
     *
     * @param name     锁名称
     * @param timeout  尝试时间
     * @param timeUnit 时间单位
     * @return 是否获取锁
     */
    @Override
    public boolean tryLock(String name, long timeout, TimeUnit timeUnit) {
        startTransaction(name);
        // 尝试的总时间
        long totalTime = timeUnit.toMillis(timeout);
        // 当前时间
        long current = System.currentTimeMillis();
        // 锁信息
        LockInfo oldLockInfo = null;
        while (System.currentTimeMillis() - current <= totalTime) {
            try {
                oldLockInfo = getOrAdd(name, lockInfoMapper::getLockInfoForUpdateNoWait);
                // 休息 300 毫秒
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (Exception e) {
                // 进入此处表示 for update nowait 失败，继续循环
                continue;
            }
            // 执行到次数，表示获取锁，修改 lock 信息
            update(oldLockInfo);
            return true;
        }
        clearTransaction();
        return false;
    }

    /**
     * 解锁
     * 如果查询到的锁记录不是当前机器线程，直接 return 避免释放了其他线程的锁
     * 如果重入次数 > 1，就 -1，如果 = 1，就设置为 0，就提交事务释放锁
     */
    @Override
    public void unlock() {
        if (statusLocal.get() == null) {
            return;
        }
        // 比较重入次数
        LockInfo oldLockInfo = lockInfoMapper.getLockInfo(lockNameLocal.get());
        LockInfo newLockInfo = create(lockNameLocal.get());
        // 判断是否重入
        if (!compare(oldLockInfo, newLockInfo)) {
            return;
        }
        if (oldLockInfo.getCount() == 0) {
            return;
        }
        // 重入次数 -1
        if (oldLockInfo.getCount() > 1) {
            newLockInfo.setCount(oldLockInfo.getCount() - 1);
            lockInfoMapper.updateLockInfo(newLockInfo);
            return;
        }
        newLockInfo.setCount(0L);
        lockInfoMapper.updateLockInfo(newLockInfo);
        clearTransaction();
    }

    /**
     * 开启事务
     * 如果事务存在就不创建
     *
     * @param name 锁名称
     */
    private void startTransaction(String name) {
        if (name == null) {
            throw new RuntimeException("锁名称不能为空");
        }
        if (statusLocal.get() == null) {
            // 开启新事务
            TransactionStatus status = transactionManager.getTransaction(definition);
            // 存储事务状态
            statusLocal.set(status);
            // 存储锁名称
            lockNameLocal.set(name);
        }
    }

    /**
     * 清除事务
     */
    private void clearTransaction() {
        transactionManager.commit(statusLocal.get());
        statusLocal.remove();
        lockNameLocal.remove();
    }

    /**
     * 查询锁信息，不存在就插入
     * fn 可以是 for update / for update nowait
     *
     * @param name 锁名称
     * @param fn   查询锁信息
     * @return 锁信息
     */
    private LockInfo getOrAdd(String name, Function<String, LockInfo> fn) {
        // 查询 lock，此处可能阻塞或直接失败报错
        LockInfo oldLockInfo = fn.apply(name);
        // 判断 lock 为空，则插入 lock 信息
        if (oldLockInfo == null) {
            try {
                lockInfoMapper.addLockInfo(create(name));
            } catch (Exception e) {
                // 插入报错再此处捕获
            } finally {
                // 再次查询，此时锁信息存在
                // 若没有抢到执行机会，for update 会在此处阻塞等待
                // 如果加了 nowait, 就直接报错
                oldLockInfo = fn.apply(name);
            }
        }
        return oldLockInfo;
    }

    /**
     * 获取锁后，更新锁信息
     * 如果是重入锁，就对 count + 1
     *
     * @param oldLockInfo 旧的锁信息
     */
    private void update(LockInfo oldLockInfo) {
        // 创建新的 LockInfo
        LockInfo newLockInfo = create(oldLockInfo.getName());
        // 判断是否是重入
        if (compare(oldLockInfo, newLockInfo)) {
            // 重入次数 +1
            newLockInfo.setCount(oldLockInfo.getCount() + 1);
        } else {
            newLockInfo.setCount(1L);
        }
        lockInfoMapper.updateLockInfo(newLockInfo);
    }

    /**
     * 比较锁信息，判断是否是重入
     *
     * @param oldLockInfo 旧的锁信息
     * @param newLockInfo 新的锁信息
     * @return 是否是相同的锁
     */
    private boolean compare(LockInfo oldLockInfo, LockInfo newLockInfo) {
        // 比较锁名称、ip、线程号
        return Objects.equals(oldLockInfo.getName(), newLockInfo.getName())
                && Objects.equals(oldLockInfo.getIp(), newLockInfo.getIp())
                && Objects.equals(oldLockInfo.getThreadId(), newLockInfo.getThreadId());
    }

    /**
     * 创建一个锁信息对象
     * count 初始化为 0
     *
     * @param name 锁名称
     * @return 锁信息对象
     */
    @SneakyThrows
    private LockInfo create(String name) {
        LockInfo lockInfo = new LockInfo();
        lockInfo.setName(name);
        lockInfo.setIp(InetAddress.getLocalHost().getHostAddress());
        lockInfo.setThreadId(Thread.currentThread().getId());
        // 重入次数默认初始化为 0 次
        lockInfo.setCount(0L);
        return lockInfo;
    }
}
