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
package org.apache.gravitino.catalog.sqlserver.integration.test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.sqlserver.integration.test.service.SqlServerService;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.platform.commons.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test for the SQL Server JDBC catalog.
 *
 * <p>These tests run against a SQL Server instance you provide rather than one they start. Set the
 * following environment variables to enable them:
 *
 * <ul>
 *   <li>{@code GRAVITINO_TEST_CLOUD_IT} - must be {@code true}
 *   <li>{@code GRAVITINO_SQLSERVER_JDBC_URL} - for example {@code
 *       jdbc:sqlserver://host:1433;databaseName=master;encrypt=false}. The {@code databaseName} is
 *       what the catalog needs as {@code jdbc-database}, so a URL without it disables the tests.
 *   <li>{@code GRAVITINO_SQLSERVER_USERNAME} - an account able to create and drop schemas in that
 *       database
 *   <li>{@code GRAVITINO_SQLSERVER_PASSWORD}
 * </ul>
 *
 * <p>Run them with {@code ./gradlew :catalogs:catalog-jdbc-sqlserver:test -PskipTests}, exporting
 * the variables in the same shell. The Gradle daemon captures the environment when it starts, so
 * pass {@code --no-daemon} or stop a running daemon first.
 *
 * <p>Each test drives the catalog through the REST client and then reads SQL Server's catalog views
 * directly, so a statement the catalog failed to issue is caught rather than echoed back. That
 * matters most for {@code alterTable}, which builds one T-SQL batch whose statements SQL Server can
 * silently stop applying part way through.
 */
@EnabledIfEnvironmentVariable(named = "GRAVITINO_TEST_CLOUD_IT", matches = "true")
@EnabledIf(
    value = "sqlServerIsConfigured",
    disabledReason =
        "Set GRAVITINO_SQLSERVER_JDBC_URL with a databaseName, plus the username and password")
@TestInstance(Lifecycle.PER_CLASS)
public class CatalogSqlServerIT extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogSqlServerIT.class);

  private static final String PROVIDER = "jdbc-sqlserver";
  private static final String DRIVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver";

  /**
   * Reads the database a SQL Server URL binds to, which the catalog needs as {@code jdbc-database}.
   */
  private static final Pattern DATABASE_NAME = Pattern.compile("(?i);databaseName=([^;]+)");

  private static final String TABLE_COMMENT = "sqlserver_it_table_comment";
  private static final String UPDATED_TABLE_COMMENT = "sqlserver_it_updated_table_comment";
  private static final String SCHEMA_COMMENT = "sqlserver_it_schema_comment";

  private static final String SQLSERVER_JDBC_URL = System.getenv("GRAVITINO_SQLSERVER_JDBC_URL");
  private static final String SQLSERVER_USERNAME = System.getenv("GRAVITINO_SQLSERVER_USERNAME");
  private static final String SQLSERVER_PASSWORD = System.getenv("GRAVITINO_SQLSERVER_PASSWORD");
  private static final String SQLSERVER_DATABASE = extractDatabase(SQLSERVER_JDBC_URL);

  private final String metalakeName = GravitinoITUtils.genRandomName("sqlserver_it_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("sqlserver_it_catalog");
  private final String schemaName = GravitinoITUtils.genRandomName("sqlserver_it_schema");
  private final String commentedSchemaName = GravitinoITUtils.genRandomName("sqlserver_it_comment");
  private final String temporarySchemaName = GravitinoITUtils.genRandomName("sqlserver_it_temp");
  private final String tableName = GravitinoITUtils.genRandomName("sqlserver_it_table");
  private final String renamedTableName = GravitinoITUtils.genRandomName("sqlserver_it_renamed");
  private final String purgedTableName = GravitinoITUtils.genRandomName("sqlserver_it_purged");

  private GravitinoMetalake metalake;
  private Catalog catalog;
  private SqlServerService sqlServerService;

  /** Reports whether the environment carries everything a SQL Server instance needs. */
  protected static boolean sqlServerIsConfigured() {
    return StringUtils.isNotBlank(SQLSERVER_JDBC_URL)
        && StringUtils.isNotBlank(SQLSERVER_DATABASE)
        && StringUtils.isNotBlank(SQLSERVER_USERNAME)
        && StringUtils.isNotBlank(SQLSERVER_PASSWORD);
  }

  @BeforeAll
  public void startup() throws IOException {
    sqlServerService =
        new SqlServerService(SQLSERVER_JDBC_URL, SQLSERVER_USERNAME, SQLSERVER_PASSWORD);
    createMetalake();
    createCatalog();
    createSchema();
  }

  @AfterAll
  public void stop() {
    try {
      // A run that fails part way leaves schemas and their tables behind in the database.
      dropSchemaQuietly(commentedSchemaName);
      dropSchemaQuietly(temporarySchemaName);
      dropSchemaQuietly(schemaName);
      metalake.dropCatalog(catalogName);
      client.dropMetalake(metalakeName);
    } catch (Exception e) {
      LOG.error("Failed to stop.", e);
    } finally {
      sqlServerService.close();
    }
  }

  @AfterEach
  public void resetSchema() {
    catalog.asSchemas().dropSchema(schemaName, true);
    createSchema();
  }

  /** Covers schema enumeration, the schema comment SQL Server stores, and dropping. */
  @Test
  void testOperationSqlServerSchema() {
    SupportsSchemas schemas = catalog.asSchemas();

    Set<String> reported = Sets.newHashSet(schemas.listSchemas());
    List<String> inDatabase = sqlServerService.listSchemas();

    Assertions.assertTrue(reported.contains(schemaName));
    Assertions.assertTrue(sqlServerService.schemaExists(schemaName));
    // dbo is an ordinary user schema and has to stay visible.
    Assertions.assertTrue(reported.contains("dbo"));

    Set<String> reportedFolded = fold(reported);
    Set<String> inDatabaseFolded = fold(inDatabase);
    Assertions.assertTrue(inDatabaseFolded.containsAll(reportedFolded));

    // These exist in every SQL Server database and belong to it, so a catalog that enumerated the
    // driver's schema list unfiltered would surface them.
    Assertions.assertTrue(inDatabaseFolded.contains("sys"));
    Assertions.assertTrue(inDatabaseFolded.contains("information_schema"));
    Assertions.assertFalse(reportedFolded.contains("sys"));
    Assertions.assertFalse(reportedFolded.contains("information_schema"));
    Assertions.assertFalse(reportedFolded.contains("guest"));
    // The driver reports the fixed database roles as schemas too, and they own no tables.
    Assertions.assertFalse(reportedFolded.contains("db_owner"));
    Assertions.assertFalse(reportedFolded.contains("db_denydatawriter"));

    Assertions.assertThrows(
        SchemaAlreadyExistsException.class,
        () -> schemas.createSchema(schemaName, null, Collections.emptyMap()));

    // SQL Server stores a description as an MS_Description extended property on the schema.
    Schema commented =
        schemas.createSchema(commentedSchemaName, SCHEMA_COMMENT, Collections.emptyMap());
    Assertions.assertEquals(SCHEMA_COMMENT, commented.comment());
    Assertions.assertEquals(SCHEMA_COMMENT, schemas.loadSchema(commentedSchemaName).comment());
    Assertions.assertTrue(
        sqlServerService.schemaComment(commentedSchemaName).contains(SCHEMA_COMMENT));

    Assertions.assertFalse(schemas.schemaExists(temporarySchemaName));
    schemas.createSchema(temporarySchemaName, null, Collections.emptyMap());
    Assertions.assertTrue(schemas.schemaExists(temporarySchemaName));
    Assertions.assertTrue(sqlServerService.schemaExists(temporarySchemaName));
    Assertions.assertTrue(schemas.dropSchema(temporarySchemaName, false));
    Assertions.assertFalse(schemas.schemaExists(temporarySchemaName));
    Assertions.assertFalse(sqlServerService.schemaExists(temporarySchemaName));
    Assertions.assertThrows(
        NoSuchSchemaException.class, () -> schemas.loadSchema(temporarySchemaName));
    Assertions.assertFalse(schemas.dropSchema(temporarySchemaName, false));

    // SQL Server has no DROP SCHEMA ... CASCADE, so the catalog builds one, but only when asked; a
    // non-cascade drop of a schema still holding tables has to fail and destroy nothing.
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            createColumns(),
            TABLE_COMMENT,
            Collections.emptyMap());
    Assertions.assertThrows(RuntimeException.class, () -> schemas.dropSchema(schemaName, false));
    Assertions.assertTrue(schemas.schemaExists(schemaName));
    Assertions.assertTrue(sqlServerService.tableExists(schemaName, tableName));
  }

  /** Creates a table holding every type the catalog maps, and reads it back from SQL Server. */
  @Test
  void testCreateAndLoadSqlServerTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    Column[] columns = createColumns();

    Table created =
        tableCatalog.createTable(
            tableIdent,
            columns,
            TABLE_COMMENT,
            Collections.emptyMap(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0],
            createIndexes());
    Assertions.assertEquals(tableName, created.name());
    Assertions.assertTrue(tableCatalog.tableExists(tableIdent));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(TABLE_COMMENT, loaded.comment());
    Assertions.assertEquals(
        Arrays.stream(columns).map(Column::name).collect(Collectors.toList()),
        Arrays.stream(loaded.columns()).map(Column::name).collect(Collectors.toList()));

    // Unlike a catalog that has to widen or substitute types, SQL Server holds every type the
    // catalog maps, so each column has to come back as the one that was submitted.
    Map<String, Column> byName = indexByName(loaded);
    for (Column expected : columns) {
      if (expected.name().equals("c_curts")) {
        continue;
      }
      ITUtils.assertColumn(expected, byName.get(expected.name()));
    }
    Assertions.assertTrue(
        Arrays.stream(loaded.columns())
            .noneMatch(column -> column.dataType() instanceof Types.ExternalType),
        "a column whose type the catalog could not map came back as an ExternalType");
    assertDefaultsCarryColumnType(loaded);

    // A datetime2 declared without a precision is datetime2(7) in SQL Server, so the column reads
    // back wider than the type submitted for it.
    Column currentTimestampColumn = byName.get("c_curts");
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(7), currentTimestampColumn.dataType());
    Assertions.assertEquals(
        Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, currentTimestampColumn.defaultValue());

    Assertions.assertEquals(2, loaded.index().length);

    // The DDL really landed, rather than the API echoing back what it was handed.
    Assertions.assertTrue(sqlServerService.tableExists(schemaName, tableName));
    Assertions.assertTrue(
        sqlServerService.tableComment(schemaName, tableName).contains(TABLE_COMMENT));
    Assertions.assertEquals(
        "identity pk", sqlServerService.columnComment(schemaName, tableName, "c_id"));
    Assertions.assertEquals(
        "a boolean", sqlServerService.columnComment(schemaName, tableName, "c_bit"));
    Assertions.assertTrue(sqlServerService.columnIsIdentity(schemaName, tableName, "c_id"));
    Assertions.assertEquals("bigint", sqlServerService.columnType(schemaName, tableName, "c_id"));
    Assertions.assertFalse(sqlServerService.columnIsNullable(schemaName, tableName, "c_notnull"));
    Assertions.assertEquals("int", sqlServerService.columnType(schemaName, tableName, "c_int"));
    Assertions.assertEquals(
        "nvarchar", sqlServerService.columnType(schemaName, tableName, "c_string"));
    Assertions.assertTrue(
        sqlServerService.columnDefault(schemaName, tableName, "c_int").contains("42"));
    Assertions.assertEquals(
        new HashSet<>(Arrays.asList("pk_e2e", "uk_e2e")),
        new HashSet<>(sqlServerService.listIndexNames(schemaName, tableName)));
  }

  /** Applies one batch of changes and checks each of them separately against SQL Server. */
  @Test
  void testAlterSqlServerTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    tableCatalog.createTable(
        tableIdent,
        createColumns(),
        TABLE_COMMENT,
        Collections.emptyMap(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0],
        createIndexes());

    Table altered =
        tableCatalog.alterTable(
            tableIdent,
            TableChange.addColumn(new String[] {"c_added"}, Types.VarCharType.of(50), "added col"),
            TableChange.updateColumnComment(new String[] {"c_int"}, "updated comment"),
            TableChange.updateColumnType(new String[] {"c_short"}, Types.IntegerType.get()),
            TableChange.updateColumnNullability(new String[] {"c_bit"}, false),
            TableChange.updateColumnNullability(new String[] {"c_char"}, false),
            TableChange.updateColumnDefaultValue(
                new String[] {"c_varchar"}, Literals.varcharLiteral(200, "changed")),
            TableChange.renameColumn(new String[] {"c_float"}, "c_float_renamed"),
            TableChange.deleteColumn(new String[] {"c_real"}, true),
            TableChange.updateComment(UPDATED_TABLE_COMMENT));
    Assertions.assertEquals(createColumns().length, altered.columns().length);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertTrue(loaded.comment().contains(UPDATED_TABLE_COMMENT));

    Map<String, Column> byName = indexByName(loaded);
    Assertions.assertTrue(byName.containsKey("c_added"));
    Assertions.assertTrue(byName.containsKey("c_float_renamed"));
    Assertions.assertFalse(byName.containsKey("c_real"));

    Assertions.assertEquals(Types.VarCharType.of(50), byName.get("c_added").dataType());
    Assertions.assertEquals("added col", byName.get("c_added").comment());
    Assertions.assertEquals(Literals.NULL, byName.get("c_added").defaultValue());
    Assertions.assertEquals("updated comment", byName.get("c_int").comment());
    Assertions.assertEquals(Types.IntegerType.get(), byName.get("c_short").dataType());
    Assertions.assertEquals(Literals.integerLiteral(42), byName.get("c_int").defaultValue());
    Assertions.assertEquals(
        Literals.varcharLiteral(200, "changed"), byName.get("c_varchar").defaultValue());
    Assertions.assertEquals(
        Literals.doubleLiteral(2.5d), byName.get("c_float_renamed").defaultValue());

    // ALTER COLUMN cannot run while a default constraint depends on the column, so the batch has to
    // drop that constraint and put it back. Losing it silently is what this locks down.
    Column bit = byName.get("c_bit");
    Assertions.assertFalse(bit.nullable());
    Assertions.assertEquals(Literals.booleanLiteral(false), bit.defaultValue());
    Column fixedChar = byName.get("c_char");
    Assertions.assertFalse(fixedChar.nullable());
    Assertions.assertEquals(
        Literals.of("abc", Types.FixedCharType.of(10)), fixedChar.defaultValue());
    // Widening a column has no constraint to restore, and SQL Server drops its default for good.
    Assertions.assertEquals(Literals.NULL, byName.get("c_short").defaultValue());
    assertDefaultsCarryColumnType(loaded);

    Assertions.assertTrue(
        sqlServerService.tableComment(schemaName, tableName).contains(UPDATED_TABLE_COMMENT));
    Assertions.assertEquals(
        "updated comment", sqlServerService.columnComment(schemaName, tableName, "c_int"));
    Assertions.assertEquals(
        "added col", sqlServerService.columnComment(schemaName, tableName, "c_added"));
    Assertions.assertFalse(sqlServerService.columnIsNullable(schemaName, tableName, "c_bit"));
    Assertions.assertFalse(sqlServerService.columnIsNullable(schemaName, tableName, "c_char"));
    Assertions.assertNotNull(sqlServerService.columnDefault(schemaName, tableName, "c_bit"));
    Assertions.assertNotNull(sqlServerService.columnDefault(schemaName, tableName, "c_char"));
    Assertions.assertTrue(
        sqlServerService.columnDefault(schemaName, tableName, "c_varchar").contains("changed"));
    Assertions.assertNull(sqlServerService.columnDefault(schemaName, tableName, "c_short"));
    Assertions.assertEquals("int", sqlServerService.columnType(schemaName, tableName, "c_short"));
    Assertions.assertTrue(sqlServerService.columnExists(schemaName, tableName, "c_added"));
    Assertions.assertTrue(sqlServerService.columnExists(schemaName, tableName, "c_float_renamed"));
    Assertions.assertFalse(sqlServerService.columnExists(schemaName, tableName, "c_real"));
  }

  /** Adds a column whose default holds an apostrophe, which has to survive a T-SQL literal. */
  @Test
  void testColumnDefaultHoldingApostrophe() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    tableCatalog.createTable(tableIdent, createColumns(), TABLE_COMMENT, Collections.emptyMap());

    tableCatalog.alterTable(
        tableIdent,
        TableChange.addColumn(
            new String[] {"c_quote"},
            Types.VarCharType.of(20),
            Literals.varcharLiteral(20, "it's")));

    Column quoted = indexByName(tableCatalog.loadTable(tableIdent)).get("c_quote");
    Assertions.assertEquals(Types.VarCharType.of(20), quoted.dataType());
    Assertions.assertEquals(Literals.varcharLiteral(20, "it's"), quoted.defaultValue());
    Assertions.assertTrue(
        sqlServerService.columnDefault(schemaName, tableName, "c_quote").contains("it''s"));
  }

  /** Renames a table, lists it under its new name and drops it. */
  @Test
  void testRenameAndDropSqlServerTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    NameIdentifier renamedIdent = NameIdentifier.of(schemaName, renamedTableName);

    tableCatalog.createTable(tableIdent, createColumns(), TABLE_COMMENT, Collections.emptyMap());

    tableCatalog.alterTable(tableIdent, TableChange.rename(renamedTableName));
    Assertions.assertTrue(tableCatalog.tableExists(renamedIdent));
    Assertions.assertFalse(tableCatalog.tableExists(tableIdent));
    Assertions.assertTrue(sqlServerService.tableExists(schemaName, renamedTableName));
    Assertions.assertFalse(sqlServerService.tableExists(schemaName, tableName));

    Set<String> listed =
        Arrays.stream(tableCatalog.listTables(Namespace.of(schemaName)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertEquals(Collections.singleton(renamedTableName), listed);

    Assertions.assertTrue(tableCatalog.dropTable(renamedIdent));
    Assertions.assertFalse(tableCatalog.tableExists(renamedIdent));
    Assertions.assertFalse(sqlServerService.tableExists(schemaName, renamedTableName));
    Assertions.assertFalse(tableCatalog.dropTable(renamedIdent));
  }

  /** Checks that purging a table leaves nothing behind in SQL Server. */
  @Test
  void testPurgeSqlServerTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier purgeIdent = NameIdentifier.of(schemaName, purgedTableName);

    tableCatalog.createTable(
        purgeIdent,
        new Column[] {
          Column.of("c_id", Types.LongType.get(), null, false, false, Column.DEFAULT_VALUE_NOT_SET)
        },
        "a table to purge",
        Collections.emptyMap());
    Assertions.assertTrue(sqlServerService.tableExists(schemaName, purgedTableName));

    Assertions.assertTrue(tableCatalog.purgeTable(purgeIdent));
    Assertions.assertFalse(tableCatalog.tableExists(purgeIdent));
    Assertions.assertFalse(sqlServerService.tableExists(schemaName, purgedTableName));
  }

  /**
   * Returns the {@code databaseName} a SQL Server URL binds to, or {@code null} if it names none.
   */
  private static String extractDatabase(String url) {
    if (StringUtils.isBlank(url)) {
      return null;
    }
    Matcher matcher = DATABASE_NAME.matcher(url);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static Set<String> fold(Set<String> names) {
    return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
  }

  private static Set<String> fold(List<String> names) {
    return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
  }

  private void createMetalake() {
    Assertions.assertEquals(0, client.listMetalakes().length);
    client.createMetalake(metalakeName, "comment", Collections.emptyMap());
    metalake = client.loadMetalake(metalakeName);
    Assertions.assertEquals(metalakeName, metalake.name());
  }

  private void createCatalog() {
    Catalog created =
        metalake.createCatalog(
            catalogName, Catalog.Type.RELATIONAL, PROVIDER, "comment", catalogProperties());
    Catalog loaded = metalake.loadCatalog(catalogName);
    Assertions.assertEquals(created, loaded);
    Assertions.assertEquals(PROVIDER, loaded.provider());
    catalog = loaded;
  }

  /** A SQL Server connection is bound to one database, which is what the catalog maps to. */
  private Map<String, String> catalogProperties() {
    return ImmutableMap.of(
        JdbcConfig.JDBC_URL.getKey(), SQLSERVER_JDBC_URL,
        JdbcConfig.JDBC_DRIVER.getKey(), DRIVER,
        JdbcConfig.JDBC_DATABASE.getKey(), SQLSERVER_DATABASE,
        JdbcConfig.USERNAME.getKey(), SQLSERVER_USERNAME,
        JdbcConfig.PASSWORD.getKey(), SQLSERVER_PASSWORD);
  }

  private void createSchema() {
    Schema created = catalog.asSchemas().createSchema(schemaName, null, Collections.emptyMap());
    Assertions.assertEquals(schemaName, created.name());
    Assertions.assertEquals(schemaName, catalog.asSchemas().loadSchema(schemaName).name());
  }

  private void dropSchemaQuietly(String schema) {
    try {
      catalog.asSchemas().dropSchema(schema, true);
    } catch (Exception e) {
      LOG.warn("Failed to drop schema {} during cleanup", schema, e);
    }
  }

  private Column[] createColumns() {
    return new Column[] {
      Column.of(
          "c_id", Types.LongType.get(), "identity pk", false, true, Column.DEFAULT_VALUE_NOT_SET),
      Column.of(
          "c_bit",
          Types.BooleanType.get(),
          "a boolean",
          true,
          false,
          Literals.booleanLiteral(false)),
      Column.of(
          "c_short", Types.ShortType.get(), null, true, false, Literals.shortLiteral((short) 7)),
      Column.of("c_int", Types.IntegerType.get(), null, true, false, Literals.integerLiteral(42)),
      Column.of(
          "c_long_default",
          Types.LongType.get(),
          null,
          true,
          false,
          Literals.longLiteral(100000000000L)),
      Column.of("c_real", Types.FloatType.get(), null, true, false, Literals.floatLiteral(1.5f)),
      Column.of("c_float", Types.DoubleType.get(), null, true, false, Literals.doubleLiteral(2.5d)),
      Column.of(
          "c_decimal",
          Types.DecimalType.of(18, 4),
          null,
          true,
          false,
          Literals.decimalLiteral(Decimal.of("123.4500", 18, 4))),
      Column.of(
          "c_date",
          Types.DateType.get(),
          null,
          true,
          false,
          Literals.dateLiteral(LocalDate.of(2024, 1, 2))),
      Column.of(
          "c_time",
          Types.TimeType.of(3),
          null,
          true,
          false,
          Literals.of(LocalTime.of(3, 4, 5), Types.TimeType.of(3))),
      Column.of(
          "c_datetime2",
          Types.TimestampType.withoutTimeZone(3),
          null,
          true,
          false,
          Literals.of(
              LocalDateTime.of(2024, 1, 2, 3, 4, 5), Types.TimestampType.withoutTimeZone(3))),
      Column.of(
          "c_dtoffset",
          Types.TimestampType.withTimeZone(3),
          null,
          true,
          false,
          Column.DEFAULT_VALUE_NOT_SET),
      Column.of(
          "c_char",
          Types.FixedCharType.of(10),
          null,
          true,
          false,
          Literals.of("abc", Types.FixedCharType.of(10))),
      Column.of(
          "c_varchar",
          Types.VarCharType.of(200),
          null,
          true,
          false,
          Literals.varcharLiteral(200, "hello")),
      Column.of(
          "c_string", Types.StringType.get(), null, true, false, Literals.stringLiteral("plain")),
      Column.of(
          "c_binary", Types.BinaryType.get(), null, true, false, Column.DEFAULT_VALUE_NOT_SET),
      Column.of("c_uuid", Types.UUIDType.get(), null, true, false, Column.DEFAULT_VALUE_NOT_SET),
      Column.of(
          "c_notnull", Types.IntegerType.get(), null, false, false, Column.DEFAULT_VALUE_NOT_SET),
      Column.of(
          "c_curts",
          Types.TimestampType.withoutTimeZone(),
          null,
          true,
          false,
          Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP)
    };
  }

  private Index[] createIndexes() {
    return new Index[] {
      Indexes.primary("pk_e2e", new String[][] {{"c_id"}}),
      Indexes.unique("uk_e2e", new String[][] {{"c_int"}})
    };
  }

  private static Map<String, Column> indexByName(Table table) {
    return Arrays.stream(table.columns()).collect(Collectors.toMap(Column::name, column -> column));
  }

  /**
   * Asserts that every column holding a literal default read back that default typed as the column
   * itself.
   *
   * <p>{@code LiteralImpl} compares the data type before the value, so a default carrying any other
   * type never tests equal to the one submitted however right its value is. {@code Literals.NULL}
   * is exempt, because {@code NullType} is what the API prescribes for an explicit null default.
   */
  private static void assertDefaultsCarryColumnType(Table table) {
    for (Column column : table.columns()) {
      if (column.defaultValue() instanceof Literal
          && !Literals.NULL.equals(column.defaultValue())) {
        Literal<?> literal = (Literal<?>) column.defaultValue();
        Assertions.assertEquals(
            column.dataType(),
            literal.dataType(),
            () -> "the default of " + column.name() + " does not carry its column type");
      }
    }
  }
}
