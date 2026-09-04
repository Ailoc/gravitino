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
package org.apache.gravitino.catalog.oracle;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit test for {@link OracleCatalogCapability}. */
public class TestOracleCatalogCapability {

  private final OracleCatalogCapability capability = new OracleCatalogCapability();

  @Test
  void testValidNames() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      assertSupported(scope, "test_table");
      assertSupported(scope, "TestTable123");
      assertSupported(scope, "table_with_underscores");
      // $ and # are legal identifier characters in Oracle, though $ is reserved for system use.
      assertSupported(scope, "price$");
      assertSupported(scope, "tmp#1");
      // Identifiers are limited to 128 bytes from release 12.2 onwards.
      assertSupported(scope, "a".repeat(128));
    }
  }

  @Test
  void testInvalidNames() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      assertIllegal(scope, "");
      assertIllegal(scope, "a".repeat(129));
      // Unlike SQL Server, an Oracle identifier must start with a letter.
      assertIllegal(scope, "123table");
      assertIllegal(scope, "_test_table");
      assertIllegal(scope, "#temp_style");
      assertIllegal(scope, "table.name");
      assertIllegal(scope, "table name");
      assertIllegal(scope, "table-name");
    }
  }

  /**
   * The pattern is deliberately ASCII only. Oracle itself accepts a quoted identifier in the
   * database character set, but Gravitino rejects anything outside it so that a name stays portable
   * to engines that cannot represent it.
   */
  @Test
  void testNonAsciiNamesAreRejected() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      assertIllegal(scope, "测试表");
      assertIllegal(scope, "tableção");
    }
  }

  @Test
  void testReservedSchemaNames() {
    assertReserved(Capability.Scope.SCHEMA, "sys");
    assertReserved(Capability.Scope.SCHEMA, "system");
    assertReserved(Capability.Scope.SCHEMA, "xdb");
    assertReserved(Capability.Scope.SCHEMA, "sys$umf");
    // The match is case insensitive, since Oracle stores an unquoted name folded to upper case.
    assertReserved(Capability.Scope.SCHEMA, "SYS");
    assertReserved(Capability.Scope.SCHEMA, "Xdb");
  }

  /**
   * APEX installs one schema per release, named {@code APEX_} followed by six digits, so the set of
   * reserved names cannot be enumerated ahead of time and has to be matched on shape.
   */
  @Test
  void testVersionedApexSchemasAreReserved() {
    assertReserved(Capability.Scope.SCHEMA, "APEX_040200");
    assertReserved(Capability.Scope.SCHEMA, "apex_050100");
  }

  @Test
  void testApexLookAlikesAreNotReserved() {
    // Five digits is not a release schema.
    assertSupported(Capability.Scope.SCHEMA, "APEX_04020");
    assertSupported(Capability.Scope.SCHEMA, "apex_reports");
    assertSupported(Capability.Scope.SCHEMA, "sales");
  }

  /** Reserved names only constrain schemas; a table or column may reuse the name. */
  @Test
  void testReservedNamesOnlyApplyToSchemas() {
    Assertions.assertTrue(
        capability.specificationOnName(Capability.Scope.TABLE, "sys").supported());
    Assertions.assertTrue(
        capability.specificationOnName(Capability.Scope.COLUMN, "APEX_040200").supported());
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
