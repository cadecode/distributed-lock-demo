package top.cadecode.mysql.mapper;

import org.apache.ibatis.annotations.Mapper;
import top.cadecode.mysql.domain.LockInfo;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 锁信息 DAO
 */
@Mapper
public interface LockInfoMapper {

    LockInfo getLockInfoForUpdate(String name);

    LockInfo getLockInfoForUpdateNoWait(String name);

    LockInfo getLockInfo(String name);

    int addLockInfo(LockInfo lockInfo);

    int deleteLockInfo(String name);

    int updateLockInfo(LockInfo lockInfo);

}
