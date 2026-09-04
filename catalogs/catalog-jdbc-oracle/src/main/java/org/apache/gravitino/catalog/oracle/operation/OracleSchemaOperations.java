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
package org.apache.gravitino.catalog.oracle.operation;

import static org.apache.gravitino.catalog.oracle.operation.OracleTableOperations.quote;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.catalog.oracle.converter.OracleExceptionConverter;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.meta.AuditInfo;

/**
 * Schema operations for Oracle.
 *
 * <p>Oracle has no schema object of its own: a schema is the namespace owned by a database user. A
 * Gravitino schema is therefore created as a user that cannot log in, using {@code NO
 * AUTHENTICATION}. Creating one needs the {@code CREATE USER} system privilege, which the account
 * named by {@code jdbc-user} must hold.
 *
 * <p>Oracle also has no catalog level above the schema, so the driver reports {@code null} from
 * {@link Connection#getCatalog()} and rejects {@link Connection#setCatalog}. The schema is selected
 * per connection with {@link Connection#setSchema}, which the driver implements as {@code ALTER
 * SESSION SET CURRENT_SCHEMA}, letting the table operations issue unqualified DDL.
 */
public class OracleSchemaOperations extends JdbcDatabaseOperations {

  /**
   * Schemas owned by Oracle itself. {@link DatabaseMetaData#getSchemas} reports every schema the
   * connecting account can see, which in a default database is dominated by these. The name
   * capability check reads this set to reject attempts at reusing a reserved name.
   */
  public static final Set<String> SYSTEM_SCHEMAS =
      ImmutableSet.<String>builder()
          .add(
              "sys",
              "system",
              "outln",
              "dip",
              "dbsnmp",
              "appqossys",
              "wmsys",
              "xdb",
              "ctxsys",
              "mdsys",
              "ordsys",
              "orddata",
              "ordplugins",
              "si_informtn_schema",
              "mddata",
              "sysman",
              "olapsys",
              "lbacsys",
              "exfsys",
              "gsmadmin_internal",
              "audsys",
              "ojvmsys",
              "dbsfwuser",
              "remote_scheduler_agent",
              "sysbackup",
              "sysdg",
              "syskm",
              "sysrac",
              "sys$umf",
              "anonymous",
              "apex_public_user",
              "flows_files",
              "oracle_ocm",
              "mgmt_view",
              "owbsys",
              "owbsys_audit",
              "spatial_csw_admin",
              "spatial_wfs_admin",
              "xs$null",
              "dgpdb_int",
              "ggsys",
              "dvf",
              "dvsys",
              "pdbadmin")
          .build();

  /** Versioned Oracle APEX schemas, such as {@code APEX_040200}, one per installed release. */
  private static final Pattern APEX_SCHEMA = Pattern.compile("apex_\\d{6}");

  /**
   * Reads the permanent tablespace Oracle treats as the default, used to size a new schema's quota.
   */
  private static final String DEFAULT_TABLESPACE_SQL =
      "SELECT PROPERTY_VALUE FROM DATABASE_PROPERTIES "
          + "WHERE PROPERTY_NAME = 'DEFAULT_PERMANENT_TABLESPACE'";

  /**
   * Creates an Oracle schema, then gives it a tablespace quota so that it can hold tables.
   *
   * <p>The base implementation dereferences {@link Connection#getCatalog()}, which is {@code null}
   * on Oracle, so it has to be replaced rather than reused.
   *
   * @param schemaName the schema to create
   * @param comment the comment, which may only carry the Gravitino entity id
   * @param properties the schema properties, which Oracle does not persist
   * @throws SchemaAlreadyExistsException if the schema already exists
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
      // Oracle reports a duplicate schema as ORA-00955, the same code it uses for a duplicate
      // table, so the duplicate has to be detected here to raise a schema specific exception.
      if (schemaExists(connection, schemaName)) {
        throw OracleExceptionConverter.schemaAlreadyExists(schemaName);
      }
      JdbcConnectorUtils.executeUpdate(
          connection, generateCreateDatabaseSql(schemaName, comment, properties));
      grantTablespaceQuota(connection, schemaName);
      LOG.info("Finished creating schema {}", schemaName);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  public JdbcSchema load(String schema) throws NoSuchSchemaException {
    try (Connection connection = getConnection()) {
      if (!schemaExists(connection, schema)) {
        throw OracleExceptionConverter.noSuchSchema(schema);
      }
      return JdbcSchema.builder()
          .withName(schema)
          // Oracle has no COMMENT ON SCHEMA, so there is nothing to read back.
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

  /** The base implementation queries {@code information_schema.SCHEMATA}, which Oracle lacks. */
  @Override
  public boolean exist(String schemaName) {
    try (Connection connection = getConnection()) {
      return schemaExists(connection, schemaName);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Creates the user that owns the schema, with {@code NO AUTHENTICATION} so that it is a namespace
   * and not an account anyone can log into.
   *
   * <p>The tablespace quota that makes the schema usable is granted by a separate statement in
   * {@link #create}, because Oracle accepts only one statement per call and the tablespace has to
   * be read from the database first.
   *
   * @param schema the schema to create
   * @param comment ignored, Oracle has no {@code COMMENT ON SCHEMA}
   * @param properties the schema properties, which Oracle does not persist
   * @return the statement creating the schema
   */
  @Override
  public String generateCreateDatabaseSql(
      String schema, String comment, Map<String, String> properties) {
    if (MapUtils.isNotEmpty(properties)) {
      throw new UnsupportedOperationException(
          "Oracle does not support properties on schema create.");
    }
    return "CREATE USER " + quote(schema) + " NO AUTHENTICATION";
  }

  @Override
  public String generateDropDatabaseSql(String schema, boolean cascade) {
    StringBuilder sqlBuilder = new StringBuilder("DROP USER " + quote(schema));
    if (cascade) {
      sqlBuilder.append(" CASCADE");
    }
    return sqlBuilder.toString();
  }

  /**
   * Oracle rejects {@code DROP USER} on a schema that still owns objects, and its ORA-01940 does
   * not say that a cascade is the fix, so the check is made here to give an actionable message.
   */
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
    // Oracle has no catalog level, so setCatalog must not be called.
    return dataSource.getConnection();
  }

  @Override
  protected boolean supportSchemaComment() {
    return false;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return SYSTEM_SCHEMAS;
  }

  @Override
  protected boolean isSystemDatabase(String dbName) {
    return isReservedSchema(dbName);
  }

  /**
   * Checks whether a name belongs to Oracle rather than to the user. On top of the fixed set of
   * Oracle owned schemas this covers the versioned APEX schemas, one per installed release, whose
   * names cannot be enumerated ahead of time.
   *
   * @param schemaName the name to check
   * @return true if Oracle owns the schema
   */
  public static boolean isReservedSchema(String schemaName) {
    String lowerCaseName = schemaName.toLowerCase(Locale.ROOT);
    return SYSTEM_SCHEMAS.contains(lowerCaseName) || APEX_SCHEMA.matcher(lowerCaseName).matches();
  }

  /**
   * Checks whether a schema exists.
   *
   * <p>The driver treats the schema argument as a {@code LIKE} pattern, so an exact comparison is
   * still required to avoid a name such as {@code a_c} matching {@code abc}.
   *
   * @param connection the connection to the database
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
   * Builds the statement giving the schema room to hold objects.
   *
   * @param schema the schema the quota is granted to
   * @param tablespace the tablespace to grant an unlimited quota on
   * @return the statement granting the quota
   */
  @VisibleForTesting
  static String grantUnlimitedQuotaSql(String schema, String tablespace) {
    return "ALTER USER " + quote(schema) + " QUOTA UNLIMITED ON " + quote(tablespace);
  }

  private ResultSet getSchemas(Connection connection, String schemaPattern) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getSchemas(null, schemaPattern);
  }

  /**
   * Grants the new schema an unlimited quota on the database default permanent tablespace.
   *
   * <p>A user created with {@code NO AUTHENTICATION} has no tablespace quota, and Oracle checks the
   * quota of the schema owner rather than of the account issuing the DDL. Without this grant every
   * {@code CREATE TABLE} in the new schema would fail with ORA-01950.
   *
   * <p>Not being able to read the tablespace is not treated as a failure. The schema has already
   * been created at this point, and a DBA can grant the quota, so the situation is logged instead.
   *
   * @param connection the connection the schema was created on
   * @param schema the schema to grant the quota to
   * @throws SQLException if the grant itself fails
   */
  private void grantTablespaceQuota(Connection connection, String schema) throws SQLException {
    String tablespace = resolveDefaultTablespace(connection);
    if (StringUtils.isBlank(tablespace)) {
      LOG.warn(
          "Could not resolve the default permanent tablespace, schema {} was created without a "
              + "tablespace quota and a DBA has to grant one before tables can be created in it",
          schema);
      return;
    }
    JdbcConnectorUtils.executeUpdate(connection, grantUnlimitedQuotaSql(schema, tablespace));
  }

  private String resolveDefaultTablespace(Connection connection) {
    try (PreparedStatement statement = connection.prepareStatement(DEFAULT_TABLESPACE_SQL);
        ResultSet resultSet = statement.executeQuery()) {
      if (resultSet.next()) {
        return resultSet.getString(1);
      }
    } catch (SQLException se) {
      LOG.warn("Failed to read the default permanent tablespace from the database", se);
    }
    return null;
  }

  private boolean isSchemaEmpty(String schema) {
    try (Connection connection = getConnection();
        ResultSet tables =
            connection
                .getMetaData()
                .getTables(null, schema, null, JdbcConnectorUtils.getTableTypes())) {
      while (tables.next()) {
        if (Objects.equals(tables.getString("TABLE_SCHEM"), schema)) {
          return false;
        }
      }
      return true;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }
}
