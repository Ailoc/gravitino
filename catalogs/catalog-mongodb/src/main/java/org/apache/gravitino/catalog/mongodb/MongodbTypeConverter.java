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

import org.apache.gravitino.connector.DataTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

public class MongodbTypeConverter implements DataTypeConverter<String, String> {

  public static final MongodbTypeConverter INSTANCE = new MongodbTypeConverter();

  @Override
  public String fromGravitino(Type type) {
    if (type instanceof Types.BooleanType) {
      return "bool";
    } else if (type instanceof Types.ByteType || type instanceof Types.ShortType) {
      return "int";
    } else if (type instanceof Types.IntegerType) {
      return "int";
    } else if (type instanceof Types.LongType) {
      return "long";
    } else if (type instanceof Types.FloatType || type instanceof Types.DoubleType) {
      return "double";
    } else if (type instanceof Types.DecimalType) {
      return "decimal";
    } else if (type instanceof Types.StringType
        || type instanceof Types.VarCharType
        || type instanceof Types.FixedCharType) {
      return "string";
    } else if (type instanceof Types.DateType) {
      return "date";
    } else if (type instanceof Types.TimestampType) {
      return "date";
    } else if (type instanceof Types.TimeType) {
      return "string";
    } else if (type instanceof Types.BinaryType || type instanceof Types.FixedType) {
      return "binData";
    } else if (type instanceof Types.UUIDType) {
      return "string";
    } else if (type instanceof Types.ListType) {
      return "array";
    } else if (type instanceof Types.StructType) {
      return "object";
    } else if (type instanceof Types.MapType) {
      return "object";
    } else if (type instanceof Types.ExternalType) {
      return ((Types.ExternalType) type).catalogString();
    }
    throw new UnsupportedOperationException("Unsupported Gravitino type: " + type);
  }

  @Override
  public Type toGravitino(String bsonType) {
    switch (bsonType.toLowerCase()) {
      case "bool":
        return Types.BooleanType.get();
      case "int":
        return Types.IntegerType.get();
      case "long":
        return Types.LongType.get();
      case "double":
        return Types.DoubleType.get();
      case "decimal":
        return Types.DecimalType.of(34, 34);
      case "string":
        return Types.StringType.get();
      case "date":
        return Types.TimestampType.withoutTimeZone();
      case "timestamp":
        return Types.TimestampType.withTimeZone();
      case "objectid":
        return Types.ExternalType.of("objectId");
      case "object":
        return Types.StructType.of();
      case "array":
        return Types.ListType.nullable(Types.StringType.get());
      case "bindata":
        return Types.BinaryType.get();
      case "regex":
        return Types.ExternalType.of("regex");
      case "minkey":
        return Types.ExternalType.of("minKey");
      case "maxkey":
        return Types.ExternalType.of("maxKey");
      default:
        return Types.ExternalType.of(bsonType);
    }
  }
}
