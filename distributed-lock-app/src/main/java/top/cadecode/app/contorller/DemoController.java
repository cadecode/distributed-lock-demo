package top.cadecode.app.contorller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.cadecode.mysql.DatabaseLock;

import java.util.concurrent.TimeUnit;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 测试 API
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DemoController {

    private final DatabaseLock databaseLock;

    @GetMapping("database_lock")
    public String databaseLock() throws InterruptedException {
        databaseLock.unlock();

        log.info("===开始加锁");
        databaseLock.lock("lock0001");
        databaseLock.lock("lock0001");
        try {
            log.info("===开始业务");
            Thread.sleep(5000);
            log.info("===业务结束，释放锁");
        } finally {
            databaseLock.unlock();
            Thread.sleep(2000);
            databaseLock.unlock();
        }
        return "返回数据";
    }

    @GetMapping("database_try_lock")
    public String databaseTryLock() throws InterruptedException {
        log.info("===开始加锁");
        boolean lock0001 = databaseLock.tryLock("lock0001", 3, TimeUnit.SECONDS);
        if (lock0001) {
            try {
                log.info("===开始业务");
                Thread.sleep(5000);
                log.info("===业务结束，释放锁");
            } finally {
                databaseLock.unlock();
            }
            return "返回数据";
        } else {
            log.info("===加锁失败");
        }
        return "加锁失败";
    }
}