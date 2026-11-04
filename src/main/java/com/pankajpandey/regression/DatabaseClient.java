/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.pankajpandey.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseClient implements AutoCloseable {
    private final Config cfg;
    private final ObjectMapper mapper;
    private final ObjectMapper referenceMapper;
    private final ThreadLocal<Connection> connectionHolder = new ThreadLocal<>();
    private final Set<Connection> openConnections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public DatabaseClient(Config cfg, ObjectMapper mapper, ObjectMapper referenceMapper) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.referenceMapper = referenceMapper;
    }

    public DatabaseClient(Config cfg, ObjectMapper mapper) {
        this(cfg, mapper, mapper);
    }

    public record DualJsonResult(JsonNode actualNode, JsonNode referenceNode) {}

    public boolean isConfigured() {
        return cfg.dbUrl != null && !cfg.dbUrl.isBlank();
    }

    public JsonNode executeJsonQuery(String sql) throws SQLException {
        if (!isConfigured()) {
            throw new SQLException("DB config missing. Set JR_DB_URL, JR_DB_USERNAME, and JR_DB_PASSWORD.");
        }
        if (sql == null || sql.isBlank()) {
            return mapper.createArrayNode();
        }
        Connection connection = ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (cfg.dbQueryTimeoutSeconds > 0) {
                ps.setQueryTimeout(cfg.dbQueryTimeoutSeconds);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return mapResultSet(rs, mapper);
            }
        }
    }

    public JsonNode executeJsonQuery(String sql, List<JsonNode> params) throws SQLException {
        if (!isConfigured()) {
            throw new SQLException("DB config missing. Set JR_DB_URL, JR_DB_USERNAME, and JR_DB_PASSWORD.");
        }
        if (sql == null || sql.isBlank()) {
            return mapper.createArrayNode();
        }
        Connection connection = ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (cfg.dbQueryTimeoutSeconds > 0) {
                ps.setQueryTimeout(cfg.dbQueryTimeoutSeconds);
            }
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return mapResultSet(rs, mapper);
            }
        }
    }

    public DualJsonResult executeJsonQueryWithReference(String sql, List<JsonNode> params) throws SQLException {
        if (!isConfigured()) {
            throw new SQLException("DB config missing. Set JR_DB_URL, JR_DB_USERNAME, and JR_DB_PASSWORD.");
        }
        if (sql == null || sql.isBlank()) {
            return new DualJsonResult(mapper.createArrayNode(), referenceMapper.createArrayNode());
        }
        Connection connection = ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (cfg.dbQueryTimeoutSeconds > 0) {
                ps.setQueryTimeout(cfg.dbQueryTimeoutSeconds);
            }
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                MaterializedRows materialized = materializeRows(rs);
                JsonNode actualNode = mapMaterializedRows(materialized, mapper);
                JsonNode referenceNode = mapMaterializedRows(materialized, referenceMapper);
                return new DualJsonResult(actualNode, referenceNode);
            }
        }
    }

    private void bindParams(PreparedStatement ps, List<JsonNode> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            JsonNode param = params.get(i);
            int index = i + 1;
            if (param == null || param.isNull()) {
                ps.setObject(index, null);
            } else if (param.isBoolean()) {
                ps.setBoolean(index, param.booleanValue());
            } else if (param.isNumber()) {
                ps.setBigDecimal(index, param.decimalValue());
            } else {
                ps.setString(index, param.asText());
            }
        }
    }

    private Connection ensureConnection() throws SQLException {
        if (closed) {
            throw new SQLException("DatabaseClient is closed");
        }
        Connection existing = connectionHolder.get();
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        if (existing != null) {
            openConnections.remove(existing);
        }
        try {
            Class.forName(cfg.dbDriver);
        } catch (ClassNotFoundException e) {
            throw new SQLException("DB driver not found: " + cfg.dbDriver, e);
        }
        DriverManager.setLoginTimeout(Math.max(1, cfg.dbConnectTimeoutSeconds));
        Properties props = new Properties();
        props.setProperty("user", cfg.dbUsername);
        props.setProperty("password", cfg.dbPassword);
        Connection created = DriverManager.getConnection(cfg.dbUrl, props);
        openConnections.add(created);
        if (cfg.dbQueryTimeoutSeconds > 0) {
            try (Statement st = created.createStatement()) {
                st.setQueryTimeout(cfg.dbQueryTimeoutSeconds);
            } catch (SQLException ignored) {
                // Some drivers ignore statement-level defaults.
            }
        }
        if (closed) {
            openConnections.remove(created);
            try {
                created.close();
            } catch (SQLException ignored) {
            }
            throw new SQLException("DatabaseClient is closed");
        }
        connectionHolder.set(created);
        return created;
    }

    private record MaterializedRows(List<String> columnLabels, List<List<Object>> rows) {}

    private MaterializedRows materializeRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        List<String> columnLabels = new ArrayList<>();
        for (int i = 1; i <= columns; i++) {
            columnLabels.add(meta.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= columns; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return new MaterializedRows(columnLabels, rows);
    }

    private JsonNode mapResultSet(ResultSet rs, ObjectMapper targetMapper) throws SQLException {
        return mapMaterializedRows(materializeRows(rs), targetMapper);
    }

    private JsonNode mapMaterializedRows(MaterializedRows materialized, ObjectMapper targetMapper) {
        int columns = materialized.columnLabels().size();
        ArrayNode rows = targetMapper.createArrayNode();
        for (List<Object> rowValues : materialized.rows()) {
            if (columns == 1) {
                Object single = rowValues.get(0);
                JsonNode parsed = parsePotentialJson(single, targetMapper);
                if (parsed != null) {
                    rows.add(parsed);
                } else {
                    rows.add(normalizeScalar(single, targetMapper));
                }
                continue;
            }
            ObjectNode row = targetMapper.createObjectNode();
            for (int i = 0; i < columns; i++) {
                row.set(materialized.columnLabels().get(i), normalizeScalar(rowValues.get(i), targetMapper));
            }
            rows.add(row);
        }
        if (columns == 1 && rows.size() == 1) {
            JsonNode only = rows.get(0);
            if (only != null && (only.isObject() || only.isArray())) {
                return only;
            }
        }
        return rows;
    }

    private JsonNode parsePotentialJson(Object value, ObjectMapper targetMapper) {
        if (value == null) {
            return targetMapper.createArrayNode();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return targetMapper.createArrayNode();
        }
        if (!(raw.startsWith("{") || raw.startsWith("["))) {
            return null;
        }
        try {
            return targetMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode normalizeScalar(Object value, ObjectMapper targetMapper) {
        if (value == null) {
            return targetMapper.nullNode();
        }
        if (value instanceof Integer i) return targetMapper.getNodeFactory().numberNode(i);
        if (value instanceof Long l) return targetMapper.getNodeFactory().numberNode(l);
        if (value instanceof Double d) return targetMapper.getNodeFactory().numberNode(BigDecimal.valueOf(d));
        if (value instanceof Float f) return targetMapper.getNodeFactory().numberNode(BigDecimal.valueOf(f));
        if (value instanceof BigDecimal bd) return targetMapper.getNodeFactory().numberNode(bd);
        if (value instanceof Boolean b) return targetMapper.getNodeFactory().booleanNode(b);
        if (value instanceof LocalDate) return targetMapper.getNodeFactory().textNode(value.toString());
        if (value instanceof LocalDateTime) return targetMapper.getNodeFactory().textNode(value.toString());
        return targetMapper.getNodeFactory().textNode(String.valueOf(value));
    }

    @Override
    public void close() {
        closed = true;
        for (Connection c : openConnections) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
        }
        openConnections.clear();
    }

    Connection activeConnectionForCurrentThread() {
        return connectionHolder.get();
    }

    int trackedConnectionCount() {
        return openConnections.size();
    }
}
