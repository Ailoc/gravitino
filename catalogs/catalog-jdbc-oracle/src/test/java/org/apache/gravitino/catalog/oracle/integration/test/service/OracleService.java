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
package org.apache.gravitino.catalog.oracle.integration.test.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Oracle's data dictionary directly, so that an integration test can prove the DDL the
 * catalog issued actually landed rather than only that the API round-tripped what it was given.
 *
 * <p>Every query reads an {@code ALL_} view, which any account can see for the objects it has
 * access to, so the account named by {@code GRAVITINO_ORACLE_USERNAME} needs no dictionary
 * privilege beyond what creating a schema already requires.
 */
public class OracleService implements AutoCloseable {

  private final Connection connection;

  public OracleService(String jdbcUrl, String username, String password) {
    try {
      connection = DriverManager.getConnection(jdbcUrl, username, password);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to connect to Oracle: " + e.getMessage(), e);
    }
  }

  /** Returns every schema Oracle knows about, unfiltered, so a test can check what was dropped. */
  public List<String> listSchemas() {
    return queryStrings("SELECT USERNAME FROM ALL_USERS ORDER BY USERNAME");
  }

  public boolean schemaExists(String schema) {
    return exists("SELECT 1 FROM ALL_USERS WHERE USERNAME = ?", schema);
  }

  public boolean tableExists(String schema, String table) {
    return exists("SELECT 1 FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?", schema, table);
  }

  /**
   * Returns the comment Oracle stores for a table, or {@code null} if it has none. The catalog
   * appends its own entity id to whatever the user asked for, so callers should compare with {@code
   * contains} rather than for equality.
   */
  public String tableComment(String schema, String table) {
    return queryString(
        "SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?", schema, table);
  }

  /** Returns the comment Oracle stores for a column, or {@code null} if it has none. */
  public String columnComment(String schema, String table, String column) {
    return queryString(
        "SELECT COMMENTS FROM ALL_COL_COMMENTS "
            + "WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
        schema,
        table,
        column);
  }

  /**
   * Returns the default Oracle stores for a column exactly as the dictionary spells it, or {@code
   * null} if the column has no default.
   */
  public String columnDefault(String schema, String table, String column) {
    return queryString(
        "SELECT DATA_DEFAULT FROM ALL_TAB_COLUMNS "
            + "WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
        schema,
        table,
        column);
  }

  /** Returns whether Oracle marked the column as an identity column. */
  public boolean columnIsIdentity(String schema, String table, String column) {
    return "YES"
        .equalsIgnoreCase(
            queryString(
                "SELECT IDENTITY_COLUMN FROM ALL_TAB_COLUMNS "
                    + "WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                schema,
                table,
                column));
  }

  /** Returns whether Oracle allows nulls in the column. */
  public boolean columnIsNullable(String schema, String table, String column) {
    return "Y"
        .equalsIgnoreCase(
            queryString(
                "SELECT NULLABLE FROM ALL_TAB_COLUMNS "
                    + "WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                schema,
                table,
                column));
  }

  /** Returns the Oracle type name of the column, for example {@code NUMBER} or {@code VARCHAR2}. */
  public String columnType(String schema, String table, String column) {
    return queryString(
        "SELECT DATA_TYPE FROM ALL_TAB_COLUMNS "
            + "WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
        schema,
        table,
        column);
  }

  /** Returns the names of the indexes Oracle holds on the table, ordered by name. */
  public List<String> listIndexNames(String schema, String table) {
    List<String> names = new ArrayList<>();
    String sql =
        "SELECT INDEX_NAME FROM ALL_INDEXES WHERE TABLE_OWNER = ? AND TABLE_NAME = ? "
            + "ORDER BY INDEX_NAME";
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
