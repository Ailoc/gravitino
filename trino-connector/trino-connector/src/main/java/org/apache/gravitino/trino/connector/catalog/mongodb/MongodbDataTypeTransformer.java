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

import io.trino.spi.TrinoException;
import io.trino.spi.type.CharType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Type.Name;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.trino.connector.GravitinoErrorCode;
import org.apache.gravitino.trino.connector.util.GeneralDataTypeTransformer;

/** Type transformer between MongoDB and Trino. */
public class MongodbDataTypeTransformer extends GeneralDataTypeTransformer {

  @Override
  public io.trino.spi.type.Type getTrinoType(Type type) {
    if (type.name() == Name.STRING) {
      return VarcharType.createUnboundedVarcharType();
    }

    return super.getTrinoType(type);
  }

  @Override
  public Type getGravitinoType(io.trino.spi.type.Type type) {
    Class<? extends io.trino.spi.type.Type> typeClass = type.getClass();

    if (typeClass == CharType.class) {
      return Types.StringType.get();
    } else if (typeClass == VarcharType.class) {
      return Types.StringType.get();
    } else if (TimeType.class.isAssignableFrom(typeClass)) {
      throw new TrinoException(
          GravitinoErrorCode.GRAVITINO_UNSUPPORTED_TRINO_DATATYPE,
          "MongoDB does not support the datatype TIME");
    }

    return super.getGravitinoType(type);
  }
}
