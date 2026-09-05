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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.DatabaseOperation;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.jdbc.operation.RequireDatabaseOperation;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchColumnException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Types;

/**
 * Table operations for SQL Server.
 *
 * <p>SQL Server has no session level "current schema" equivalent to PostgreSQL's {@code
 * search_path}, so every generated statement fully qualifies the target as {@code
 * [schema].[table]}. For the same reason the schema-less {@code generateXxxTableSql} methods
 * inherited from {@link JdbcTableOperations} are unsupported here; their callers are overridden to
 * use the schema-aware variants defined in this class.
 */
public class SqlServerTableOperations extends JdbcTableOperations
    implements RequireDatabaseOperation {

  /** Opening delimiter of a SQL Server quoted identifier. */
  public static final String QUOTED_IDENTIFIER_PREFIX = "[";

  /** Closing delimiter of a SQL Server quoted identifier. */
  public static final String QUOTED_IDENTIFIER_SUFFIX = "]";

  /** Name of the extended property SQL Server tooling uses to hold object descriptions. */
  public static final String DESCRIPTION_PROPERTY = "MS_Description";

  private static final String NEW_LINE = "\n";
  private static final String ALTER_TABLE = "ALTER TABLE ";
  private static final String NOT_SUPPORT_NESTED_COLUMN_MSG =
      "SQL Server does not support nested column names.";

  /** Batch variable holding the server generated name of a column default constraint. */
  private static final String CONSTRAINT_NAME_VARIABLE = "@gravitinoConstraintName";

  /** Batch variable holding the definition of the column default constraint being replaced. */
  private static final String CONSTRAINT_DEFINITION_VARIABLE = "@gravitinoConstraintDefinition";

  /** Batch variable holding a statement assembled from the two variables above. */
  private static final String DYNAMIC_SQL_VARIABLE = "@gravitinoDynamicSql";

  /**
   * Declares the variables every default constraint fragment uses.
   *
   * <p>A batch declares them once at its head, because SQL Server rejects a second {@code DECLARE}
   * of the same name with error 134 and a single alter request routinely carries several changes
   * that each touch a column default.
   */
  private static final String CONSTRAINT_VARIABLE_DECLARATIONS =
      "DECLARE "
          + CONSTRAINT_NAME_VARIABLE
          + " NVARCHAR(256);"
          + NEW_LINE
          + "DECLARE "
          + CONSTRAINT_DEFINITION_VARIABLE
          + " NVARCHAR(MAX);"
          + NEW_LINE
          + "DECLARE "
          + DYNAMIC_SQL_VARIABLE
          + " NVARCHAR(MAX);"
          + NEW_LINE;

  /**
   * Reads the {@code MS_Description} of a table and of every one of its columns that carries one.
   *
   * <p>A table level property has {@code minor_id} zero, which matches no row of {@code
   * sys.columns}, so the left join yields exactly one row with a null column name for the table
   * itself plus one row per commented column. {@code value} is a {@code sql_variant}, which the
   * driver does not hand back as text on its own, so it is cast here.
   */
  private static final String SELECT_DESCRIPTIONS_SQL =
      "SELECT c.name, CAST(p.value AS NVARCHAR(MAX)) FROM sys.extended_properties p"
          + " INNER JOIN sys.objects o ON p.major_id = o.object_id"
          + " INNER JOIN sys.schemas s ON o.schema_id = s.schema_id"
          + " LEFT JOIN sys.columns c ON c.object_id = o.object_id AND c.column_id = p.minor_id"
          + " WHERE p.class = 1 AND p.name = N'"
          + DESCRIPTION_PROPERTY
          + "' AND s.name = ? AND o.name = ?";

  private String database;
  private SqlServerSchemaOperations schemaOperations;

  /**
   * Escapes a value for use inside a single-quoted SQL Server string literal.
   *
   * <p>Only the quote itself needs doubling. Unlike MySQL, SQL Server does not treat the backslash
   * as an escape character in string literals, so {@link JdbcConnectorUtils#escapeSqlLiteral} must
   * not be used here as it would corrupt values containing backslashes.
   *
   * @param value the literal value to escape
   * @return the escaped literal value, without surrounding quotes
   */
  public static String escapeLiteral(String value) {
    return String.valueOf(value).replace("'", "''");
  }

  /**
   * Wraps an identifier in SQL Server bracket quoting, doubling any embedded closing bracket.
   *
   * @param identifier the identifier to quote
   * @return the quoted identifier
   */
  public static String quote(String identifier) {
    return QUOTED_IDENTIFIER_PREFIX
        + identifier.replace(QUOTED_IDENTIFIER_SUFFIX, QUOTED_IDENTIFIER_SUFFIX + "]")
        + QUOTED_IDENTIFIER_SUFFIX;
  }

  /**
   * Builds a fully qualified {@code [schema].[table]} reference.
   *
   * @param schema the schema name
   * @param table the table name
   * @return the qualified and quoted table reference
   */
  public static String qualifiedTable(String schema, String table) {
    return quote(schema) + "." + quote(table);
  }

  @Override
  public void initialize(
      DataSource dataSource,
      JdbcExceptionConverter exceptionMapper,
      JdbcTypeConverter jdbcTypeConverter,
      JdbcColumnDefaultValueConverter jdbcColumnDefaultValueConverter,
      Map<String, String> conf) {
    super.initialize(
        dataSource, exceptionMapper, jdbcTypeConverter, jdbcColumnDefaultValueConverter, conf);
    database = new JdbcConfig(conf).getJdbcDatabase();
    Preconditions.checkArgument(
        StringUtils.isNotBlank(database),
        "The `jdbc-database` configuration item is mandatory in SQL Server.");
  }

  @Override
  public void setDatabaseOperation(DatabaseOperation databaseOperation) {
    this.schemaOperations = (SqlServerSchemaOperations) databaseOperation;
  }

  @Override
  public void create(
      String schemaName,
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes,
      SortOrder[] sortOrders)
      throws TableAlreadyExistsException {
    if (ArrayUtils.isNotEmpty(sortOrders)) {
      throw new UnsupportedOperationException("SQL Server does not support sort orders.");
    }
    LOG.info("Attempting to create table {} in schema {}", tableName, schemaName);
    try (Connection connection = getConnection(schemaName)) {
      String sql =
          generateCreateTableSql(
              schemaName,
              tableName,
              columns,
              comment,
              properties,
              partitioning,
              distribution,
              indexes);
      JdbcConnectorUtils.executeUpdate(connection, sql);
      LOG.info("Created table {} in schema {} with SQL:\n{}", tableName, schemaName, sql);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  public void rename(String schemaName, String oldTableName, String newTableName)
      throws NoSuchTableException {
    LOG.info(
        "Attempting to rename table {}/{} to {}/{}",
        schemaName,
        oldTableName,
        schemaName,
        newTableName);
    try (Connection connection = getConnection(schemaName)) {
      JdbcConnectorUtils.executeUpdate(
          connection, generateRenameTableSql(schemaName, oldTableName, newTableName));
      LOG.info("Renamed table {}/{} to {}/{}", schemaName, oldTableName, schemaName, newTableName);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  public List<String> listTables(String schemaName) throws NoSuchSchemaException {
    try (Connection connection = getConnection(schemaName)) {
      if (!schemaOperations.schemaExists(connection, schemaName)) {
        throw new NoSuchSchemaException("No such schema: %s", schemaName);
      }
      List<String> names = Lists.newArrayList();
      DatabaseMetaData metaData = connection.getMetaData();
      try (ResultSet tables =
          metaData.getTables(
              connection.getCatalog(), schemaName, null, JdbcConnectorUtils.getTableTypes())) {
        while (tables.next()) {
          if (Objects.equals(tables.getString("TABLE_SCHEM"), schemaName)) {
            names.add(tables.getString("TABLE_NAME"));
          }
        }
      }
      LOG.info("Finished listing tables size {} for schema name {} ", names.size(), schemaName);
      return names;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Generates the {@code CREATE TABLE} statement plus the extended-property calls that carry the
   * table and column comments.
   *
   * @param schemaName the schema the table belongs to
   * @param tableName the table name
   * @param columns the columns of the table
   * @param comment the table comment, which also carries the Gravitino entity id
   * @param properties the table properties, which SQL Server does not persist
   * @param partitioning the partitioning transforms, unsupported by SQL Server
   * @param distribution the distribution, unsupported by SQL Server
   * @param indexes the primary key and unique constraints to declare inline
   * @return the T-SQL batch creating the table
   */
  @VisibleForTesting
  String generateCreateTableSql(
      String schemaName,
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {
    if (ArrayUtils.isNotEmpty(partitioning)) {
      throw new UnsupportedOperationException(
          "Currently we do not support Partitioning in SQL Server");
    }
    Preconditions.checkArgument(
        Distributions.NONE.equals(distribution), "SQL Server does not support distribution");
    if (MapUtils.isNotEmpty(properties)) {
      throw new IllegalArgumentException("Properties are not supported yet");
    }

    String qualified = qualifiedTable(schemaName, tableName);
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("CREATE TABLE ").append(qualified).append(" (").append(NEW_LINE);

    for (int i = 0; i < columns.length; i++) {
      JdbcColumn column = columns[i];
      sqlBuilder.append("    ").append(quote(column.name()));
      appendColumnDefinition(column, sqlBuilder);
      if (i < columns.length - 1) {
        sqlBuilder.append(",").append(NEW_LINE);
      }
    }
    appendIndexesSql(indexes, sqlBuilder);
    sqlBuilder.append(NEW_LINE).append(")");

    if (StringUtils.isNotEmpty(comment)) {
      sqlBuilder
          .append(";")
          .append(NEW_LINE)
          .append(
              tableDescriptionSql(schemaName, tableName, null, comment, false /* assumeExists */));
    }
    Arrays.stream(columns)
        .filter(column -> StringUtils.isNotEmpty(column.comment()))
        .forEach(
            column ->
                sqlBuilder
                    .append(NEW_LINE)
                    .append(
                        tableDescriptionSql(
                            schemaName,
                            tableName,
                            column.name(),
                            column.comment(),
                            false /* assumeExists */)));

    String result = sqlBuilder.toString();
    LOG.info("Generated create table:{} sql: {}", tableName, result);
    return result;
  }

  /**
   * Generates the {@code sp_rename} call that renames a table.
   *
   * @param schemaName the schema the table belongs to
   * @param oldTableName the current table name
   * @param newTableName the desired table name
   * @return the T-SQL statement renaming the table
   */
  @VisibleForTesting
  String generateRenameTableSql(String schemaName, String oldTableName, String newTableName) {
    return String.format(
        "EXEC sp_rename '%s', '%s'",
        escapeLiteral(qualifiedTable(schemaName, oldTableName)), escapeLiteral(newTableName));
  }

  /**
   * Generates the statement that drops a table.
   *
   * @param schemaName the schema the table belongs to
   * @param tableName the table name
   * @return the T-SQL statement dropping the table
   */
  @VisibleForTesting
  String generateDropTableSql(String schemaName, String tableName) {
    return "DROP TABLE " + qualifiedTable(schemaName, tableName);
  }

  /**
   * SQL Server has no recycle bin, so purging is identical to dropping. This overrides the base
   * class hook that already carries the schema.
   *
   * @param schemaName the schema the table belongs to
   * @param tableName the table name
   * @return the T-SQL statement dropping the table
   */
  @Override
  protected String generatePurgeTableSql(String schemaName, String tableName) {
    return generateDropTableSql(schemaName, tableName);
  }

  @Override
  protected void dropTable(String schemaName, String tableName) {
    LOG.info("Attempting to delete table {} from schema {}", tableName, schemaName);
    try (Connection connection = getConnection(schemaName)) {
      JdbcConnectorUtils.executeUpdate(connection, generateDropTableSql(schemaName, tableName));
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected void purgeTable(String schemaName, String tableName) {
    LOG.info("Attempting to purge table {} from schema {}", tableName, schemaName);
    try (Connection connection = getConnection(schemaName)) {
      JdbcConnectorUtils.executeUpdate(connection, generatePurgeTableSql(schemaName, tableName));
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected String generateAlterTableSql(
      String schemaName, String tableName, TableChange... changes) {
    // Not all operations require the original table information, so lazy loading is used here.
    JdbcTable lazyLoadTable = null;
    List<String> alterSql = new ArrayList<>();
    for (TableChange change : changes) {
      if (change instanceof TableChange.UpdateComment) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.add(
            updateCommentDefinition(schemaName, lazyLoadTable, (TableChange.UpdateComment) change));
      } else if (change instanceof TableChange.SetProperty
          || change instanceof TableChange.RemoveProperty) {
        throw new IllegalArgumentException("SQL Server does not support table properties.");
      } else if (change instanceof TableChange.AddColumn) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.addAll(
            addColumnFieldDefinition(schemaName, lazyLoadTable, (TableChange.AddColumn) change));
      } else if (change instanceof TableChange.RenameColumn) {
        alterSql.add(
            renameColumnFieldDefinition(schemaName, tableName, (TableChange.RenameColumn) change));
      } else if (change instanceof TableChange.UpdateColumnDefaultValue) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.add(
            updateColumnDefaultValueDefinition(
                schemaName, lazyLoadTable, (TableChange.UpdateColumnDefaultValue) change));
      } else if (change instanceof TableChange.UpdateColumnType) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.add(
            updateColumnTypeDefinition(
                schemaName, lazyLoadTable, (TableChange.UpdateColumnType) change));
      } else if (change instanceof TableChange.UpdateColumnComment) {
        alterSql.add(
            updateColumnCommentDefinition(
                schemaName, tableName, (TableChange.UpdateColumnComment) change));
      } else if (change instanceof TableChange.UpdateColumnPosition) {
        throw new IllegalArgumentException("SQL Server does not support column position.");
      } else if (change instanceof TableChange.DeleteColumn) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        String deleteColumnSql =
            deleteColumnFieldDefinition(
                schemaName, lazyLoadTable, (TableChange.DeleteColumn) change);
        if (StringUtils.isNotEmpty(deleteColumnSql)) {
          alterSql.add(deleteColumnSql);
        }
      } else if (change instanceof TableChange.UpdateColumnNullability) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        TableChange.UpdateColumnNullability updateColumnNullability =
            (TableChange.UpdateColumnNullability) change;
        validateUpdateColumnNullable(updateColumnNullability, lazyLoadTable);
        alterSql.add(
            updateColumnNullabilityDefinition(schemaName, lazyLoadTable, updateColumnNullability));
      } else if (change instanceof TableChange.AddIndex) {
        alterSql.add(addIndexDefinition(schemaName, tableName, (TableChange.AddIndex) change));
      } else if (change instanceof TableChange.DeleteIndex) {
        alterSql.add(
            deleteIndexDefinition(schemaName, tableName, (TableChange.DeleteIndex) change));
      } else if (change instanceof TableChange.UpdateColumnAutoIncrement) {
        throw new UnsupportedOperationException(
            "SQL Server cannot add or drop IDENTITY on an existing column; "
                + "recreate the table to change auto-increment.");
      } else {
        throw new IllegalArgumentException(
            "Unsupported table change type: " + change.getClass().getName());
      }
    }

    if (alterSql.isEmpty()) {
      return "";
    }

    StringBuilder batch = new StringBuilder();
    if (touchesDefaultConstraint(alterSql)) {
      batch.append(CONSTRAINT_VARIABLE_DECLARATIONS);
    }
    String result = batch.append(String.join(NEW_LINE, alterSql)).toString();
    LOG.info("Generated alter table:{}.{} sql: {}", schemaName, tableName, result);
    return result;
  }

  /**
   * Reports whether a batch needs the default constraint variables declared.
   *
   * <p>Only {@link #dropDefaultConstraintSql} and {@link #restoreDefaultConstraintSql} write the
   * variable name, so its presence identifies the fragments that need the declaration. A schema,
   * table or column that happens to be named after the variable only adds a declaration the batch
   * then leaves unused.
   */
  private static boolean touchesDefaultConstraint(List<String> alterSql) {
    return alterSql.stream().anyMatch(sql -> sql.contains(CONSTRAINT_NAME_VARIABLE));
  }

  @Override
  protected JdbcTable.Builder getTableBuilder(
      ResultSet tablesResult, String schemaName, String tableName) throws SQLException {
    boolean found = false;
    JdbcTable.Builder builder = null;
    while (tablesResult.next() && !found) {
      if (Objects.equals(tablesResult.getString("TABLE_NAME"), tableName)
          && Objects.equals(tablesResult.getString("TABLE_SCHEM"), schemaName)) {
        builder = getBasicJdbcTableInfo(tablesResult);
        found = true;
      }
    }

    if (!found) {
      throw new NoSuchTableException("Table %s does not exist in %s.", tableName, schemaName);
    }
    return builder;
  }

  @Override
  protected JdbcColumn.Builder getColumnBuilder(
      ResultSet columnsResult, String schemaName, String tableName) throws SQLException {
    if (Objects.equals(columnsResult.getString("TABLE_NAME"), tableName)
        && Objects.equals(columnsResult.getString("TABLE_SCHEM"), schemaName)) {
      return getBasicJdbcColumnInfo(columnsResult);
    }
    return null;
  }

  /**
   * Fills in the table and column comments, which the driver does not report.
   *
   * <p>SQL Server keeps descriptions in extended properties, and mssql-jdbc leaves {@code REMARKS}
   * null for tables and columns alike however the connection is configured, so the base
   * implementation reads no comment back at all. That also loses the Gravitino entity id when a
   * comment is later updated, since {@link #updateCommentDefinition} carries the id over from the
   * comment it reads back.
   *
   * @param connection the connection, already switched to the database holding the table
   * @param databaseName the schema the table belongs to
   * @param tableName the table name
   * @param jdbcTableBuilder the builder holding the table as the driver reported it
   * @throws SQLException if the description query fails
   */
  @Override
  protected void correctJdbcTableFields(
      Connection connection,
      String databaseName,
      String tableName,
      JdbcTable.Builder jdbcTableBuilder)
      throws SQLException {
    String tableComment = null;
    Map<String, String> columnComments = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(SELECT_DESCRIPTIONS_SQL)) {
      statement.setString(1, databaseName);
      statement.setString(2, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String description = resultSet.getString(2);
          if (StringUtils.isEmpty(description)) {
            continue;
          }
          String columnName = resultSet.getString(1);
          if (columnName == null) {
            tableComment = description;
          } else {
            columnComments.put(columnName, description);
          }
        }
      }
    }

    jdbcTableBuilder.withComment(tableComment);
    applyColumnComments(jdbcTableBuilder, columnComments);
  }

  @Override
  protected Connection getConnection(String schema) throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setCatalog(database);
    return connection;
  }

  @Override
  protected ResultSet getTable(Connection connection, String schema, String tableName)
      throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getTables(connection.getCatalog(), schema, tableName, null);
  }

  @Override
  protected ResultSet getColumns(Connection connection, String schema, String tableName)
      throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getColumns(connection.getCatalog(), schema, tableName, null);
  }

  @Override
  protected ResultSet getIndexInfo(String schema, String tableName, DatabaseMetaData metaData)
      throws SQLException {
    return metaData.getIndexInfo(
        metaData.getConnection().getCatalog(), schema, tableName, false, false);
  }

  @Override
  protected ResultSet getPrimaryKeys(String schema, String tableName, DatabaseMetaData metaData)
      throws SQLException {
    return metaData.getPrimaryKeys(metaData.getConnection().getCatalog(), schema, tableName);
  }

  /**
   * Reads the auto-increment flag as the string the driver actually reports.
   *
   * <p>mssql-jdbc answers {@code IS_AUTOINCREMENT} with {@code YES}, and {@code
   * ResultSet#getBoolean} on that text yields false, so the base implementation reports every
   * identity column as not auto-incrementing. The MySQL, StarRocks and Doris catalogs override this
   * for the same reason.
   */
  @Override
  protected boolean getAutoIncrementInfo(ResultSet resultSet) throws SQLException {
    return "YES".equalsIgnoreCase(resultSet.getString("IS_AUTOINCREMENT"));
  }

  /**
   * The mssql-jdbc driver reports the fractional seconds of {@code datetime2}/{@code time} through
   * the scale of the column rather than deriving it from the column size.
   */
  @Override
  public Integer calculateDatetimePrecision(String typeName, int columnSize, int scale) {
    switch (typeName.toLowerCase(Locale.ROOT)) {
      case "time":
      case "datetime2":
      case "datetimeoffset":
        return Math.max(scale, 0);
      case "datetime":
        // datetime has a fixed ~3.33ms resolution reported as 0 fractional digits.
        return 3;
      case "smalldatetime":
        return 0;
      default:
        return null;
    }
  }

  @Override
  protected String quoteIdentifier(String identifier) {
    return quote(identifier);
  }

  @Override
  protected String generateCreateTableSql(
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {
    throw new UnsupportedOperationException(
        "SQL Server requires a schema to qualify the table; "
            + "use generateCreateTableSql(schema, table, ...) instead.");
  }

  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    throw new UnsupportedOperationException(
        "SQL Server requires a schema to qualify the table; "
            + "use generateRenameTableSql(schema, oldTable, newTable) instead.");
  }

  @Override
  protected String generateDropTableSql(String tableName) {
    throw new UnsupportedOperationException(
        "SQL Server requires a schema to qualify the table; "
            + "use generateDropTableSql(schema, table) instead.");
  }

  @Override
  protected String generatePurgeTableSql(String tableName) {
    throw new UnsupportedOperationException(
        "SQL Server requires a schema to qualify the table; "
            + "use generatePurgeTableSql(schema, table) instead.");
  }

  private void appendColumnDefinition(JdbcColumn column, StringBuilder sqlBuilder) {
    sqlBuilder.append(SPACE).append(typeConverter.fromGravitino(column.dataType())).append(SPACE);

    if (column.autoIncrement()) {
      if (!Types.allowAutoIncrement(column.dataType())) {
        throw new IllegalArgumentException(
            "Unsupported auto-increment, column: "
                + column.name()
                + ", type: "
                + column.dataType());
      }
      sqlBuilder.append("IDENTITY(1,1) ");
    }

    sqlBuilder.append(column.nullable() ? "NULL " : "NOT NULL ");
    appendDefaultValue(column, sqlBuilder);
  }

  @VisibleForTesting
  static void appendIndexesSql(Index[] indexes, StringBuilder sqlBuilder) {
    if (indexes == null) {
      return;
    }
    for (Index index : indexes) {
      String fieldStr = getIndexFieldStr(index.fieldNames());
      sqlBuilder.append(",").append(NEW_LINE);
      switch (index.type()) {
        case PRIMARY_KEY:
          if (StringUtils.isNotEmpty(index.name())) {
            sqlBuilder.append("CONSTRAINT ").append(quote(index.name())).append(SPACE);
          }
          sqlBuilder.append("PRIMARY KEY (").append(fieldStr).append(")");
          break;
        case UNIQUE_KEY:
          if (StringUtils.isNotEmpty(index.name())) {
            sqlBuilder.append("CONSTRAINT ").append(quote(index.name())).append(SPACE);
          }
          sqlBuilder.append("UNIQUE (").append(fieldStr).append(")");
          break;
        default:
          throw new IllegalArgumentException("SQL Server doesn't support index : " + index.type());
      }
    }
  }

  protected static String getIndexFieldStr(String[][] fieldNames) {
    return Arrays.stream(fieldNames)
        .map(
            colNames -> {
              if (colNames.length > 1) {
                throw new IllegalArgumentException(
                    "Index does not support complex fields in SQL Server");
              }
              return quote(colNames[0]);
            })
        .collect(Collectors.joining(", "));
  }

  private String updateCommentDefinition(
      String schemaName, JdbcTable jdbcTable, TableChange.UpdateComment change) {
    String newComment = change.getNewComment();
    if (null == StringIdentifier.fromComment(newComment)) {
      // Detect and add Gravitino id.
      if (StringUtils.isNotEmpty(jdbcTable.comment())) {
        StringIdentifier identifier = StringIdentifier.fromComment(jdbcTable.comment());
        if (null != identifier) {
          newComment = StringIdentifier.addToComment(identifier, newComment);
        }
      }
    }
    return tableDescriptionSql(schemaName, jdbcTable.name(), null, newComment, true);
  }

  private List<String> addColumnFieldDefinition(
      String schemaName, JdbcTable lazyLoadTable, TableChange.AddColumn addColumn) {
    if (addColumn.fieldName().length > 1) {
      throw new UnsupportedOperationException(NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    if (!(addColumn.getPosition() instanceof TableChange.Default)) {
      throw new IllegalArgumentException("SQL Server does not support column position.");
    }

    List<String> result = new ArrayList<>();
    String col = addColumn.fieldName()[0];
    String qualified = qualifiedTable(schemaName, lazyLoadTable.name());

    StringBuilder columnDefinition = new StringBuilder();
    columnDefinition
        .append(ALTER_TABLE)
        .append(qualified)
        .append(SPACE)
        .append("ADD ")
        .append(quote(col))
        .append(SPACE)
        .append(typeConverter.fromGravitino(addColumn.getDataType()))
        .append(SPACE);

    if (addColumn.isAutoIncrement()) {
      if (!Types.allowAutoIncrement(addColumn.getDataType())) {
        throw new IllegalArgumentException(
            "Unsupported auto-increment, column: "
                + Arrays.toString(addColumn.getFieldName())
                + ", type: "
                + addColumn.getDataType());
      }
      // An IDENTITY column cannot be nullable in SQL Server.
      columnDefinition.append("IDENTITY(1,1) NOT NULL");
    } else {
      columnDefinition.append(addColumn.isNullable() ? "NULL" : "NOT NULL");
      if (!Column.DEFAULT_VALUE_NOT_SET.equals(addColumn.getDefaultValue())) {
        columnDefinition
            .append(" CONSTRAINT ")
            .append(quote("DF_" + lazyLoadTable.name() + "_" + col))
            .append(" DEFAULT ")
            .append(columnDefaultValueConverter.fromGravitino(addColumn.getDefaultValue()));
      }
    }

    result.add(columnDefinition.append(";").toString());

    if (StringUtils.isNotEmpty(addColumn.getComment())) {
      result.add(
          tableDescriptionSql(
              schemaName, lazyLoadTable.name(), col, addColumn.getComment(), false));
    }
    return result;
  }

  private String renameColumnFieldDefinition(
      String schemaName, String tableName, TableChange.RenameColumn renameColumn) {
    if (renameColumn.fieldName().length > 1) {
      throw new UnsupportedOperationException(NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String target =
        qualifiedTable(schemaName, tableName) + "." + quote(renameColumn.fieldName()[0]);
    return String.format(
        "EXEC sp_rename '%s', '%s', 'COLUMN';",
        escapeLiteral(target), escapeLiteral(renameColumn.getNewName()));
  }

  private String deleteColumnFieldDefinition(
      String schemaName, JdbcTable table, TableChange.DeleteColumn deleteColumn) {
    if (deleteColumn.fieldName().length > 1) {
      throw new UnsupportedOperationException(NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = deleteColumn.fieldName()[0];
    boolean colExists =
        Arrays.stream(table.columns()).anyMatch(s -> StringUtils.equals(col, s.name()));
    if (!colExists) {
      if (BooleanUtils.isTrue(deleteColumn.getIfExists())) {
        return "";
      }
      throw new IllegalArgumentException("Delete column does not exist: " + col);
    }
    String qualified = qualifiedTable(schemaName, table.name());
    return dropDefaultConstraintSql(qualified, col)
        + ALTER_TABLE
        + qualified
        + " DROP COLUMN "
        + quote(col)
        + ";";
  }

  /**
   * Clears the constraint variables before a fragment reads them.
   *
   * <p>{@code SELECT @v = c FROM ...} leaves {@code @v} untouched when the query matches no row, so
   * a fragment that follows one which did find a constraint would otherwise act on that constraint
   * a second time.
   */
  private static String resetConstraintVariablesSql() {
    return "SET "
        + CONSTRAINT_NAME_VARIABLE
        + " = NULL;"
        + NEW_LINE
        + "SET "
        + CONSTRAINT_DEFINITION_VARIABLE
        + " = NULL;"
        + NEW_LINE;
  }

  /**
   * Builds the statements that read the default constraint of a column and drop it, if the column
   * has one.
   *
   * <p>SQL Server binds a column default through a named constraint object whose name the server
   * generates, and refuses an {@code ALTER COLUMN} or a {@code DROP COLUMN} on a column that still
   * carries one with error 5074 or 4922. Every change that rewrites or removes a column therefore
   * has to take the constraint off first, and because the name is unknown until the batch runs it
   * has to be looked up from {@code sys.default_constraints}.
   *
   * <p>The drop is assembled into a variable before it runs. {@code EXEC} accepts a string built
   * from literals and variables joined by {@code +}, but not a function call, so inlining {@code
   * QUOTENAME(...)} into the {@code EXEC} argument is a syntax error. {@link
   * SqlServerSchemaOperations#generateDropDatabaseSql} builds its dynamic statement the same way.
   *
   * @param qualifiedTable the {@code [schema].[table]} reference, already quoted
   * @param column the column name, not quoted
   * @return the statements that drop the column default, terminated by a newline
   */
  private static String dropDefaultConstraintSql(String qualifiedTable, String column) {
    return resetConstraintVariablesSql()
        + "SELECT "
        + CONSTRAINT_NAME_VARIABLE
        + " = dc.name, "
        + CONSTRAINT_DEFINITION_VARIABLE
        + " = dc.definition"
        + NEW_LINE
        + "  FROM sys.default_constraints dc"
        + NEW_LINE
        + "  INNER JOIN sys.columns c ON dc.parent_object_id = c.object_id"
        + " AND dc.parent_column_id = c.column_id"
        + NEW_LINE
        + String.format(
            "  WHERE dc.parent_object_id = OBJECT_ID(N'%s') AND c.name = N'%s';",
            escapeLiteral(qualifiedTable), escapeLiteral(column))
        + NEW_LINE
        + "SET "
        + DYNAMIC_SQL_VARIABLE
        + " = N'"
        + escapeLiteral(ALTER_TABLE + qualifiedTable + " DROP CONSTRAINT ")
        + "' + QUOTENAME("
        + CONSTRAINT_NAME_VARIABLE
        + ");"
        + NEW_LINE
        + "IF "
        + CONSTRAINT_NAME_VARIABLE
        + " IS NOT NULL"
        + NEW_LINE
        + "  EXEC ("
        + DYNAMIC_SQL_VARIABLE
        + ");"
        + NEW_LINE;
  }

  /**
   * Builds the statement that reattaches the default constraint a fragment dropped.
   *
   * <p>{@code sys.default_constraints.definition} holds the expression exactly as the server
   * accepted it, so adding it back under a new server generated name restores the column. A change
   * that replaces the default attaches the replacement instead and does not call this.
   *
   * @param qualifiedTable the {@code [schema].[table]} reference, already quoted
   * @param column the column name, not quoted
   * @return the statement that restores the column default, terminated by a newline
   */
  private static String restoreDefaultConstraintSql(String qualifiedTable, String column) {
    return "SET "
        + DYNAMIC_SQL_VARIABLE
        + " = N'"
        + escapeLiteral(ALTER_TABLE + qualifiedTable + " ADD DEFAULT ")
        + "' + "
        + CONSTRAINT_DEFINITION_VARIABLE
        + " + N' FOR "
        + escapeLiteral(quote(column))
        + "';"
        + NEW_LINE
        + "IF "
        + CONSTRAINT_NAME_VARIABLE
        + " IS NOT NULL"
        + NEW_LINE
        + "  EXEC ("
        + DYNAMIC_SQL_VARIABLE
        + ");"
        + NEW_LINE;
  }

  /**
   * Replaces the default of a column, or removes it when the change carries no new value.
   *
   * <p>The existing constraint has to come off first whatever the outcome, so the fragment this
   * returns starts with {@link #dropDefaultConstraintSql}.
   */
  private String updateColumnDefaultValueDefinition(
      String schemaName, JdbcTable jdbcTable, TableChange.UpdateColumnDefaultValue change) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = change.fieldName()[0];
    requireColumn(jdbcTable, col);

    String qualified = qualifiedTable(schemaName, jdbcTable.name());
    StringBuilder batch = new StringBuilder(dropDefaultConstraintSql(qualified, col));

    String newDefault = columnDefaultValueConverter.fromGravitino(change.getNewDefaultValue());
    if (newDefault == null) {
      // The change unsets the default, so dropping the constraint is the whole operation.
      return batch.toString();
    }
    return batch
        .append(ALTER_TABLE)
        .append(qualified)
        .append(" ADD DEFAULT ")
        .append(newDefault)
        .append(" FOR ")
        .append(quote(col))
        .append(";")
        .toString();
  }

  private String updateColumnTypeDefinition(
      String schemaName, JdbcTable jdbcTable, TableChange.UpdateColumnType change) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = change.fieldName()[0];
    JdbcColumn column = requireColumn(jdbcTable, col);

    // The default is dropped rather than reattached, matching what the MySQL catalog does when a
    // column type changes: the old expression was written against the old type and SQL Server
    // would rebind it verbatim.
    String qualified = qualifiedTable(schemaName, jdbcTable.name());
    return dropDefaultConstraintSql(qualified, col)
        + alterColumnSql(
            qualified,
            col,
            typeConverter.fromGravitino(change.getNewDataType()),
            column.nullable());
  }

  private String updateColumnCommentDefinition(
      String schemaName, String tableName, TableChange.UpdateColumnComment change) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    return tableDescriptionSql(
        schemaName, tableName, change.fieldName()[0], change.getNewComment(), true);
  }

  private String updateColumnNullabilityDefinition(
      String schemaName, JdbcTable jdbcTable, TableChange.UpdateColumnNullability change) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = change.fieldName()[0];
    JdbcColumn column = requireColumn(jdbcTable, col);

    // Only nullability changes here, so the default goes back on afterwards, matching what the
    // MySQL catalog does with the same change.
    String qualified = qualifiedTable(schemaName, jdbcTable.name());
    return dropDefaultConstraintSql(qualified, col)
        + alterColumnSql(
            qualified, col, typeConverter.fromGravitino(column.dataType()), change.nullable())
        + NEW_LINE
        + restoreDefaultConstraintSql(qualified, col);
  }

  /**
   * Builds an {@code ALTER COLUMN}, which replaces the whole column definition and therefore has to
   * restate the nullability even when it is not what changed.
   */
  private static String alterColumnSql(
      String qualifiedTable, String column, String dataType, boolean nullable) {
    return ALTER_TABLE
        + qualifiedTable
        + " ALTER COLUMN "
        + quote(column)
        + SPACE
        + dataType
        + SPACE
        + (nullable ? "NULL" : "NOT NULL")
        + ";";
  }

  @VisibleForTesting
  static String addIndexDefinition(
      String schemaName, String tableName, TableChange.AddIndex addIndex) {
    StringBuilder sqlBuilder =
        new StringBuilder(ALTER_TABLE + qualifiedTable(schemaName, tableName) + " ADD ");
    if (StringUtils.isNotEmpty(addIndex.getName())) {
      sqlBuilder.append("CONSTRAINT ").append(quote(addIndex.getName())).append(SPACE);
    }
    switch (addIndex.getType()) {
      case PRIMARY_KEY:
        sqlBuilder.append("PRIMARY KEY ");
        break;
      case UNIQUE_KEY:
        sqlBuilder.append("UNIQUE ");
        break;
      default:
        throw new IllegalArgumentException("Unsupported index type: " + addIndex.getType());
    }
    sqlBuilder
        .append("(")
        .append(
            Arrays.stream(addIndex.getFieldNames())
                .map(
                    fieldNames -> {
                      if (fieldNames.length > 1) {
                        throw new IllegalArgumentException(
                            "Index does not support complex fields in SQL Server");
                      }
                      return quote(fieldNames[0]);
                    })
                .collect(Collectors.joining(", ")))
        .append(");");
    return sqlBuilder.toString();
  }

  @VisibleForTesting
  static String deleteIndexDefinition(
      String schemaName, String tableName, TableChange.DeleteIndex deleteIndex) {
    return ALTER_TABLE
        + qualifiedTable(schemaName, tableName)
        + " DROP CONSTRAINT "
        + quote(deleteIndex.getName())
        + ";";
  }

  /**
   * Builds an {@code sp_addextendedproperty} / {@code sp_updateextendedproperty} call carrying an
   * {@code MS_Description} for a table or one of its columns.
   *
   * <p>SQL Server rejects {@code sp_addextendedproperty} when the property already exists and
   * {@code sp_updateextendedproperty} when it does not, so the generated batch branches on
   * existence when {@code assumeExists} cannot be relied upon.
   *
   * @param schemaName the schema the table belongs to
   * @param tableName the table name
   * @param columnName the column name, or {@code null} to target the table itself
   * @param description the description text to store
   * @param existsAware whether to emit an existence check so the statement is idempotent
   * @return the T-SQL statement storing the description
   */
  private String tableDescriptionSql(
      String schemaName,
      String tableName,
      String columnName,
      String description,
      boolean existsAware) {
    String levelClause =
        String.format(
            "@level0type = N'SCHEMA', @level0name = N'%s', @level1type = N'TABLE', @level1name = N'%s'",
            escapeLiteral(schemaName), escapeLiteral(tableName));
    if (columnName != null) {
      levelClause +=
          String.format(
              ", @level2type = N'COLUMN', @level2name = N'%s'", escapeLiteral(columnName));
    }

    String addSql =
        String.format(
            "EXEC sp_addextendedproperty @name = N'%s', @value = N'%s', %s",
            DESCRIPTION_PROPERTY, escapeLiteral(description), levelClause);
    if (!existsAware) {
      return addSql + ";";
    }

    String updateSql =
        String.format(
            "EXEC sp_updateextendedproperty @name = N'%s', @value = N'%s', %s",
            DESCRIPTION_PROPERTY, escapeLiteral(description), levelClause);
    return String.format(
        "IF EXISTS (SELECT 1 FROM sys.extended_properties WHERE name = N'%s'%s)%s  %s%sELSE%s  %s",
        DESCRIPTION_PROPERTY,
        existsPredicate(schemaName, tableName, columnName),
        NEW_LINE,
        updateSql,
        NEW_LINE,
        NEW_LINE,
        addSql);
  }

  private String existsPredicate(String schemaName, String tableName, String columnName) {
    String objectId =
        String.format("OBJECT_ID(N'%s')", escapeLiteral(qualifiedTable(schemaName, tableName)));
    StringBuilder predicate = new StringBuilder(String.format(" AND major_id = %s", objectId));
    if (columnName == null) {
      predicate.append(" AND minor_id = 0");
    } else {
      predicate.append(
          String.format(
              " AND minor_id = COLUMNPROPERTY(%s, N'%s', 'ColumnId')",
              objectId, escapeLiteral(columnName)));
    }
    return predicate.toString();
  }

  private JdbcColumn requireColumn(JdbcTable jdbcTable, String col) {
    return (JdbcColumn)
        Arrays.stream(jdbcTable.columns())
            .filter(c -> c.name().equals(col))
            .findFirst()
            .orElseThrow(() -> new NoSuchColumnException("Column %s does not exist.", col));
  }
}
