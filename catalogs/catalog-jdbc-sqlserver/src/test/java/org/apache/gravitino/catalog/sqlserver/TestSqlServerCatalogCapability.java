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
package org.apache.gravitino.catalog.sqlserver;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit test for {@link SqlServerCatalogCapability}. */
public class TestSqlServerCatalogCapability {

  private final SqlServerCatalogCapability capability = new SqlServerCatalogCapability();

  @Test
  void testValidNames() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      assertSupported(scope, "test_table");
      assertSupported(scope, "TestTable123");
      assertSupported(scope, "table_with_underscores");
      // SQL Server regular identifiers may start with an underscore, @ or #.
      assertSupported(scope, "_test_table");
      assertSupported(scope, "@variable_style");
      assertSupported(scope, "#temp_style");
      // Unicode letters are valid identifier characters.
      assertSupported(scope, "测试表");
      assertSupported(scope, "tableção");
      // Identifiers are limited to 128 characters.
      assertSupported(scope, "a".repeat(128));
    }
  }

  @Test
  void testInvalidNames() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      assertIllegal(scope, "");
      // 129 characters exceeds the identifier limit.
      assertIllegal(scope, "a".repeat(129));
      // A regular identifier may not start with a digit.
      assertIllegal(scope, "123table");
      // Separators and punctuation are only usable inside delimited identifiers.
      assertIllegal(scope, "table.name");
      assertIllegal(scope, "table/name");
      assertIllegal(scope, "table name");
      assertIllegal(scope, "table$name-start");
    }
  }

  /**
   * {@code dbo} is the default user schema of every SQL Server database and holds real tables, so
   * it must stay usable as a Gravitino schema name even though creating it will report that it
   * already exists.
   */
  @Test
  void testDboIsNotReserved() {
    Assertions.assertTrue(
        capability.specificationOnName(Capability.Scope.SCHEMA, "dbo").supported());
  }

  @Test
  void testReservedSchemaNames() {
    assertReserved(Capability.Scope.SCHEMA, "sys");
    assertReserved(Capability.Scope.SCHEMA, "information_schema");
    assertReserved(Capability.Scope.SCHEMA, "guest");
    // Fixed database roles are reported alongside schemas by the driver and cannot be created.
    assertReserved(Capability.Scope.SCHEMA, "db_owner");
    assertReserved(Capability.Scope.SCHEMA, "db_datareader");
    // The match is case insensitive, as SQL Server identifier comparison is.
    assertReserved(Capability.Scope.SCHEMA, "SYS");
    assertReserved(Capability.Scope.SCHEMA, "Information_Schema");
  }

  /** Reserved words only constrain schemas; a table or column may reuse the name. */
  @Test
  void testReservedNamesOnlyApplyToSchemas() {
    Assertions.assertTrue(
        capability.specificationOnName(Capability.Scope.TABLE, "sys").supported());
    Assertions.assertTrue(
        capability.specificationOnName(Capability.Scope.COLUMN, "information_schema").supported());
  }

  private void assertSupported(Capability.Scope scope, String name) {
    Assertions.assertTrue(
        capability.specificationOnName(scope, name).supported(),
        () -> String.format("%s name '%s' should be supported", scope, name));
  }

  private void assertIllegal(Capability.Scope scope, String name) {
    CapabilityResult result = capability.specificationOnName(scope, name);
    Assertions.assertFalse(
        result.supported(), () -> String.format("%s name '%s' should be illegal", scope, name));
    String message = result.unsupportedMessage();
    Assertions.assertTrue(message.contains("illegal"), message);
  }

  private void assertReserved(Capability.Scope scope, String name) {
    CapabilityResult result = capability.specificationOnName(scope, name);
    Assertions.assertFalse(
        result.supported(), () -> String.format("%s name '%s' should be reserved", scope, name));
    String message = result.unsupportedMessage();
    Assertions.assertTrue(message.contains("reserved"), message);
  }
}
