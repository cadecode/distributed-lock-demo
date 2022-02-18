package top.cadecode.mysql;

import lombok.*;
import org.springframework.stereotype.Component;
import top.cadecode.common.DistributedLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
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

    private final DataSource dataSource;

    private final ThreadLocal<Map<String, LockContent>> contentMapLocal = ThreadLocal.withInitial(HashMap::new);

    @Override
    public void lock(String name) {
        if (checkReentrant(name)) {
            storeLock(name, null, true);
            return;
        }
        Connection connection = createConnection(name);
        LockDao lockDao = new LockDao(connection);
        // for update 加锁
        getLock(name, lockDao, lockDao::selectForUpdate);
        // 保存锁内容
        storeLock(name, connection, false);
    }

    @Override
    public boolean tryLock(String name) {
        if (checkReentrant(name)) {
            storeLock(name, null, true);
            return true;
        }
        Connection connection = createConnection(name);
        LockDao lockDao = new LockDao(connection);
        try {
            getLock(name, lockDao, lockDao::selectForUpdateNoWait);
        } catch (Exception e) {
            clearConnection(connection);
            return false;
        }
        // 保存锁内容
        storeLock(name, connection, false);
        return true;
    }

    @Override
    public boolean tryLock(String name, long timeout, TimeUnit timeUnit) {
        if (checkReentrant(name)) {
            storeLock(name, null, true);
            return true;
        }
        Connection connection = createConnection(name);
        LockDao lockDao = new LockDao(connection);
        // 尝试的总时间
        long totalTime = timeUnit.toMillis(timeout);
        // 当前时间
        long current = System.currentTimeMillis();
        while (System.currentTimeMillis() - current <= totalTime) {
            try {
                getLock(name, lockDao, lockDao::selectForUpdateNoWait);
            } catch (Exception e) {
                continue;
            }
            // 保存锁内容
            storeLock(name, connection, false);
            return true;
        }
        clearConnection(connection);
        return false;
    }

    @Override
    public void unlock(String name) {
        // 判断是否重入
        if (!checkReentrant(name)) {
            return;
        }
        // 获取锁内容
        LockContent lockContent = contentMapLocal.get().get(name);
        Integer count = lockContent.getCount();
        if (count > 0) {
            // 次数减一
            lockContent.setCount(--count);
        }
        if (count == 0) {
            // 提交事务，关闭 connection
            Connection connection = lockContent.getConnection();
            clearConnection(connection);
            // 删除对应的锁内容
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
            throw new RuntimeException("锁名称不能为空");
        }
        // 判断是否重入
        LockContent lockContent = contentMapLocal.get().get(name);
        return Objects.nonNull(lockContent);
    }

    /**
     * 保存锁内容到 ThreadLocal
     *
     * @param name 锁名称
     */
    private void storeLock(String name, Connection connection, boolean reentrant) {
        LockContent lockContent;
        if (reentrant) {
            lockContent = contentMapLocal.get().get(name);
            // 重入次数加一
            lockContent.setCount(lockContent.getCount() + 1);
            return;
        }
        // 创建新的 LockContent
        lockContent = new LockContent(connection, 1);
        contentMapLocal.get().put(name, lockContent);
    }

    /**
     * 获取 connection
     *
     * @return 数据库连接
     */
    @SneakyThrows
    private Connection createConnection(String name) {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    /**
     * 清除 connection
     */
    @SneakyThrows
    private void clearConnection(Connection connection) {
        if (Objects.nonNull(connection)) {
            connection.commit();
            connection.close();
        }
    }

    /**
     * 查询锁记录，不存在就插入
     *
     * @param name   锁名称
     * @param dao    DAO
     * @param select 查询
     * @return 锁名称
     */
    private String getLock(String name, LockDao dao, Function<String, String> select) {
        String lockName = select.apply(name);
        // for update 查询到数据，加锁成功
        if (Objects.nonNull(lockName)) {
            return lockName;
        }
        try {
            // 插入 name
            dao.insert(name);
        } catch (Exception e) {
            // 捕获插入异常
        } finally {
            // 再次加锁
            lockName = select.apply(name);
        }
        return lockName;
    }

    /**
     * 锁内容
     * 维护 Connection 和重入次数
     */
    @Data
    @AllArgsConstructor
    private static class LockContent {
        /**
         * 数据库连接
         */
        private Connection connection;
        /**
         * 重入次数
         */
        private Integer count;
    }

    /**
     * 锁记录 DAO
     * 增加或查询锁记录，其中 name 为主键
     */
    private static class LockDao {

        final Connection connection;

        LockDao(Connection connection) {
            this.connection = connection;
        }

        @SneakyThrows
        String selectForUpdate(String name) {
            @Cleanup Statement statement = connection.createStatement();
            String sql = "select name from distributed_lock where name = '" + name + "' for update";
            ResultSet resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                return resultSet.getString("name");
            }
            return null;
        }

        @SneakyThrows
        String selectForUpdateNoWait(String name) {
            @Cleanup Statement statement = connection.createStatement();
            String sql = "select name from distributed_lock where name = '" + name + "' for update nowait";
            ResultSet resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                return resultSet.getString("name");
            }
            return null;
        }

        @SneakyThrows
        void insert(String name) {
            @Cleanup Statement statement = connection.createStatement();
            String sql = "insert into distributed_lock (name) values ('" + name + "')";
            statement.executeUpdate(sql);
        }
    }
}
