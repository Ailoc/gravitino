/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog.sqlserver.integration.test.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads SQL Server's catalog views directly, so that an integration test can prove the DDL the
 * catalog issued actually landed rather than only that the API round-tripped what it was given.
 *
 * <p>Every query reads a {@code sys} view of the database the connection is bound to, which is the
 * one named by {@code databaseName} in the JDBC URL and by {@code jdbc-database} on the catalog.
 */
public class SqlServerService implements AutoCloseable {

  /**
   * The extended property SQL Server tools use for a description, and the one the catalog writes.
   */
  private static final String DESCRIPTION_PROPERTY = "MS_Description";

  private final Connection connection;

  public SqlServerService(String jdbcUrl, String username, String password) {
    try {
      connection = DriverManager.getConnection(jdbcUrl, username, password);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to connect to SQL Server: " + e.getMessage(), e);
    }
  }

  /**
   * Returns every schema in the database, unfiltered, so a test can check what was filtered out.
   */
  public List<String> listSchemas() {
    return queryStrings("SELECT name FROM sys.schemas ORDER BY name");
  }

  public boolean schemaExists(String schema) {
    return exists("SELECT 1 FROM sys.schemas WHERE name = ?", schema);
  }

  public boolean tableExists(String schema, String table) {
    return exists(
        "SELECT 1 FROM sys.tables t INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE s.name = ? AND t.name = ?",
        schema,
        table);
  }

  public boolean columnExists(String schema, String table, String column) {
    return exists(
        "SELECT 1 FROM sys.columns c "
            + "INNER JOIN sys.tables t ON c.object_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE s.name = ? AND t.name = ? AND c.name = ?",
        schema,
        table,
        column);
  }

  /**
   * Returns the {@code MS_Description} SQL Server stores for a table, or {@code null} if it has
   * none. The catalog appends its own entity id to whatever the user asked for, so callers should
   * compare with {@code contains} rather than for equality.
   */
  public String tableComment(String schema, String table) {
    return queryString(
        "SELECT CAST(p.value AS NVARCHAR(4000)) FROM sys.extended_properties p "
            + "INNER JOIN sys.tables t ON p.major_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE p.minor_id = 0 AND p.name = N'"
            + DESCRIPTION_PROPERTY
            + "' AND s.name = ? AND t.name = ?",
        schema,
        table);
  }

  /**
   * Returns the {@code MS_Description} SQL Server stores for a schema, or {@code null} if it has
   * none. As with a table, the catalog appends its own entity id to the user's text.
   */
  public String schemaComment(String schema) {
    return queryString(
        "SELECT CAST(p.value AS NVARCHAR(4000)) FROM sys.extended_properties p "
            + "INNER JOIN sys.schemas s ON p.major_id = s.schema_id "
            + "WHERE p.minor_id = 0 AND p.name = N'"
            + DESCRIPTION_PROPERTY
            + "' AND s.name = ?",
        schema);
  }

  /** Returns the {@code MS_Description} SQL Server stores for a column, or {@code null}. */
  public String columnComment(String schema, String table, String column) {
    return queryString(
        "SELECT CAST(p.value AS NVARCHAR(4000)) FROM sys.extended_properties p "
            + "INNER JOIN sys.columns c ON p.major_id = c.object_id AND p.minor_id = c.column_id "
            + "INNER JOIN sys.tables t ON c.object_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE p.name = N'"
            + DESCRIPTION_PROPERTY
            + "' AND s.name = ? AND t.name = ? AND c.name = ?",
        schema,
        table,
        column);
  }

  /**
   * Returns the definition SQL Server stores for a column's default constraint, or {@code null} if
   * the column has none. The definition is the text as written, wrapped in the parentheses SQL
   * Server adds, for example {@code (('changed'))}.
   */
  public String columnDefault(String schema, String table, String column) {
    return queryString(
        "SELECT dc.definition FROM sys.columns c "
            + "INNER JOIN sys.tables t ON c.object_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "LEFT JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id "
            + "WHERE s.name = ? AND t.name = ? AND c.name = ?",
        schema,
        table,
        column);
  }

  /** Returns whether SQL Server marked the column as an {@code IDENTITY} column. */
  public boolean columnIsIdentity(String schema, String table, String column) {
    return queryBoolean(
        "SELECT c.is_identity FROM sys.columns c "
            + "INNER JOIN sys.tables t ON c.object_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE s.name = ? AND t.name = ? AND c.name = ?",
        schema,
        table,
        column);
  }

  /** Returns whether SQL Server allows nulls in the column. */
  public boolean columnIsNullable(String schema, String table, String column) {
    return queryBoolean(
        "SELECT c.is_nullable FROM sys.columns c "
            + "INNER JOIN sys.tables t ON c.object_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE s.name = ? AND t.name = ? AND c.name = ?",
        schema,
        table,
        column);
  }

  /**
   * Returns the SQL Server type name of the column, for example {@code bigint} or {@code nvarchar}.
   */
  public String columnType(String schema, String table, String column) {
    return queryString(
        "SELECT ty.name FROM sys.columns c "
            + "INNER JOIN sys.types ty ON c.user_type_id = ty.user_type_id "
            + "INNER JOIN sys.tables t ON c.object_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE s.name = ? AND t.name = ? AND c.name = ?",
        schema,
        table,
        column);
  }

  /** Returns the names of the indexes SQL Server holds on the table, ordered by name. */
  public List<String> listIndexNames(String schema, String table) {
    List<String> names = new ArrayList<>();
    String sql =
        "SELECT i.name FROM sys.indexes i "
            + "INNER JOIN sys.tables t ON i.object_id = t.object_id "
            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE s.name = ? AND t.name = ? AND i.name IS NOT NULL ORDER BY i.name";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          names.add(resultSet.getString(1));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read the indexes of " + table, e);
    }
    return names;
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      // Nothing useful can be done with a connection that refuses to close in a test teardown.
    }
  }

  private List<String> queryStrings(String sql) {
    List<String> values = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        values.add(resultSet.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to run " + sql, e);
    }
    return values;
  }

  private String queryString(String sql, String... parameters) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to run " + sql, e);
    }
  }

  private boolean queryBoolean(String sql, String... parameters) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getBoolean(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to run " + sql, e);
    }
  }

  private boolean exists(String sql, String... parameters) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to run " + sql, e);
    }
  }

  private static void bind(PreparedStatement statement, String... parameters) throws SQLException {
    for (int i = 0; i < parameters.length; i++) {
      statement.setString(i + 1, parameters[i]);
    }
  }
}
