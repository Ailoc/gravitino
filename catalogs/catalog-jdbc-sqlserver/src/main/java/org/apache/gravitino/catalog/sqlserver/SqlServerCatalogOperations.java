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

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.apache.gravitino.catalog.jdbc.JdbcCatalogOperations;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;

/** Catalog operations for SQL Server. */
public class SqlServerCatalogOperations extends JdbcCatalogOperations {

  /**
   * Creates catalog operations for SQL Server.
   *
   * @param exceptionConverter converts SQLExceptions into Gravitino exceptions
   * @param jdbcTypeConverter converts between SQL Server and Gravitino types
   * @param databaseOperation manages schemas
   * @param tableOperation manages tables
   * @param columnDefaultValueConverter converts column default values
   */
  public SqlServerCatalogOperations(
      JdbcExceptionConverter exceptionConverter,
      JdbcTypeConverter jdbcTypeConverter,
      JdbcDatabaseOperations databaseOperation,
      JdbcTableOperations tableOperation,
      JdbcColumnDefaultValueConverter columnDefaultValueConverter) {
    super(
        exceptionConverter,
        jdbcTypeConverter,
        databaseOperation,
        tableOperation,
        columnDefaultValueConverter);
  }

  /**
   * The base implementation resolves the driver from the configured URL, but the URL is not
   * available at this point. Resolving against a syntactically valid dummy URL identifies the
   * registered SQL Server driver instead.
   *
   * @return the SQL Server JDBC driver
   * @throws SQLException if no registered driver accepts the URL
   */
  @Override
  protected Driver getDriver() throws SQLException {
    return DriverManager.getDriver("jdbc:sqlserver://localhost:1433");
  }
}
