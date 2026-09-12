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

import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MongodbConnectorAdapter}. */
public class TestMongodbConnectorAdapter {

  private static final String MONGODB_URI = "mongodb://localhost:27017/testDB?authSource=admin";

  private final MongodbConnectorAdapter adapter = new MongodbConnectorAdapter();

  private GravitinoCatalog catalogWithProps(Map<String, String> props) {
    return new GravitinoCatalog("metalake", "mongodb", "test", props, 0L);
  }

  @Test
  void testInternalConnectorName() {
    Assertions.assertEquals("mongodb", adapter.internalConnectorName());
  }

  @Test
  void testMetadataAdapterUsesTheMongodbTransformer() {
    Assertions.assertTrue(adapter.getMetadataAdapter() instanceof MongodbMetadataAdapter);
  }

  @Test
  void testBuildConfigConvertsMongoDbUri() throws Exception {
    GravitinoCatalog catalog =
        catalogWithProps(Collections.singletonMap("mongodb.uri", MONGODB_URI));

    Map<String, String> config = adapter.buildInternalConnectorConfig(catalog, new Credential[0]);

    Assertions.assertEquals(MONGODB_URI, config.get("connection-uri"));
  }

  @Test
  void testBuildConfigFailsWithoutUri() {
    GravitinoCatalog catalog = catalogWithProps(Collections.emptyMap());

    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> adapter.buildInternalConnectorConfig(catalog, new Credential[0]));
  }
}
