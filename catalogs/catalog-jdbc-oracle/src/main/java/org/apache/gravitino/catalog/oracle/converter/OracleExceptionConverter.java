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
package org.apache.gravitino.catalog.oracle.converter;

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
 * Maps Oracle errors onto Gravitino exceptions.
 *
 * <p>The driver reports SQLSTATE {@code 72000} for almost every server side failure, so the mapping
 * keys off the numeric {@code ORA-} code carried by {@link SQLException#getErrorCode()} instead.
 */
public class OracleExceptionConverter extends JdbcExceptionConverter {

  /** ORA-00955: name is already used by an existing object. */
  private static final int NAME_ALREADY_USED = 955;

  /** ORA-01920: user name conflicts with another user or role name. */
  private static final int USER_ALREADY_EXISTS = 1920;

  /** ORA-00942: table or view does not exist. Also raised for an unresolvable schema qualifier. */
  private static final int TABLE_OR_VIEW_DOES_NOT_EXIST = 942;

  /** ORA-01918: user does not exist. Raised when a schema cannot be resolved. */
  private static final int USER_DOES_NOT_EXIST = 1918;

  /** ORA-00904: invalid identifier. Raised for an unknown column. */
  private static final int INVALID_IDENTIFIER = 904;

  /** ORA-01017: invalid username or password; logon denied. */
  private static final int LOGON_DENIED = 1017;

  /** ORA-12154: TNS could not resolve the connect identifier specified. */
  private static final int TNS_UNRESOLVED = 12154;

  /** ORA-12541: TNS no listener. */
  private static final int NO_LISTENER = 12541;

  /** ORA-12514: listener does not currently know of the requested service. */
  private static final int UNKNOWN_SERVICE = 12514;

  /** SQLSTATE class 08 covers connection exceptions. */
  private static final String CONNECTION_EXCEPTION_PREFIX = "08";

  @SuppressWarnings("FormatStringAnnotation")
  @Override
  public GravitinoRuntimeException toGravitinoException(SQLException se) {
    switch (se.getErrorCode()) {
      case NAME_ALREADY_USED:
        return new TableAlreadyExistsException(se.getMessage(), se);
      case USER_ALREADY_EXISTS:
        return new SchemaAlreadyExistsException(se.getMessage(), se);
      case TABLE_OR_VIEW_DOES_NOT_EXIST:
        return new NoSuchTableException(se.getMessage(), se);
      case USER_DOES_NOT_EXIST:
        return new NoSuchSchemaException(se.getMessage(), se);
      case INVALID_IDENTIFIER:
        return new NoSuchColumnException(se.getMessage(), se);
      case LOGON_DENIED:
      case TNS_UNRESOLVED:
      case NO_LISTENER:
      case UNKNOWN_SERVICE:
        return new ConnectionFailedException(se.getMessage(), se);
      default:
        break;
    }

    String sqlState = se.getSQLState();
    if (sqlState != null && sqlState.startsWith(CONNECTION_EXCEPTION_PREFIX)) {
      return new ConnectionFailedException(se.getMessage(), se);
    }
    return new GravitinoRuntimeException(se.getMessage(), se);
  }

  /**
   * Exposed so schema creation can raise a schema specific exception. Oracle reports a duplicate
   * schema as ORA-00955, the same code it uses for a duplicate table, which the mapping above has
   * to read as a table conflict.
   *
   * @param schemaName the schema that was found to exist already
   * @return the exception to raise
   */
  public static SchemaAlreadyExistsException schemaAlreadyExists(String schemaName) {
    return new SchemaAlreadyExistsException("Schema %s already exists", schemaName);
  }

  /**
   * Exposed so schema loading can report a missing schema rather than the ambiguous ORA-00942
   * Oracle raises when a schema qualifier cannot be resolved.
   *
   * @param schemaName the schema that could not be found
   * @return the exception to raise
   */
  public static NoSuchSchemaException noSuchSchema(String schemaName) {
    return new NoSuchSchemaException("No such schema: %s", schemaName);
  }
}
