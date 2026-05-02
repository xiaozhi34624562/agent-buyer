package com.ai.agent.web.admin.service;

import com.ai.agent.web.admin.dto.AdminRunListDto;
import com.ai.agent.web.admin.dto.AdminRunListQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminRunListService {

    private final JdbcTemplate jdbcTemplate;

    public AdminRunListService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AdminRunListDto> listRuns(AdminRunListQuery query) {
        int offset = query.getOffset();
        int limit = query.getClampedPageSize();

        StringBuilder sql = new StringBuilder("""
                SELECT
                    r.run_id,
                    r.user_id,
                    r.status,
                    r.turn_no,
                    r.agent_type,
                    r.parent_run_id,
                    r.parent_link_status,
                    c.primary_provider,
                    c.fallback_provider,
                    c.model,
                    c.max_turns,
                    r.started_at,
                    r.updated_at,
                    r.completed_at,
                    r.last_error
                FROM agent_run r
                JOIN agent_run_context c ON r.run_id = c.run_id
                WHERE 1=1
                """);

        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            sql.append(" AND r.status = ?");
        }
        if (query.getUserId() != null && !query.getUserId().isBlank()) {
            sql.append(" AND r.user_id = ?");
        }

        // Fixed sort order - no dynamic sort
        sql.append(" ORDER BY r.updated_at DESC");
        sql.append(" LIMIT ? OFFSET ?");

        return jdbcTemplate.query(
                sql.toString(),
                ps -> {
                    int paramIndex = 1;
                    if (query.getStatus() != null && !query.getStatus().isBlank()) {
                        ps.setString(paramIndex++, query.getStatus());
                    }
                    if (query.getUserId() != null && !query.getUserId().isBlank()) {
                        ps.setString(paramIndex++, query.getUserId());
                    }
                    ps.setInt(paramIndex++, limit);
                    ps.setInt(paramIndex, offset);
                },
                this::mapRow
        );
    }

    private AdminRunListDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AdminRunListDto(
                rs.getString("run_id"),
                rs.getString("user_id"),
                rs.getString("status"),
                rs.getInt("turn_no"),
                rs.getString("agent_type"),
                rs.getString("parent_run_id"),
                rs.getString("parent_link_status"),
                rs.getString("primary_provider"),
                rs.getString("fallback_provider"),
                rs.getString("model"),
                rs.getInt("max_turns"),
                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null,
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
                rs.getString("last_error")
        );
    }
}