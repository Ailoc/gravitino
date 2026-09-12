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
package org.apache.gravitino.catalog.mongodb.integration.test.service;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;

/**
 * Reads MongoDB's state directly through the driver, so that an integration test can prove the
 * catalog's DDL actually landed rather than only that the API round-tripped what it was given.
 *
 * <p>Every query reads from the same database the catalog writes to, so a collection or validator
 * the catalog failed to create is caught rather than echoed back.
 */
public class MongodbService implements AutoCloseable {

  private final MongoClient mongoClient;

  public MongodbService(String connectionString) {
    MongoClientSettings settings =
        MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(connectionString))
            .build();
    this.mongoClient = MongoClients.create(settings);
  }

  /**
   * Returns every database MongoDB knows about, unfiltered, so a test can check what was dropped.
   */
  public List<String> listDatabases() {
    List<String> names = new ArrayList<>();
    try (MongoCursor<String> cursor = mongoClient.listDatabaseNames().iterator()) {
      while (cursor.hasNext()) {
        names.add(cursor.next());
      }
    }
    return names;
  }

  public boolean databaseExists(String database) {
    for (String name : mongoClient.listDatabaseNames()) {
      if (name.equals(database)) {
        return true;
      }
    }
    return false;
  }

  public List<String> listCollectionNames(String database) {
    List<String> names = new ArrayList<>();
    try (MongoCursor<String> cursor =
        mongoClient.getDatabase(database).listCollectionNames().iterator()) {
      while (cursor.hasNext()) {
        names.add(cursor.next());
      }
    }
    return names;
  }

  public boolean collectionExists(String database, String collection) {
    for (String name : mongoClient.getDatabase(database).listCollectionNames()) {
      if (name.equals(collection)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the {@code $jsonSchema} validator MongoDB stores for the collection, or {@code null} if
   * the collection has none. The catalog builds this validator from the column definitions, so a
   * non-null result proves the DDL landed.
   */
  public Document collectionValidator(String database, String collection) {
    try (MongoCursor<Document> cursor =
        mongoClient.getDatabase(database).listCollections().iterator()) {
      while (cursor.hasNext()) {
        Document info = cursor.next();
        if (collection.equals(info.getString("name"))) {
          Document options = info.get("options", Document.class);
          if (options == null) {
            return null;
          }
          Document validator = options.get("validator", Document.class);
          if (validator == null) {
            return null;
          }
          return validator.get("$jsonSchema", Document.class);
        }
      }
    }
    return null;
  }

  /**
   * Returns the BSON type the validator records for a field, or {@code null} if the field is not
   * part of the validator.
   */
  public String fieldBsonType(String database, String collection, String field) {
    Document validator = collectionValidator(database, collection);
    if (validator == null) {
      return null;
    }
    Document properties = validator.get("properties", Document.class);
    if (properties == null) {
      return null;
    }
    Document fieldDef = properties.get(field, Document.class);
    if (fieldDef == null) {
      return null;
    }
    return fieldDef.getString("bsonType");
  }

  /**
   * Returns the description the validator records for a field, or {@code null} if the field has
   * none. The catalog stores the column comment in this slot.
   */
  public String fieldDescription(String database, String collection, String field) {
    Document validator = collectionValidator(database, collection);
    if (validator == null) {
      return null;
    }
    Document properties = validator.get("properties", Document.class);
    if (properties == null) {
      return null;
    }
    Document fieldDef = properties.get(field, Document.class);
    if (fieldDef == null) {
      return null;
    }
    return fieldDef.getString("description");
  }

  /**
   * Returns the names of the fields the validator marks as required, which is how the catalog
   * records non-nullable columns.
   */
  public List<String> requiredFields(String database, String collection) {
    Document validator = collectionValidator(database, collection);
    if (validator == null) {
      return new ArrayList<>();
    }
    List<String> required = validator.getList("required", String.class);
    return required != null ? required : new ArrayList<>();
  }

  @Override
  public void close() {
    try {
      mongoClient.close();
    } catch (Exception e) {
      // Nothing useful can be done with a client that refuses to close in a test teardown.
    }
  }
}
