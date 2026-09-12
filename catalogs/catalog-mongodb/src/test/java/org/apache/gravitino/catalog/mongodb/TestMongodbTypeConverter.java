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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Test;

public class TestMongodbTypeConverter {

  private final MongodbTypeConverter converter = MongodbTypeConverter.INSTANCE;

  @Test
  public void testToGravitino() {
    assertEquals(Types.BooleanType.get(), converter.toGravitino("bool"));
    assertEquals(Types.IntegerType.get(), converter.toGravitino("int"));
    assertEquals(Types.LongType.get(), converter.toGravitino("long"));
    assertEquals(Types.DoubleType.get(), converter.toGravitino("double"));
    assertEquals(Types.StringType.get(), converter.toGravitino("string"));
    assertEquals(Types.TimestampType.withoutTimeZone(), converter.toGravitino("date"));
    assertEquals(Types.BinaryType.get(), converter.toGravitino("binData"));
    assertTrue(converter.toGravitino("objectId") instanceof Types.ExternalType);
  }

  @Test
  public void testFromGravitino() {
    assertEquals("bool", converter.fromGravitino(Types.BooleanType.get()));
    assertEquals("int", converter.fromGravitino(Types.IntegerType.get()));
    assertEquals("long", converter.fromGravitino(Types.LongType.get()));
    assertEquals("double", converter.fromGravitino(Types.DoubleType.get()));
    assertEquals("string", converter.fromGravitino(Types.StringType.get()));
    assertEquals("date", converter.fromGravitino(Types.TimestampType.withoutTimeZone()));
    assertEquals("binData", converter.fromGravitino(Types.BinaryType.get()));
    assertEquals("decimal", converter.fromGravitino(Types.DecimalType.of(10, 2)));
  }

  @Test
  public void testRoundTrip() {
    // Test that converting to Gravitino and back gives a reasonable result
    Type gravitinoType = converter.toGravitino("int");
    String bsonType = converter.fromGravitino(gravitinoType);
    assertEquals("int", bsonType);
  }
}
