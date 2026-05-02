package com.ai.agent.web.admin.dto;

public class AdminRunListQuery {
    private Integer page = 1;
    private Integer pageSize = 20;
    private String status;
    private String userId;

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public int getClampedPage() {
        return Math.max(1, page != null ? page : 1);
    }

    public int getClampedPageSize() {
        int size = pageSize != null ? pageSize : 20;
        return Math.max(1, Math.min(100, size));
    }

    public int getOffset() {
        return (getClampedPage() - 1) * getClampedPageSize();
    }
}