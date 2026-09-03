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

import java.util.Locale;
import org.apache.gravitino.catalog.sqlserver.operation.SqlServerSchemaOperations;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

/** Declares the naming rules SQL Server imposes on schemas, tables and columns. */
public class SqlServerCatalogCapability implements Capability {

  /**
   * Regular expression explanation: {@code ^[a-zA-Z_\p{L}@#][\w\p{L}$@#]{0,127}$}
   *
   * <p>{@code ^[a-zA-Z_\p{L}@#]} - a regular identifier must start with a letter (of any language),
   * an underscore, {@code @} or {@code #}
   *
   * <p>{@code [\w\p{L}$@#]{0,127}} - followed by up to 127 characters of letters, digits,
   * underscores, {@code $}, {@code @} or {@code #}, giving the 128 character limit SQL Server
   * enforces on identifiers
   *
   * <p>{@code $} - end of the string
   *
   * <p>Delimited (bracket quoted) identifiers could allow more, but Gravitino rejects names outside
   * this pattern so that identifiers stay portable across engines.
   */
  public static final String SQLSERVER_NAME_PATTERN = "^[a-zA-Z_\\p{L}@#][\\w\\p{L}$@#]{0,127}$";

  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    if (!name.matches(SQLSERVER_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is illegal.", scope, name));
    }

    if (scope == Scope.SCHEMA
        && SqlServerSchemaOperations.SYSTEM_SCHEMAS.contains(name.toLowerCase(Locale.ROOT))) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is reserved and cannot be used.", scope, name));
    }

    return CapabilityResult.SUPPORTED;
  }
}
