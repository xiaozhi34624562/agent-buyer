package com.ai.agent.web.admin.dto;

/**
 * 管理后台运行列表查询参数。
 *
 * <p>封装查询 Agent 运行列表时的筛选条件和分页参数。
 *
 * @author AI Agent
 */
public class AdminRunListQuery {
    private Integer page = 1;
    private Integer pageSize = 20;
    private String status;
    private String userId;

    /**
     * 获取页码。
     *
     * @return 页码
     */
    public Integer getPage() {
        return page;
    }

    /**
     * 设置页码。
     *
     * @param page 页码
     */
    public void setPage(Integer page) {
        this.page = page;
    }

    /**
     * 获取每页大小。
     *
     * @return 每页大小
     */
    public Integer getPageSize() {
        return pageSize;
    }

    /**
     * 设置每页大小。
     *
     * @param pageSize 每页大小
     */
    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * 获取状态筛选条件。
     *
     * @return 运行状态
     */
    public String getStatus() {
        return status;
    }

    /**
     * 设置状态筛选条件。
     *
     * @param status 运行状态
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * 获取用户 ID 筛选条件。
     *
     * @return 用户 ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 设置用户 ID 筛选条件。
     *
     * @param userId 用户 ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 获取校验后的页码，确保最小值为 1。
     *
     * @return 校验后的页码
     */
    public int getClampedPage() {
        return Math.max(1, page != null ? page : 1);
    }

    /**
     * 获取校验后的每页大小，确保范围在 1-100 之间。
     *
     * @return 校验后的每页大小
     */
    public int getClampedPageSize() {
        int size = pageSize != null ? pageSize : 20;
        return Math.max(1, Math.min(100, size));
    }

    /**
     * 计算分页偏移量。
     *
     * @return 偏移量
     */
    public int getOffset() {
        return (getClampedPage() - 1) * getClampedPageSize();
    }
}