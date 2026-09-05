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
package org.apache.gravitino.catalog.sqlserver.operation;

import static org.apache.gravitino.catalog.sqlserver.operation.SqlServerTableOperations.escapeLiteral;
import static org.apache.gravitino.catalog.sqlserver.operation.SqlServerTableOperations.quote;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.catalog.sqlserver.converter.SqlServerExceptionConverter;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.meta.AuditInfo;

/**
 * Schema operations for SQL Server.
 *
 * <p>A SQL Server connection is bound to exactly one database, and each database contains multiple
 * schemas. Gravitino therefore maps one catalog to the database named by {@code jdbc-database}, and
 * one Gravitino schema to one SQL Server schema. This mirrors the PostgreSQL catalog mapping.
 */
public class SqlServerSchemaOperations extends JdbcDatabaseOperations {

  /**
   * System schemas and fixed database roles that must never be surfaced as Gravitino schemas. The
   * SQL Server driver reports the fixed database roles through {@code DatabaseMetaData#getSchemas}
   * as well, so they are filtered here too. The name capability check reads this set to reject
   * attempts at reusing a reserved name.
   */
  public static final Set<String> SYSTEM_SCHEMAS =
      ImmutableSet.of(
          "sys",
          "information_schema",
          "guest",
          "db_owner",
          "db_accessadmin",
          "db_securityadmin",
          "db_ddladmin",
          "db_backupoperator",
          "db_datareader",
          "db_datawriter",
          "db_denydatareader",
          "db_denydatawriter");

  private String database;

  @Override
  public void initialize(
      DataSource dataSource, JdbcExceptionConverter exceptionMapper, Map<String, String> conf) {
    super.initialize(dataSource, exceptionMapper, conf);
    database = new JdbcConfig(conf).getJdbcDatabase();
  }

  /**
   * Creates a SQL Server schema, then stores its comment as an extended property.
   *
   * <p>The base implementation switches the connection away from the database it is about to
   * create, which is a MySQL-ism. In SQL Server a schema lives inside the connected database, so
   * that switch is both unnecessary and invalid.
   *
   * <p>The statements also have to run one at a time rather than as the single batch the base
   * implementation sends, because {@code CREATE SCHEMA} must be alone in its batch. See {@link
   * #generateCreateDatabaseSqls}.
   */
  @Override
  public void create(String schemaName, String comment, Map<String, String> properties)
      throws SchemaAlreadyExistsException {
    LOG.info("Beginning to create schema {}", schemaName);
    String originComment = StringIdentifier.removeIdFromComment(comment);
    if (!supportSchemaComment() && StringUtils.isNotEmpty(originComment)) {
      throw new UnsupportedOperationException(
          "Doesn't support setting schema comment: " + originComment);
    }

    try (Connection connection = getConnection()) {
      // SQL Server reports a duplicate schema with the same error code it uses for a duplicate
      // table, so the duplicate has to be detected here to raise a schema specific exception.
      if (schemaExists(connection, schemaName)) {
        throw SqlServerExceptionConverter.schemaAlreadyExists(schemaName);
      }
      for (String sql : generateCreateDatabaseSqls(schemaName, comment, properties)) {
        JdbcConnectorUtils.executeUpdate(connection, sql);
      }
      LOG.info("Finished creating schema {}", schemaName);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  public JdbcSchema load(String schema) throws NoSuchSchemaException {
    try (Connection connection = getConnection()) {
      if (!schemaExists(connection, schema)) {
        throw SqlServerExceptionConverter.noSuchSchema(schema);
      }
      return JdbcSchema.builder()
          .withName(schema)
          .withComment(getSchemaComment(connection, schema))
          .withAuditInfo(AuditInfo.EMPTY)
          .withProperties(Collections.emptyMap())
          .build();
    } catch (SQLException e) {
      throw exceptionMapper.toGravitinoException(e);
    }
  }

  @Override
  public List<String> listDatabases() {
    List<String> result = new ArrayList<>();
    try (Connection connection = getConnection();
        ResultSet resultSet = getSchemas(connection, null)) {
      while (resultSet.next()) {
        String schemaName = resultSet.getString(1);
        if (!isSystemDatabase(schemaName)) {
          result.add(schemaName);
        }
      }
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
    return result;
  }

  @Override
  public boolean exist(String schemaName) {
    try (Connection connection = getConnection()) {
      return schemaExists(connection, schemaName);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Generates the statements creating a schema, in the order they have to run.
   *
   * <p>{@code CREATE SCHEMA} has to be the only statement in its T-SQL batch, so the extended
   * property carrying the comment cannot be appended to it. Joining the two with a semicolon and
   * sending them as one batch is what makes the driver report {@code Incorrect syntax near the
   * keyword 'EXEC'}, and because {@code JdbcCatalogOperations} always attaches the Gravitino id as
   * a comment, that batch would be generated on every single schema creation.
   *
   * @param schema the schema to create
   * @param comment the comment, stored as an {@code MS_Description} extended property
   * @param properties the schema properties, which SQL Server does not persist
   * @return the statements to run, the second present only when there is a comment
   */
  @VisibleForTesting
  List<String> generateCreateDatabaseSqls(
      String schema, String comment, Map<String, String> properties) {
    List<String> statements = new ArrayList<>();
    statements.add(generateCreateDatabaseSql(schema, comment, properties));
    if (StringUtils.isNotEmpty(comment)) {
      statements.add(schemaCommentSql(schema, comment));
    }
    return statements;
  }

  /**
   * Generates the {@code CREATE SCHEMA} statement on its own.
   *
   * <p>The {@code comment} argument is deliberately unused: SQL Server stores a description as an
   * extended property, and {@code CREATE SCHEMA} must be alone in its batch, so the comment is
   * emitted as a separate statement by {@link #generateCreateDatabaseSqls}.
   *
   * @param schema the schema to create
   * @param comment ignored, stored by a separate statement
   * @param properties the schema properties, which SQL Server does not persist
   * @return the statement creating the schema
   */
  @Override
  public String generateCreateDatabaseSql(
      String schema, String comment, Map<String, String> properties) {
    if (MapUtils.isNotEmpty(properties)) {
      throw new UnsupportedOperationException(
          "SQL Server does not support properties on schema create.");
    }
    return "CREATE SCHEMA " + quote(schema);
  }

  /**
   * SQL Server has no {@code DROP SCHEMA ... CASCADE}, so a cascade has to remove the contained
   * tables first. The generated batch enumerates them at execution time.
   */
  @Override
  public String generateDropDatabaseSql(String schema, boolean cascade) {
    String quotedSchema = quote(schema);
    if (!cascade) {
      return "DROP SCHEMA " + quotedSchema;
    }

    return "DECLARE @gravitinoDropSql NVARCHAR(MAX) = N'';\n"
        + "SELECT @gravitinoDropSql += N'DROP TABLE ' + QUOTENAME(s.name) + N'.' + QUOTENAME(t.name) + N';'\n"
        + "FROM sys.tables t INNER JOIN sys.schemas s ON t.schema_id = s.schema_id\n"
        + "WHERE s.name = N'"
        + escapeLiteral(schema)
        + "';\n"
        + "EXEC sp_executesql @gravitinoDropSql;\n"
        + "DROP SCHEMA "
        + quotedSchema;
  }

  @Override
  protected void dropDatabase(String schema, boolean cascade) {
    if (!cascade && !isSchemaEmpty(schema)) {
      throw new IllegalStateException(
          String.format("Schema %s is not empty, the value of cascade should be true.", schema));
    }
    super.dropDatabase(schema, cascade);
  }

  @Override
  protected Connection getConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setCatalog(database);
    return connection;
  }

  @Override
  protected boolean supportSchemaComment() {
    return true;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return SYSTEM_SCHEMAS;
  }

  /**
   * Checks whether a schema exists in the connected database.
   *
   * <p>The driver treats the schema argument as a {@code LIKE} pattern, so an exact comparison is
   * still required to avoid a name such as {@code a_c} matching {@code abc}.
   *
   * @param connection the connection bound to the target database
   * @param schema the schema name to look for
   * @return true if the schema exists
   * @throws SQLException if the metadata query fails
   */
  public boolean schemaExists(Connection connection, String schema) throws SQLException {
    try (ResultSet resultSet = getSchemas(connection, schema)) {
      while (resultSet.next()) {
        if (Objects.equals(resultSet.getString(1), schema)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Builds the {@code sp_addextendedproperty} call used to attach an {@code MS_Description} to a
   * schema or table.
   */
  static String addExtendedPropertySql(String comment, String levelClause) {
    return String.format(
        "EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'%s', %s",
        escapeLiteral(comment), levelClause);
  }

  /**
   * Builds the statement storing a schema description as an {@code MS_Description}.
   *
   * @param schema the schema to describe
   * @param comment the description text
   * @return the statement storing the description
   */
  private static String schemaCommentSql(String schema, String comment) {
    return addExtendedPropertySql(
        comment,
        String.format("@level0type = N'SCHEMA', @level0name = N'%s'", escapeLiteral(schema)));
  }

  /**
   * Reads the schemas of the connected database.
   *
   * <p>The catalog argument is deliberately {@code null}. mssql-jdbc answers {@code getSchemas}
   * with zero rows when a non-null catalog is combined with a null schema pattern, which is exactly
   * the combination {@code listDatabases} needs, so naming the catalog here would silently
   * enumerate nothing. Leaving it unfiltered widens nothing: the connection is already bound to one
   * database.
   *
   * @param connection the connection bound to the target database
   * @param schemaPattern the pattern to match schema names against, or null for all of them
   * @return the matching schemas
   * @throws SQLException if the metadata query fails
   */
  private ResultSet getSchemas(Connection connection, String schemaPattern) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getSchemas(null, schemaPattern);
  }

  private String getSchemaComment(Connection connection, String schema) throws SQLException {
    String sql =
        "SELECT CAST(p.value AS NVARCHAR(4000)) AS comment FROM sys.extended_properties p "
            + "INNER JOIN sys.schemas s ON p.major_id = s.schema_id "
            + "WHERE p.minor_id = 0 AND p.name = N'MS_Description' AND s.name = N'"
            + escapeLiteral(schema)
            + "'";
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      if (resultSet.next()) {
        return resultSet.getString("comment");
      }
    }
    return null;
  }

  private boolean isSchemaEmpty(String schema) {
    String sql =
        "SELECT 1 FROM sys.tables t INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE s.name = N'"
            + escapeLiteral(schema)
            + "'";
    try (Connection connection = getConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      return !resultSet.next();
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }
}
