package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.AgentRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent运行Mapper接口
 * <p>
 * 提供Agent运行实体的数据库访问操作，继承MyBatis-Plus的BaseMapper，
 * 并扩展了状态更新、状态转换、轮次递增、用户ID查询、子运行查询等功能。
 * </p>
 */
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /**
     * 更新运行状态
     *
     * @param runId  Agent运行ID
     * @param status 新状态
     * @param error  错误信息，可为null
     * @return 受影响的行数
     */
    @Update("""
            UPDATE agent_run
            SET status = #{status},
                last_error = #{error},
                completed_at = CASE
                    WHEN #{status} IN ('SUCCEEDED', 'FAILED', 'FAILED_RECOVERED', 'CANCELLED', 'TIMEOUT')
                    THEN CURRENT_TIMESTAMP(3)
                    ELSE completed_at
                END
            WHERE run_id = #{runId}
            """)
    int updateStatus(@Param("runId") String runId, @Param("status") String status, @Param("error") String error);

    /**
     * 状态转换操作，仅在当前状态符合预期时才更新
     *
     * @param runId          Agent运行ID
     * @param expectedStatus 期望的当前状态
     * @param nextStatus     目标状态
     * @param error          错误信息，可为null
     * @return 受影响的行数，若当前状态不匹配则返回0
     */
    @Update("""
            UPDATE agent_run
            SET status = #{nextStatus},
                last_error = #{error},
                completed_at = CASE
                    WHEN #{nextStatus} IN ('SUCCEEDED', 'FAILED', 'FAILED_RECOVERED', 'CANCELLED', 'TIMEOUT')
                    THEN CURRENT_TIMESTAMP(3)
                    ELSE completed_at
                END
            WHERE run_id = #{runId}
              AND status = #{expectedStatus}
            """)
    int transitionStatus(
            @Param("runId") String runId,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("error") String error
    );

    /**
     * 递增运行轮次计数
     *
     * @param runId Agent运行ID
     * @return 受影响的行数
     */
    @Update("UPDATE agent_run SET turn_no = turn_no + 1 WHERE run_id = #{runId}")
    int incrementTurn(@Param("runId") String runId);

    /**
     * 获取当前运行轮次号
     *
     * @param runId Agent运行ID
     * @return 当前轮次号
     */
    @Select("SELECT turn_no FROM agent_run WHERE run_id = #{runId}")
    Integer currentTurn(@Param("runId") String runId);

    /**
     * 根据运行ID查询用户ID
     *
     * @param runId Agent运行ID
     * @return 用户ID
     */
    @Select("SELECT user_id FROM agent_run WHERE run_id = #{runId}")
    String findUserId(@Param("runId") String runId);

    /**
     * 根据运行ID查询当前状态
     *
     * @param runId Agent运行ID
     * @return 当前状态字符串
     */
    @Select("SELECT status FROM agent_run WHERE run_id = #{runId}")
    String findStatus(@Param("runId") String runId);

    /**
     * 根据父工具调用ID查询子运行记录
     *
     * @param parentToolCallId 父工具调用ID
     * @return 子运行实体，最多返回一条
     */
    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE parent_tool_call_id = #{parentToolCallId}
            LIMIT 1
            """)
    AgentRunEntity findByParentToolCallId(@Param("parentToolCallId") String parentToolCallId);

    /**
     * 查询启动修复候选运行列表
     * <p>
     * 查找处于CREATED或RUNNING状态且更新时间早于截止时间的运行记录。
     * </p>
     *
     * @param cutoff 截止时间
     * @param limit  返回记录数量上限
     * @return 待修复运行实体列表
     */
    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE status IN ('CREATED', 'RUNNING')
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<AgentRunEntity> findStartupRepairCandidates(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );

    /**
     * 查询过期确认运行列表
     * <p>
     * 查找处于WAITING_USER_CONFIRMATION状态且更新时间早于截止时间的运行记录。
     * </p>
     *
     * @param cutoff 截止时间
     * @param limit  返回记录数量上限
     * @return 过期确认运行实体列表
     */
    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE status = 'WAITING_USER_CONFIRMATION'
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<AgentRunEntity> findExpiredConfirmationRuns(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );

    /**
     * 查询活跃子运行列表
     * <p>
     * 查找指定父运行ID下处于LIVE链接状态且尚未完成的子运行记录。
     * </p>
     *
     * @param parentRunId 父运行ID
     * @return 活跃子运行实体列表
     */
    @Select("""
            SELECT run_id, session_id, user_id, status, turn_no, parent_run_id, parent_tool_call_id,
                   agent_type, parent_link_status, started_at, updated_at, completed_at, last_error
            FROM agent_run
            WHERE parent_run_id = #{parentRunId}
              AND parent_link_status = 'LIVE'
              AND status NOT IN ('SUCCEEDED', 'FAILED', 'FAILED_RECOVERED', 'CANCELLED', 'TIMEOUT')
            ORDER BY started_at ASC
            """)
    List<AgentRunEntity> findLiveChildren(@Param("parentRunId") String parentRunId);

    /**
     * 更新父运行链接状态
     *
     * @param childRunId       子运行ID
     * @param parentLinkStatus 新的父运行链接状态
     * @return 受影响的行数
     */
    @Update("""
            UPDATE agent_run
            SET parent_link_status = #{parentLinkStatus}
            WHERE run_id = #{childRunId}
            """)
    int updateParentLinkStatus(
            @Param("childRunId") String childRunId,
            @Param("parentLinkStatus") String parentLinkStatus
    );
}
