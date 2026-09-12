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
package org.apache.gravitino.catalog.mongodb;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.connector.CatalogInfo;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.HasPropertyMetadata;
import org.apache.gravitino.connector.SupportsSchemas;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.PrincipalUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MongodbCatalogOperations implements CatalogOperations, SupportsSchemas, TableCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(MongodbCatalogOperations.class);

  static final String SYSTEM_COLLECTION = "__gravitino_init__";

  private static final Set<String> SYSTEM_DATABASES =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("admin", "local", "config")));

  private final MongodbTypeConverter typeConverter = MongodbTypeConverter.INSTANCE;

  private MongoClient mongoClient;

  @VisibleForTesting
  MongodbCatalogOperations() {}

  @Override
  public void initialize(
      Map<String, String> config, CatalogInfo info, HasPropertyMetadata propertiesMetadata)
      throws RuntimeException {

    Preconditions.checkArgument(
        config.containsKey(MongodbCatalogPropertiesMetadata.MONGODB_URI),
        "Missing configuration: %s",
        MongodbCatalogPropertiesMetadata.MONGODB_URI);

    String uri = config.get(MongodbCatalogPropertiesMetadata.MONGODB_URI);
    MongoClientSettings settings =
        MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri)).build();

    try {
      mongoClient = MongoClients.create(settings);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create MongoDB client", e);
    }
  }

  @Override
  public void testConnection(NameIdentifier catalogIdent) {
    try {
      mongoClient.listDatabaseNames().first();
    } catch (Exception e) {
      throw new ConnectionFailedException(e, "Failed to connect to MongoDB: %s", e.getMessage());
    }
  }

  @Override
  public void close() {
    if (mongoClient != null) {
      mongoClient.close();
    }
  }

  // ========== Schema Operations ==========

  @Override
  public NameIdentifier[] listSchemas(Namespace namespace) {
    try {
      List<String> dbNames = new ArrayList<>();
      for (String name : mongoClient.listDatabaseNames()) {
        if (!SYSTEM_DATABASES.contains(name)) {
          dbNames.add(name);
        }
      }
      return dbNames.stream()
          .map(name -> NameIdentifier.of(namespace, name))
          .toArray(NameIdentifier[]::new);
    } catch (Exception e) {
      throw new RuntimeException("Failed to list schemas under namespace " + namespace, e);
    }
  }

  @Override
  public Schema createSchema(NameIdentifier ident, String comment, Map<String, String> properties)
      throws SchemaAlreadyExistsException {
    String schemaName = ident.name();

    // Check if database already exists
    boolean exists = false;
    for (String name : mongoClient.listDatabaseNames()) {
      if (name.equals(schemaName)) {
        exists = true;
        break;
      }
    }
    if (exists) {
      throw new SchemaAlreadyExistsException("Schema %s already exists", ident);
    }

    // MongoDB creates databases lazily when data is written.
    // Keep the marker collection so the database persists (MongoDB removes empty databases).
    try {
      MongoDatabase db = mongoClient.getDatabase(schemaName);
      db.createCollection(SYSTEM_COLLECTION);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create schema " + ident, e);
    }

    LOG.info("Created schema {}", ident);

    return MongodbSchema.builder()
        .withName(schemaName)
        .withComment(comment)
        .withProperties(properties != null ? properties : Collections.emptyMap())
        .withAuditInfo(
            org.apache.gravitino.meta.AuditInfo.builder()
                .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
                .withCreateTime(Instant.now())
                .build())
        .build();
  }

  @Override
  public Schema loadSchema(NameIdentifier ident) throws NoSuchSchemaException {
    String schemaName = ident.name();

    boolean exists = false;
    for (String name : mongoClient.listDatabaseNames()) {
      if (name.equals(schemaName)) {
        exists = true;
        break;
      }
    }
    if (!exists) {
      throw new NoSuchSchemaException("Schema %s does not exist", ident);
    }

    return MongodbSchema.builder()
        .withName(schemaName)
        .withComment(null)
        .withProperties(Collections.emptyMap())
        .withAuditInfo(
            org.apache.gravitino.meta.AuditInfo.builder()
                .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
                .withCreateTime(Instant.now())
                .build())
        .build();
  }

  @Override
  public Schema alterSchema(NameIdentifier ident, SchemaChange... changes)
      throws NoSuchSchemaException {
    // MongoDB databases don't support metadata changes
    return loadSchema(ident);
  }

  @Override
  public boolean dropSchema(NameIdentifier ident, boolean cascade) throws NonEmptySchemaException {
    String schemaName = ident.name();

    boolean exists = false;
    for (String name : mongoClient.listDatabaseNames()) {
      if (name.equals(schemaName)) {
        exists = true;
        break;
      }
    }
    if (!exists) {
      return false;
    }

    if (!cascade) {
      // Check if database has user collections (exclude the system marker collection)
      MongoDatabase db = mongoClient.getDatabase(schemaName);
      long collectionCount =
          db.listCollectionNames().into(new ArrayList<>()).stream()
              .filter(name -> !SYSTEM_COLLECTION.equals(name))
              .count();
      if (collectionCount > 0) {
        throw new NonEmptySchemaException(
            "Schema %s is not empty. Use cascade=true to drop it.", ident);
      }
    }

    try {
      mongoClient.getDatabase(schemaName).drop();
      LOG.info("Dropped schema {}", ident);
      return true;
    } catch (Exception e) {
      throw new RuntimeException("Failed to drop schema " + ident, e);
    }
  }

  // ========== Table Operations ==========

  @Override
  public NameIdentifier[] listTables(Namespace namespace) throws NoSuchSchemaException {
    String schemaName = namespace.level(namespace.length() - 1);

    // Verify schema exists
    boolean schemaExists = false;
    for (String name : mongoClient.listDatabaseNames()) {
      if (name.equals(schemaName)) {
        schemaExists = true;
        break;
      }
    }
    if (!schemaExists) {
      throw new NoSuchSchemaException("Schema %s does not exist", schemaName);
    }

    try {
      MongoDatabase db = mongoClient.getDatabase(schemaName);
      List<String> collectionNames = db.listCollectionNames().into(new ArrayList<>());
      return collectionNames.stream()
          .filter(name -> !SYSTEM_COLLECTION.equals(name))
          .map(name -> NameIdentifier.of(namespace, name))
          .toArray(NameIdentifier[]::new);
    } catch (Exception e) {
      throw new RuntimeException("Failed to list tables under schema " + schemaName, e);
    }
  }

  @Override
  public Table loadTable(NameIdentifier ident) throws NoSuchTableException {
    String schemaName = ident.namespace().level(ident.namespace().length() - 1);
    String tableName = ident.name();

    MongoDatabase db = mongoClient.getDatabase(schemaName);

    // Check if collection exists
    boolean exists = false;
    for (String name : db.listCollectionNames()) {
      if (name.equals(tableName)) {
        exists = true;
        break;
      }
    }
    if (!exists) {
      throw new NoSuchTableException("Table %s does not exist", ident);
    }

    // Get collection info to read validator
    Document collectionInfo = null;
    for (Document doc : db.listCollections()) {
      if (tableName.equals(doc.getString("name"))) {
        collectionInfo = doc;
        break;
      }
    }

    // Parse validator to get columns
    Column[] columns = new Column[0];
    if (collectionInfo != null) {
      Document options = collectionInfo.get("options", Document.class);
      if (options != null) {
        Document validator = options.get("validator", Document.class);
        if (validator != null) {
          Document jsonSchema = validator.get("$jsonSchema", Document.class);
          if (jsonSchema != null) {
            Document properties = jsonSchema.get("properties", Document.class);
            if (properties != null) {
              List<String> required =
                  jsonSchema.containsKey("required")
                      ? jsonSchema.getList("required", String.class)
                      : Collections.emptyList();
              List<Column> columnList = new ArrayList<>();
              for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String fieldName = entry.getKey();
                Document fieldDef = (Document) entry.getValue();
                String bsonType = fieldDef.getString("bsonType");
                String comment = fieldDef.getString("description");
                Type gravitinoType =
                    bsonType != null ? typeConverter.toGravitino(bsonType) : Types.StringType.get();
                boolean nullable = !required.contains(fieldName);
                columnList.add(Column.of(fieldName, gravitinoType, comment, nullable, false, null));
              }
              columns = columnList.toArray(new Column[0]);
            }
          }
        }
      }
    }

    Map<String, String> tableProperties = new HashMap<>();

    return MongodbTable.builder()
        .withName(tableName)
        .withColumns(columns)
        .withComment(null)
        .withProperties(tableProperties)
        .withAuditInfo(
            org.apache.gravitino.meta.AuditInfo.builder()
                .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
                .withCreateTime(Instant.now())
                .build())
        .build();
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitions,
      Distribution distribution,
      SortOrder[] sortOrders,
      Index[] indexes)
      throws TableAlreadyExistsException {
    String schemaName = ident.namespace().level(ident.namespace().length() - 1);
    String tableName = ident.name();

    MongoDatabase db = mongoClient.getDatabase(schemaName);

    // Check if collection already exists
    boolean exists = false;
    for (String name : db.listCollectionNames()) {
      if (name.equals(tableName)) {
        exists = true;
        break;
      }
    }
    if (exists) {
      throw new TableAlreadyExistsException("Table %s already exists", ident);
    }

    // Build $jsonSchema validator from columns
    Document jsonSchema = buildJsonSchemaValidator(columns);

    // Create collection with validator
    try {
      com.mongodb.client.model.CreateCollectionOptions options =
          new com.mongodb.client.model.CreateCollectionOptions();
      org.bson.conversions.Bson validator = new Document("$jsonSchema", jsonSchema);
      options.validationOptions(
          new com.mongodb.client.model.ValidationOptions().validator(validator));
      db.createCollection(tableName, options);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create table " + ident, e);
    }

    LOG.info("Created table {} with {} columns", ident, columns.length);

    return MongodbTable.builder()
        .withName(tableName)
        .withColumns(columns)
        .withComment(comment)
        .withProperties(properties != null ? properties : Collections.emptyMap())
        .withAuditInfo(
            org.apache.gravitino.meta.AuditInfo.builder()
                .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
                .withCreateTime(Instant.now())
                .build())
        .build();
  }

  @Override
  public Table alterTable(NameIdentifier ident, TableChange... changes)
      throws NoSuchTableException {
    // Load existing table first
    Table existingTable = loadTable(ident);

    // For MongoDB, we can update the validator
    // But most TableChange operations don't map cleanly to MongoDB
    // For now, we just return the existing table
    LOG.warn("alterTable is limited for MongoDB catalog. Table: {}", ident);

    return existingTable;
  }

  @Override
  public boolean dropTable(NameIdentifier ident) {
    String schemaName = ident.namespace().level(ident.namespace().length() - 1);
    String tableName = ident.name();

    MongoDatabase db = mongoClient.getDatabase(schemaName);

    // Check if collection exists
    boolean exists = false;
    for (String name : db.listCollectionNames()) {
      if (name.equals(tableName)) {
        exists = true;
        break;
      }
    }
    if (!exists) {
      return false;
    }

    try {
      db.getCollection(tableName).drop();
      LOG.info("Dropped table {}", ident);
      return true;
    } catch (Exception e) {
      throw new RuntimeException("Failed to drop table " + ident, e);
    }
  }

  @Override
  public boolean purgeTable(NameIdentifier ident) throws UnsupportedOperationException {
    // For MongoDB, drop and purge are the same
    return dropTable(ident);
  }

  // ========== Helper Methods ==========

  private Document buildJsonSchemaValidator(Column[] columns) {
    Document properties = new Document();
    List<String> required = new ArrayList<>();

    for (Column column : columns) {
      String bsonType = typeConverter.fromGravitino(column.dataType());
      Document fieldDef = new Document("bsonType", bsonType);
      if (column.comment() != null) {
        fieldDef.put("description", column.comment());
      }
      properties.put(column.name(), fieldDef);

      if (!column.nullable()) {
        required.add(column.name());
      }
    }

    Document jsonSchema = new Document("bsonType", "object");
    jsonSchema.put("properties", properties);
    if (!required.isEmpty()) {
      jsonSchema.put("required", required);
    }

    return jsonSchema;
  }
}
