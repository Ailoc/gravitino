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

package org.apache.gravitino.trino.connector.catalog.mongodb;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.commons.collections4.bidimap.TreeBidiMap;
import org.apache.gravitino.trino.connector.catalog.CatalogPropertyConverter;

/**
 * Property converter for MongoDB catalog properties. Handles the conversion of property keys
 * between Trino and Gravitino formats for the MongoDB connector.
 */
public class MongodbCatalogPropertyConverter extends CatalogPropertyConverter {

  /** Property key for the MongoDB connection URI in Trino's connector configuration. */
  public static final String TRINO_CONNECTION_URI_KEY = "connection-uri";

  /** Property key for the MongoDB connection URI in Gravitino's catalog configuration. */
  public static final String GRAVITINO_MONGODB_URI_KEY = "mongodb.uri";

  private static final TreeBidiMap<String, String> TRINO_KEY_TO_GRAVITINO_KEY =
      new TreeBidiMap<>(ImmutableMap.of(TRINO_CONNECTION_URI_KEY, GRAVITINO_MONGODB_URI_KEY));

  @Override
  public TreeBidiMap<String, String> engineToGravitinoMapping() {
    return TRINO_KEY_TO_GRAVITINO_KEY;
  }

  @Override
  public Map<String, String> gravitinoToEngineProperties(Map<String, String> properties) {
    Map<String, String> trinoProperties = super.gravitinoToEngineProperties(properties);
    if (!trinoProperties.containsKey(TRINO_CONNECTION_URI_KEY)) {
      throw new IllegalArgumentException("Missing required property: " + TRINO_CONNECTION_URI_KEY);
    }
    return trinoProperties;
  }
}
