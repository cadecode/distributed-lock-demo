package top.cadecode.mysql.domain;

import lombok.Data;

import java.util.Date;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 锁信息
 */
@Data
public class LockInfo {
    private Long id;
    private String name;
    private String ip;
    private Long threadId;
    private Long count;
    private Date createTime;
    private Date updateTime;
}
