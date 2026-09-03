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
package org.apache.gravitino.catalog.sqlserver.converter;

import java.sql.SQLException;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchColumnException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;

/**
 * Maps SQL Server errors onto Gravitino exceptions.
 *
 * <p>SQL Server reports the same SQLSTATE for many distinct failures, so the mapping keys off the
 * vendor error code instead. The codes used here are the documented {@code sys.messages} values.
 */
public class SqlServerExceptionConverter extends JdbcExceptionConverter {

  /** There is already an object with this name in the database. */
  private static final int OBJECT_ALREADY_EXISTS = 2714;

  /** User, group, or role already exists in the current database. */
  private static final int PRINCIPAL_ALREADY_EXISTS = 15023;

  /** Invalid object name; raised for a missing table or a missing schema. */
  private static final int INVALID_OBJECT_NAME = 208;

  /** Invalid column name. */
  private static final int INVALID_COLUMN_NAME = 207;

  /** Login failed for the supplied user. */
  private static final int LOGIN_FAILED = 18456;

  /** Cannot open the requested database. */
  private static final int CANNOT_OPEN_DATABASE = 4060;

  /** SQLSTATE class 08 covers connection exceptions. */
  private static final String CONNECTION_EXCEPTION_PREFIX = "08";

  @SuppressWarnings("FormatStringAnnotation")
  @Override
  public GravitinoRuntimeException toGravitinoException(SQLException se) {
    switch (se.getErrorCode()) {
      case OBJECT_ALREADY_EXISTS:
        return new TableAlreadyExistsException(se.getMessage(), se);
      case PRINCIPAL_ALREADY_EXISTS:
        return new SchemaAlreadyExistsException(se.getMessage(), se);
      case INVALID_OBJECT_NAME:
        return new NoSuchTableException(se.getMessage(), se);
      case INVALID_COLUMN_NAME:
        return new NoSuchColumnException(se.getMessage(), se);
      case LOGIN_FAILED:
      case CANNOT_OPEN_DATABASE:
        return new ConnectionFailedException(se.getMessage(), se);
      default:
        break;
    }

    String sqlState = se.getSQLState();
    if (sqlState != null && sqlState.startsWith(CONNECTION_EXCEPTION_PREFIX)) {
      return new ConnectionFailedException(se.getMessage(), se);
    }
    if (se.getMessage() != null && se.getMessage().contains("Login failed")) {
      return new ConnectionFailedException(se.getMessage(), se);
    }
    return new GravitinoRuntimeException(se.getMessage(), se);
  }

  /**
   * Exposed so schema creation can turn SQL Server's ambiguous "object already exists" error into a
   * schema specific exception before it reaches the table mapping above.
   *
   * @param schemaName the schema that was found to exist already
   * @return the exception to raise
   */
  public static SchemaAlreadyExistsException schemaAlreadyExists(String schemaName) {
    return new SchemaAlreadyExistsException("Schema %s already exists", schemaName);
  }

  /**
   * Exposed so schema loading can report a missing schema rather than the ambiguous "invalid object
   * name" error SQL Server produces.
   *
   * @param schemaName the schema that could not be found
   * @return the exception to raise
   */
  public static NoSuchSchemaException noSuchSchema(String schemaName) {
    return new NoSuchSchemaException("No such schema: %s", schemaName);
  }
}
