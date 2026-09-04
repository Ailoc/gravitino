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

import org.apache.gravitino.catalog.oracle.operation.OracleSchemaOperations;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

/** Declares the naming rules Oracle imposes on schemas, tables and columns. */
public class OracleCatalogCapability implements Capability {

  /**
   * Regular expression explanation: {@code ^[a-zA-Z][a-zA-Z0-9_$#]{0,127}$}
   *
   * <p>{@code ^[a-zA-Z]} - an Oracle identifier must start with a letter
   *
   * <p>{@code [a-zA-Z0-9_$#]{0,127}} - followed by up to 127 letters, digits, underscores, {@code
   * $} or {@code #}, giving the 128 byte limit Oracle applies from release 12.2 onwards. Earlier
   * releases cap an identifier at 30 bytes and reject a longer one with ORA-00972.
   *
   * <p>{@code $} - end of the string
   *
   * <p>Quoted identifiers could allow more, but Gravitino rejects names outside this pattern so
   * that identifiers stay portable across engines.
   */
  public static final String ORACLE_NAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9_$#]{0,127}$";

  /**
   * Oracle folds an unquoted identifier to upper case, but every statement this catalog generates
   * quotes its identifiers, so a name keeps the exact case it was created with. The inherited
   * case-sensitive behaviour and the identity name normalization that goes with it are therefore
   * correct here and are left alone.
   *
   * @param scope the scope the name belongs to
   * @param name the name to check
   * @return whether the name may be used
   */
  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    if (!name.matches(ORACLE_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is illegal.", scope, name));
    }

    if (scope == Scope.SCHEMA && OracleSchemaOperations.isReservedSchema(name)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is reserved and cannot be used.", scope, name));
    }

    return CapabilityResult.SUPPORTED;
  }
}
