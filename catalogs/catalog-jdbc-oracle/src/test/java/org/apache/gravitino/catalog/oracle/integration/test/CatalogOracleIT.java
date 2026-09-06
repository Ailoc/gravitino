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
package org.apache.gravitino.catalog.oracle.integration.test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.oracle.integration.test.service.OracleService;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
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
 * Integration test for the Oracle JDBC catalog.
 *
 * <p>Oracle publishes no image that can be started anonymously, so these tests run against an
 * Oracle instance you provide rather than one they start. Set the following environment variables
 * to enable them:
 *
 * <ul>
 *   <li>{@code GRAVITINO_TEST_CLOUD_IT} - must be {@code true}
 *   <li>{@code GRAVITINO_ORACLE_JDBC_URL} - for example {@code
 *       jdbc:oracle:thin:@//host:1521/FREEPDB1}
 *   <li>{@code GRAVITINO_ORACLE_USERNAME} - an account holding {@code CREATE USER} and {@code DROP
 *       USER}, since a Gravitino schema is an Oracle user
 *   <li>{@code GRAVITINO_ORACLE_PASSWORD}
 * </ul>
 *
 * <p>Run them with {@code ./gradlew :catalogs:catalog-jdbc-oracle:test -PskipTests}, exporting the
 * variables in the same shell. The Gradle daemon captures the environment when it starts, so pass
 * {@code --no-daemon} or stop a running daemon first.
 *
 * <p>Each test drives the catalog through the REST client and then reads Oracle's data dictionary
 * directly, so a statement the catalog failed to issue is caught rather than echoed back.
 */
@EnabledIfEnvironmentVariable(named = "GRAVITINO_TEST_CLOUD_IT", matches = "true")
@EnabledIf(value = "oracleIsConfigured", disabledReason = "Oracle is not configured")
@TestInstance(Lifecycle.PER_CLASS)
public class CatalogOracleIT extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogOracleIT.class);

  private static final String PROVIDER = "jdbc-oracle";
  private static final String DRIVER = "oracle.jdbc.OracleDriver";

  private static final String TABLE_COMMENT = "oracle_it_table_comment";
  private static final String UPDATED_TABLE_COMMENT = "oracle_it_updated_table_comment";

  private static final String ORACLE_JDBC_URL = System.getenv("GRAVITINO_ORACLE_JDBC_URL");
  private static final String ORACLE_USERNAME = System.getenv("GRAVITINO_ORACLE_USERNAME");
  private static final String ORACLE_PASSWORD = System.getenv("GRAVITINO_ORACLE_PASSWORD");

  private final String metalakeName = GravitinoITUtils.genRandomName("oracle_it_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("oracle_it_catalog");
  private final String schemaName = oracleName("oracle_it_schema");
  private final String commentedSchemaName = oracleName("oracle_it_commented");
  private final String temporarySchemaName = oracleName("oracle_it_temp");
  private final String tableName = oracleName("oracle_it_table");
  private final String renamedTableName = oracleName("oracle_it_renamed");
  private final String purgedTableName = oracleName("oracle_it_purged");

  private GravitinoMetalake metalake;
  private Catalog catalog;
  private OracleService oracleService;

  /** Reports whether the environment carries everything an Oracle instance needs. */
  protected static boolean oracleIsConfigured() {
    return StringUtils.isNotBlank(ORACLE_JDBC_URL)
        && StringUtils.isNotBlank(ORACLE_USERNAME)
        && StringUtils.isNotBlank(ORACLE_PASSWORD);
  }

  @BeforeAll
  public void startup() throws IOException {
    oracleService = new OracleService(ORACLE_JDBC_URL, ORACLE_USERNAME, ORACLE_PASSWORD);
    createMetalake();
    createCatalog();
    createSchema();
  }

  @AfterAll
  public void stop() {
    try {
      // A run that fails part way leaves Oracle users behind, which no one notices until the
      // account list grows.
      dropSchemaQuietly(commentedSchemaName);
      dropSchemaQuietly(temporarySchemaName);
      dropSchemaQuietly(schemaName);
      metalake.dropCatalog(catalogName);
      client.dropMetalake(metalakeName);
    } catch (Exception e) {
      LOG.error("Failed to stop.", e);
    } finally {
      oracleService.close();
    }
  }

  @AfterEach
  public void resetSchema() {
    catalog.asSchemas().dropSchema(schemaName, true);
    createSchema();
  }

  /** Covers schema enumeration, the schema comment the catalog has to refuse, and dropping. */
  @Test
  void testOperationOracleSchema() {
    SupportsSchemas schemas = catalog.asSchemas();

    Set<String> reported = Sets.newHashSet(schemas.listSchemas());
    List<String> inDictionary = oracleService.listSchemas();

    Assertions.assertTrue(reported.contains(schemaName));
    Assertions.assertTrue(oracleService.schemaExists(schemaName));
    // Everything the catalog reports has to be a schema Oracle really has.
    Assertions.assertTrue(inDictionary.containsAll(reported));

    // These two exist in every Oracle database and belong to Oracle, so a catalog that enumerated
    // the dictionary unfiltered would surface them.
    Assertions.assertTrue(inDictionary.contains("SYS"));
    Assertions.assertTrue(inDictionary.contains("SYSTEM"));
    Assertions.assertFalse(reported.contains("SYS"));
    Assertions.assertFalse(reported.contains("SYSTEM"));
    // Oracle leaves PDBADMIN behind when a pluggable database is created and does not mark it as
    // its own, which is why the catalog names it explicitly.
    Assertions.assertFalse(reported.contains("PDBADMIN"));

    Assertions.assertThrows(
        SchemaAlreadyExistsException.class,
        () -> schemas.createSchema(schemaName, null, Collections.emptyMap()));

    // Oracle has no COMMENT ON SCHEMA, so the catalog refuses a comment rather than dropping it,
    // and refuses it before creating the user that would have to be cleaned up afterwards.
    UnsupportedOperationException refused =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () ->
                schemas.createSchema(
                    commentedSchemaName, "a comment Oracle cannot store", Collections.emptyMap()));
    Assertions.assertTrue(refused.getMessage().contains("a comment Oracle cannot store"));
    Assertions.assertFalse(oracleService.schemaExists(commentedSchemaName));

    Schema loaded = schemas.loadSchema(schemaName);
    Assertions.assertEquals(schemaName, loaded.name());
    Assertions.assertNull(loaded.comment());
    Assertions.assertTrue(schemas.schemaExists(schemaName));

    schemas.createSchema(temporarySchemaName, null, Collections.emptyMap());
    Assertions.assertTrue(schemas.schemaExists(temporarySchemaName));
    Assertions.assertTrue(oracleService.schemaExists(temporarySchemaName));
    Assertions.assertTrue(schemas.dropSchema(temporarySchemaName, false));
    Assertions.assertFalse(schemas.schemaExists(temporarySchemaName));
    Assertions.assertFalse(oracleService.schemaExists(temporarySchemaName));
    Assertions.assertThrows(
        NoSuchSchemaException.class, () -> schemas.loadSchema(temporarySchemaName));
    Assertions.assertFalse(schemas.dropSchema(temporarySchemaName, false));

    // Oracle rejects DROP USER on a user who still owns objects with an ORA-01940 that does not
    // mention the cascade, so the catalog says so itself and destroys nothing.
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            createColumns(),
            TABLE_COMMENT,
            Collections.emptyMap());
    Assertions.assertThrows(RuntimeException.class, () -> schemas.dropSchema(schemaName, false));
    Assertions.assertTrue(schemas.schemaExists(schemaName));
    Assertions.assertTrue(oracleService.tableExists(schemaName, tableName));
  }

  /** Creates a table holding every type the catalog maps, and reads it back from Oracle itself. */
  @Test
  void testCreateAndLoadOracleTable() {
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
    assertDefaultsCarryColumnType(loaded);

    Map<String, Column> byName = indexByName(loaded);

    Column identity = byName.get("c_id");
    Assertions.assertFalse(identity.nullable());
    Assertions.assertTrue(identity.autoIncrement());
    Assertions.assertEquals("identity pk", identity.comment());
    // The NEXTVAL of the sequence Oracle names after the table is not a default the user chose.
    Assertions.assertEquals(Column.DEFAULT_VALUE_NOT_SET, identity.defaultValue());

    // Oracle has no boolean type and NUMBER(1) stands in for it, so the default reads back numeric.
    assertDefaultValue(byName.get("c_bool"), "0");
    assertDefaultValue(byName.get("c_short"), "7");
    assertDefaultValue(byName.get("c_int"), "42");
    assertDefaultValue(byName.get("c_long_default"), "100000000000");

    Assertions.assertEquals(Literals.floatLiteral(1.5f), byName.get("c_float").defaultValue());
    Assertions.assertEquals(Literals.doubleLiteral(2.5d), byName.get("c_double").defaultValue());
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("123.4500", 18, 4)),
        byName.get("c_decimal").defaultValue());

    // An Oracle DATE holds a time of day, so a date default reads back as a timestamp of precision
    // zero rather than as the date that was submitted.
    Types.TimestampType dateType = Types.TimestampType.withoutTimeZone(0);
    Assertions.assertEquals(dateType, byName.get("c_date").dataType());
    Assertions.assertEquals(
        Literals.of(LocalDateTime.of(2024, 1, 2, 0, 0), dateType),
        byName.get("c_date").defaultValue());

    Types.TimestampType timestampType = Types.TimestampType.withoutTimeZone(3);
    Assertions.assertEquals(timestampType, byName.get("c_timestamp").dataType());
    Assertions.assertEquals(
        Literals.of(LocalDateTime.of(2024, 1, 2, 3, 4, 5), timestampType),
        byName.get("c_timestamp").defaultValue());

    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(6), byName.get("c_timestamptz").dataType());
    Assertions.assertEquals(Literals.NULL, byName.get("c_timestamptz").defaultValue());

    Assertions.assertEquals(Types.FixedCharType.of(10), byName.get("c_char").dataType());
    Assertions.assertEquals(
        Literals.of("abc", Types.FixedCharType.of(10)), byName.get("c_char").defaultValue());

    Assertions.assertEquals(Types.VarCharType.of(200), byName.get("c_varchar").dataType());
    Assertions.assertEquals(
        Literals.varcharLiteral(200, "hello"), byName.get("c_varchar").defaultValue());

    // An apostrophe in a default has to survive the trip through a SQL string literal.
    Assertions.assertEquals(Types.StringType.get(), byName.get("c_string").dataType());
    Assertions.assertEquals(Literals.stringLiteral("it's"), byName.get("c_string").defaultValue());

    Assertions.assertEquals(Types.BinaryType.get(), byName.get("c_binary").dataType());
    Assertions.assertEquals(Literals.NULL, byName.get("c_binary").defaultValue());

    Assertions.assertFalse(byName.get("c_notnull").nullable());
    Assertions.assertEquals(Column.DEFAULT_VALUE_NOT_SET, byName.get("c_notnull").defaultValue());

    Assertions.assertEquals(
        Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, byName.get("c_curts").defaultValue());

    Assertions.assertEquals(2, loaded.index().length);

    // The DDL really landed, rather than the API echoing back what it was handed.
    Assertions.assertTrue(oracleService.tableExists(schemaName, tableName));
    Assertions.assertTrue(
        oracleService.tableComment(schemaName, tableName).contains(TABLE_COMMENT));
    Assertions.assertEquals(
        "identity pk", oracleService.columnComment(schemaName, tableName, "c_id"));
    Assertions.assertTrue(oracleService.columnIsIdentity(schemaName, tableName, "c_id"));
    Assertions.assertFalse(oracleService.columnIsNullable(schemaName, tableName, "c_notnull"));
    Assertions.assertEquals(
        "VARCHAR2", oracleService.columnType(schemaName, tableName, "c_varchar"));
    Assertions.assertTrue(
        oracleService.columnDefault(schemaName, tableName, "c_string").contains("it''s"));
    Assertions.assertEquals(
        new HashSet<>(Arrays.asList("pk_e2e", "uk_e2e")),
        new HashSet<>(oracleService.listIndexNames(schemaName, tableName)));
  }

  /** Applies one batch of changes and checks each of them separately against Oracle. */
  @Test
  void testAlterOracleTable() {
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
            TableChange.updateColumnNullability(new String[] {"c_bool"}, false),
            TableChange.updateColumnDefaultValue(
                new String[] {"c_varchar"}, Literals.varcharLiteral(200, "changed")),
            TableChange.renameColumn(new String[] {"c_double"}, "c_double_renamed"),
            TableChange.deleteColumn(new String[] {"c_float"}, true),
            TableChange.updateComment(UPDATED_TABLE_COMMENT));
    Assertions.assertEquals(createColumns().length, altered.columns().length);

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertTrue(loaded.comment().contains(UPDATED_TABLE_COMMENT));
    assertDefaultsCarryColumnType(loaded);

    Map<String, Column> byName = indexByName(loaded);
    Assertions.assertTrue(byName.containsKey("c_added"));
    Assertions.assertTrue(byName.containsKey("c_double_renamed"));
    Assertions.assertFalse(byName.containsKey("c_float"));

    Assertions.assertEquals(Types.VarCharType.of(50), byName.get("c_added").dataType());
    Assertions.assertEquals("added col", byName.get("c_added").comment());
    Assertions.assertEquals(Literals.NULL, byName.get("c_added").defaultValue());
    Assertions.assertEquals("updated comment", byName.get("c_int").comment());
    Assertions.assertFalse(byName.get("c_bool").nullable());
    // Widening the column keeps the default Oracle already stored attached to it.
    assertDefaultValue(byName.get("c_short"), "7");
    Assertions.assertEquals(
        Literals.varcharLiteral(200, "changed"), byName.get("c_varchar").defaultValue());
    Assertions.assertEquals(
        Literals.doubleLiteral(2.5d), byName.get("c_double_renamed").defaultValue());

    Assertions.assertTrue(
        oracleService.tableComment(schemaName, tableName).contains(UPDATED_TABLE_COMMENT));
    Assertions.assertEquals(
        "updated comment", oracleService.columnComment(schemaName, tableName, "c_int"));
    Assertions.assertEquals(
        "added col", oracleService.columnComment(schemaName, tableName, "c_added"));
    Assertions.assertFalse(oracleService.columnIsNullable(schemaName, tableName, "c_bool"));
    Assertions.assertTrue(oracleService.tableExists(schemaName, tableName));
    Assertions.assertNull(oracleService.columnType(schemaName, tableName, "c_float"));
    Assertions.assertTrue(
        oracleService.columnDefault(schemaName, tableName, "c_varchar").contains("changed"));
  }

  /** Renames a table, lists it under its new name and drops it. */
  @Test
  void testRenameAndDropOracleTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    NameIdentifier renamedIdent = NameIdentifier.of(schemaName, renamedTableName);

    tableCatalog.createTable(tableIdent, createColumns(), TABLE_COMMENT, Collections.emptyMap());

    tableCatalog.alterTable(tableIdent, TableChange.rename(renamedTableName));
    Assertions.assertTrue(tableCatalog.tableExists(renamedIdent));
    Assertions.assertFalse(tableCatalog.tableExists(tableIdent));
    Assertions.assertTrue(oracleService.tableExists(schemaName, renamedTableName));
    Assertions.assertFalse(oracleService.tableExists(schemaName, tableName));

    Set<String> listed =
        Arrays.stream(tableCatalog.listTables(Namespace.of(schemaName)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertEquals(Collections.singleton(renamedTableName), listed);

    Assertions.assertTrue(tableCatalog.dropTable(renamedIdent));
    Assertions.assertFalse(tableCatalog.tableExists(renamedIdent));
    Assertions.assertFalse(oracleService.tableExists(schemaName, renamedTableName));
    Assertions.assertFalse(tableCatalog.dropTable(renamedIdent));
  }

  /** Checks that purging a table leaves nothing behind in Oracle. */
  @Test
  void testPurgeOracleTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier purgeIdent = NameIdentifier.of(schemaName, purgedTableName);

    tableCatalog.createTable(
        purgeIdent,
        new Column[] {
          Column.of("c_id", Types.LongType.get(), null, false, false, Column.DEFAULT_VALUE_NOT_SET)
        },
        "a table to purge",
        Collections.emptyMap());
    Assertions.assertTrue(oracleService.tableExists(schemaName, purgedTableName));

    Assertions.assertTrue(tableCatalog.purgeTable(purgeIdent));
    Assertions.assertFalse(tableCatalog.tableExists(purgeIdent));
    Assertions.assertFalse(oracleService.tableExists(schemaName, purgedTableName));
  }

  /** Oracle keeps the case of a quoted identifier, so names are upper cased as Oracle users do. */
  private static String oracleName(String prefix) {
    return GravitinoITUtils.genRandomName(prefix).toUpperCase(Locale.ROOT);
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

  /** Oracle has no catalog level above the schema, so {@code jdbc-database} is left unset. */
  private Map<String, String> catalogProperties() {
    return ImmutableMap.of(
        JdbcConfig.JDBC_URL.getKey(), ORACLE_JDBC_URL,
        JdbcConfig.JDBC_DRIVER.getKey(), DRIVER,
        JdbcConfig.USERNAME.getKey(), ORACLE_USERNAME,
        JdbcConfig.PASSWORD.getKey(), ORACLE_PASSWORD);
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
          "c_bool", Types.BooleanType.get(), null, true, false, Literals.booleanLiteral(false)),
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
      Column.of("c_float", Types.FloatType.get(), null, true, false, Literals.floatLiteral(1.5f)),
      Column.of(
          "c_double", Types.DoubleType.get(), null, true, false, Literals.doubleLiteral(2.5d)),
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
          "c_timestamp",
          Types.TimestampType.withoutTimeZone(3),
          null,
          true,
          false,
          Literals.timestampLiteral(LocalDateTime.of(2024, 1, 2, 3, 4, 5))),
      Column.of(
          "c_timestamptz",
          Types.TimestampType.withTimeZone(6),
          null,
          true,
          false,
          Column.DEFAULT_VALUE_NOT_SET),
      Column.of(
          "c_char", Types.FixedCharType.of(10), null, true, false, Literals.stringLiteral("abc")),
      Column.of(
          "c_varchar",
          Types.VarCharType.of(200),
          null,
          true,
          false,
          Literals.varcharLiteral(200, "hello")),
      Column.of(
          "c_string", Types.StringType.get(), null, true, false, Literals.stringLiteral("it's")),
      Column.of(
          "c_binary", Types.BinaryType.get(), null, true, false, Column.DEFAULT_VALUE_NOT_SET),
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
        assertDefaultCarriesColumnType(column);
      }
    }
  }

  /**
   * Asserts the value of a literal default.
   *
   * <p>Oracle maps every whole number type onto {@code NUMBER}, so the precision a numeric default
   * carries belongs to the column it was read from rather than to the type that was submitted and
   * cannot be spelled out here. The value on its own is what stays comparable.
   */
  private static void assertDefaultValue(Column column, String expectedValue) {
    Assertions.assertTrue(
        column.defaultValue() instanceof Literal,
        () -> column.name() + " did not read back a literal default: " + column.defaultValue());
    Literal<?> literal = (Literal<?>) column.defaultValue();
    Assertions.assertEquals(expectedValue, String.valueOf(literal.value()), column.name());
    assertDefaultCarriesColumnType(column);
  }

  private static void assertDefaultCarriesColumnType(Column column) {
    Literal<?> literal = (Literal<?>) column.defaultValue();
    Assertions.assertEquals(
        column.dataType(),
        literal.dataType(),
        () -> "the default of " + column.name() + " does not carry its column type");
  }
}
