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
package org.apache.gravitino.catalog.mongodb.integration.test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.catalog.mongodb.integration.test.service.MongodbService;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the MongoDB catalog.
 *
 * <p>These tests start their own MongoDB container, so they need Docker and run only under {@code
 * -PskipDockerTests=false}.
 *
 * <p>Each test drives the catalog through the REST client and then reads MongoDB directly, so a
 * statement the catalog failed to issue is caught rather than echoed back.
 */
@Tag("gravitino-docker-test")
@TestInstance(Lifecycle.PER_CLASS)
public class CatalogMongodbIT extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogMongodbIT.class);

  private static final String PROVIDER = "mongodb";
  private static final String MONGODB_URI_KEY = "mongodb.uri";

  private final String metalakeName = GravitinoITUtils.genRandomName("mongodb_it_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("mongodb_it_catalog");
  private final String schemaName = GravitinoITUtils.genRandomName("mongodb_it_schema");
  private final String temporarySchemaName = GravitinoITUtils.genRandomName("mongodb_it_temp");
  private final String tableName = GravitinoITUtils.genRandomName("mongodb_it_table");
  private final String purgedTableName = GravitinoITUtils.genRandomName("mongodb_it_purged");

  private MongoDBContainer mongodbContainer;
  private GravitinoMetalake metalake;
  private Catalog catalog;
  private MongodbService mongodbService;

  @BeforeAll
  public void startup() throws IOException {
    mongodbContainer = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
    mongodbContainer.start();

    mongodbService = new MongodbService(mongodbContainer.getConnectionString());

    createMetalake();
    createCatalog();
    createSchema();
  }

  @AfterAll
  public void stop() {
    try {
      // A run that fails part way leaves databases behind, which no one notices until the
      // database list grows.
      dropSchemaQuietly(temporarySchemaName);
      dropSchemaQuietly(schemaName);
      metalake.dropCatalog(catalogName);
      client.dropMetalake(metalakeName);
    } catch (Exception e) {
      LOG.error("Failed to stop.", e);
    } finally {
      mongodbService.close();
      mongodbContainer.stop();
    }
  }

  @AfterEach
  public void resetSchema() {
    catalog.asSchemas().dropSchema(schemaName, true);
    createSchema();
  }

  /** Covers schema enumeration, duplicate creation, and dropping. */
  @Test
  void testOperationMongodbSchema() {
    SupportsSchemas schemas = catalog.asSchemas();

    Set<String> reported = Sets.newHashSet(schemas.listSchemas());
    List<String> inDictionary = mongodbService.listDatabases();

    Assertions.assertTrue(reported.contains(schemaName));
    Assertions.assertTrue(mongodbService.databaseExists(schemaName));
    // Everything the catalog reports has to be a database MongoDB really has.
    Assertions.assertTrue(inDictionary.containsAll(reported));

    // MongoDB always ships these three system databases, which the catalog should not surface.
    Assertions.assertTrue(inDictionary.contains("admin"));
    Assertions.assertTrue(inDictionary.contains("local"));
    Assertions.assertTrue(inDictionary.contains("config"));
    Assertions.assertFalse(reported.contains("admin"));
    Assertions.assertFalse(reported.contains("local"));
    Assertions.assertFalse(reported.contains("config"));

    Assertions.assertThrows(
        SchemaAlreadyExistsException.class,
        () -> schemas.createSchema(schemaName, null, Collections.emptyMap()));

    Schema loaded = schemas.loadSchema(schemaName);
    Assertions.assertEquals(schemaName, loaded.name());
    Assertions.assertNull(loaded.comment());
    Assertions.assertTrue(schemas.schemaExists(schemaName));

    schemas.createSchema(temporarySchemaName, null, Collections.emptyMap());
    Assertions.assertTrue(schemas.schemaExists(temporarySchemaName));
    Assertions.assertTrue(mongodbService.databaseExists(temporarySchemaName));
    Assertions.assertTrue(schemas.dropSchema(temporarySchemaName, false));
    Assertions.assertFalse(schemas.schemaExists(temporarySchemaName));
    Assertions.assertFalse(mongodbService.databaseExists(temporarySchemaName));
    Assertions.assertThrows(
        NoSuchSchemaException.class, () -> schemas.loadSchema(temporarySchemaName));
    Assertions.assertFalse(schemas.dropSchema(temporarySchemaName, false));

    // MongoDB rejects DROP on a database that still holds collections, so the catalog says so
    // itself and destroys nothing.
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            createColumns(),
            null,
            Collections.emptyMap());
    Assertions.assertThrows(RuntimeException.class, () -> schemas.dropSchema(schemaName, false));
    Assertions.assertTrue(schemas.schemaExists(schemaName));
    Assertions.assertTrue(mongodbService.collectionExists(schemaName, tableName));
  }

  /** Creates a collection holding every type the catalog maps, and reads it back from MongoDB. */
  @Test
  void testCreateAndLoadMongodbTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);
    Column[] columns = createColumns();

    Table created = tableCatalog.createTable(tableIdent, columns, null, Collections.emptyMap());
    Assertions.assertEquals(tableName, created.name());
    Assertions.assertTrue(tableCatalog.tableExists(tableIdent));

    Table loaded = tableCatalog.loadTable(tableIdent);
    Assertions.assertEquals(
        Arrays.stream(columns).map(Column::name).collect(Collectors.toList()),
        Arrays.stream(loaded.columns()).map(Column::name).collect(Collectors.toList()));

    Map<String, Column> byName = indexByName(loaded);

    // The type converter collapses several Gravitino types onto the same BSON type, so the
    // round-trip reads back the canonical Gravitino type for each BSON type.
    Assertions.assertEquals(Types.BooleanType.get(), byName.get("c_bool").dataType());
    Assertions.assertEquals(Types.IntegerType.get(), byName.get("c_int").dataType());
    Assertions.assertEquals(Types.LongType.get(), byName.get("c_long").dataType());
    Assertions.assertEquals(Types.DoubleType.get(), byName.get("c_double").dataType());
    Assertions.assertEquals(Types.DecimalType.of(34, 34), byName.get("c_decimal").dataType());
    Assertions.assertEquals(Types.StringType.get(), byName.get("c_string").dataType());
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(), byName.get("c_timestamp").dataType());
    Assertions.assertEquals(Types.BinaryType.get(), byName.get("c_binary").dataType());

    // A non-nullable column lands in the validator's required array.
    Assertions.assertFalse(byName.get("c_notnull").nullable());

    // The DDL really landed, rather than the API echoing back what it was handed.
    Assertions.assertTrue(mongodbService.collectionExists(schemaName, tableName));

    org.bson.Document validator = mongodbService.collectionValidator(schemaName, tableName);
    Assertions.assertNotNull(validator);
    Assertions.assertEquals("object", validator.getString("bsonType"));

    Assertions.assertEquals("bool", mongodbService.fieldBsonType(schemaName, tableName, "c_bool"));
    Assertions.assertEquals("int", mongodbService.fieldBsonType(schemaName, tableName, "c_int"));
    Assertions.assertEquals("long", mongodbService.fieldBsonType(schemaName, tableName, "c_long"));
    Assertions.assertEquals(
        "double", mongodbService.fieldBsonType(schemaName, tableName, "c_double"));
    Assertions.assertEquals(
        "decimal", mongodbService.fieldBsonType(schemaName, tableName, "c_decimal"));
    Assertions.assertEquals(
        "string", mongodbService.fieldBsonType(schemaName, tableName, "c_string"));
    Assertions.assertEquals(
        "date", mongodbService.fieldBsonType(schemaName, tableName, "c_timestamp"));
    Assertions.assertEquals(
        "binData", mongodbService.fieldBsonType(schemaName, tableName, "c_binary"));

    Assertions.assertEquals(
        "a boolean field", mongodbService.fieldDescription(schemaName, tableName, "c_bool"));
    Assertions.assertEquals(
        "non-nullable field", mongodbService.fieldDescription(schemaName, tableName, "c_notnull"));

    Assertions.assertEquals(
        new HashSet<>(Arrays.asList("c_id", "c_notnull")),
        new HashSet<>(mongodbService.requiredFields(schemaName, tableName)));
  }

  /** Lists tables, checks existence, and drops a collection. */
  @Test
  void testListAndDropMongodbTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);

    tableCatalog.createTable(tableIdent, createColumns(), null, Collections.emptyMap());

    Set<String> listed =
        Arrays.stream(tableCatalog.listTables(Namespace.of(schemaName)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(listed.contains(tableName));

    Assertions.assertTrue(tableCatalog.tableExists(tableIdent));
    Assertions.assertTrue(mongodbService.collectionExists(schemaName, tableName));

    Assertions.assertTrue(tableCatalog.dropTable(tableIdent));
    Assertions.assertFalse(tableCatalog.tableExists(tableIdent));
    Assertions.assertFalse(mongodbService.collectionExists(schemaName, tableName));
    Assertions.assertFalse(tableCatalog.dropTable(tableIdent));
  }

  /** Checks that purging a collection leaves nothing behind in MongoDB. */
  @Test
  void testPurgeMongodbTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier purgeIdent = NameIdentifier.of(schemaName, purgedTableName);

    tableCatalog.createTable(
        purgeIdent,
        new Column[] {Column.of("c_id", Types.LongType.get(), null)},
        null,
        Collections.emptyMap());
    Assertions.assertTrue(mongodbService.collectionExists(schemaName, purgedTableName));

    Assertions.assertTrue(tableCatalog.purgeTable(purgeIdent));
    Assertions.assertFalse(tableCatalog.tableExists(purgeIdent));
    Assertions.assertFalse(mongodbService.collectionExists(schemaName, purgedTableName));
  }

  /** MongoDB refuses to drop a database that still holds collections when cascade is off. */
  @Test
  void testDropNonEmptySchema() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    tableCatalog.createTable(
        NameIdentifier.of(schemaName, tableName),
        new Column[] {Column.of("c_id", Types.LongType.get(), null)},
        null,
        Collections.emptyMap());

    Assertions.assertThrows(
        NonEmptySchemaException.class, () -> catalog.asSchemas().dropSchema(schemaName, false));

    // The schema and its collection must both survive the refused drop.
    Assertions.assertTrue(catalog.asSchemas().schemaExists(schemaName));
    Assertions.assertTrue(mongodbService.collectionExists(schemaName, tableName));
  }

  /** Creating a table that already exists must raise, not silently succeed. */
  @Test
  void testCreateDuplicateTable() {
    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdent = NameIdentifier.of(schemaName, tableName);

    tableCatalog.createTable(
        tableIdent,
        new Column[] {Column.of("c_id", Types.LongType.get(), null)},
        null,
        Collections.emptyMap());

    Assertions.assertThrows(
        TableAlreadyExistsException.class,
        () ->
            tableCatalog.createTable(
                tableIdent,
                new Column[] {Column.of("c_other", Types.StringType.get(), null)},
                null,
                Collections.emptyMap()));
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

  private Map<String, String> catalogProperties() {
    return ImmutableMap.of(MONGODB_URI_KEY, mongodbContainer.getConnectionString());
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
      Column.of("c_id", Types.LongType.get(), "identity field", false, false, null),
      Column.of("c_bool", Types.BooleanType.get(), "a boolean field"),
      Column.of("c_int", Types.IntegerType.get(), null),
      Column.of("c_long", Types.LongType.get(), null),
      Column.of("c_double", Types.DoubleType.get(), null),
      Column.of("c_decimal", Types.DecimalType.of(18, 4), null),
      Column.of("c_string", Types.StringType.get(), null),
      Column.of("c_timestamp", Types.TimestampType.withoutTimeZone(), null),
      Column.of("c_binary", Types.BinaryType.get(), null),
      Column.of("c_notnull", Types.IntegerType.get(), "non-nullable field", false, false, null)
    };
  }

  private static Map<String, Column> indexByName(Table table) {
    return Arrays.stream(table.columns()).collect(Collectors.toMap(Column::name, column -> column));
  }
}
