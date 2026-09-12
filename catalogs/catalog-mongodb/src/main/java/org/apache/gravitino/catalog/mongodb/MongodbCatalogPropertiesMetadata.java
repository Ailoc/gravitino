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

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.connector.BaseCatalogPropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

public class MongodbCatalogPropertiesMetadata extends BaseCatalogPropertiesMetadata {

  public static final String MONGODB_URI = "mongodb.uri";

  private static final Map<String, PropertyEntry<?>> MONGODB_CATALOG_PROPERTY_ENTRIES =
      ImmutableMap.<String, PropertyEntry<?>>builder()
          .put(
              MONGODB_URI,
              PropertyEntry.stringRequiredPropertyEntry(
                  MONGODB_URI,
                  "The MongoDB connection URI",
                  false /* immutable */,
                  false /* hidden */))
          .build();

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return MONGODB_CATALOG_PROPERTY_ENTRIES;
  }
}
