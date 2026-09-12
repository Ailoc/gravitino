---
title: "MongoDB Catalog"
slug: "/mongodb-catalog"
keywords:
- mongodb
- nosql
- metadata
license: "This software is licensed under the Apache License version 2."
---

## Introduction

Apache Gravitino provides the ability to manage MongoDB metadata.

:::caution
Gravitino stores schema information in a `$jsonSchema` collection validator. Do not modify or remove this validator manually.
:::

## Catalog

### Catalog Capabilities

- Gravitino catalog corresponds to a MongoDB deployment (standalone or replica set) identified by `mongodb.uri`.
- Supports metadata management of MongoDB 5.0 and later.
- Supports DDL operations for MongoDB databases (schemas) and collections (tables).
- Doesn't support column default values, auto-increment, indexes, table partitioning, distribution, or sort orders.
- Alter table operations are limited.

### Catalog Properties

Besides the [common catalog properties](./gravitino-server-config.md#catalog-properties-configuration), the MongoDB catalog has the following properties:

| Configuration item | Description                                                                         | Default value | Required |
|--------------------|-------------------------------------------------------------------------------------|---------------|----------|
| `mongodb.uri`      | MongoDB connection URI, for example `mongodb://localhost:27017` or `mongodb://user:pass@host:27017/db`. | (none)        | Yes      |

### Catalog Operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#catalog-operations) for more details.

## Schema

### Schema Capabilities

- Gravitino schema corresponds to a MongoDB database.
- Doesn't support creating schema with comments. MongoDB has no database-level metadata, so a Gravitino schema carries no user-supplied comment.
- Supports dropping schema. Dropping a schema drops the MongoDB database.
- Supports cascade dropping schema. When `cascade` is `false`, dropping a non-empty schema throws `NonEmptySchemaException`.

Creating a schema creates the MongoDB database by writing a temporary collection and removing it, since MongoDB creates databases lazily on first write.

`listSchemas` returns all database names visible to the connection, including system databases such as `admin`, `local`, and `config`.

### Schema Properties

- Doesn't support any schema property settings.

### Schema Operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#schema-operations) for more details.

## Table

### Table Capabilities

- The Gravitino table corresponds to a MongoDB collection.
- Supports creating a collection with a `$jsonSchema` validator that encodes column names, BSON types, nullability, and comments.
- Doesn't support table property settings.
- Doesn't support table partitioning, distribution, or sort orders.
- Doesn't support table indexes.
- Alter table is limited and returns the existing table without modification.

### Table Column Types

| Gravitino Type  | BSON Type   |
|-----------------|-------------|
| `Boolean`       | `bool`      |
| `Byte`          | `int`       |
| `Short`         | `int`       |
| `Integer`       | `int`       |
| `Long`          | `long`      |
| `Float`         | `double`    |
| `Double`        | `double`    |
| `Decimal(p,s)`  | `decimal`   |
| `String`        | `string`    |
| `VarChar(n)`    | `string`    |
| `FixedChar(n)`  | `string`    |
| `Binary`        | `binData`   |
| `Fixed(n)`      | `binData`   |
| `Date`          | `date`      |
| `Timestamp`     | `date`      |
| `Time`          | `string`    |
| `UUID`          | `string`    |
| `List`          | `array`     |
| `Struct`        | `object`    |
| `Map`           | `object`    |

Reading types back from MongoDB:

| BSON Type   | Gravitino Type              |
|-------------|-----------------------------|
| `bool`      | `Boolean`                   |
| `int`       | `Integer`                   |
| `long`      | `Long`                      |
| `double`    | `Double`                    |
| `decimal`   | `Decimal(34,34)`            |
| `string`    | `String`                    |
| `date`      | `Timestamp.withoutTimeZone` |
| `timestamp` | `Timestamp.withTimeZone`    |
| `binData`   | `Binary`                    |
| `array`     | `List(String)`              |
| `object`    | `Struct`                    |
| `objectId`  | [External Type](./tables-and-views.md#external-type) |
| `regex`     | [External Type](./tables-and-views.md#external-type) |

:::info
MongoDB has fewer distinct numeric types than Gravitino. `Byte`, `Short`, and `Integer` all map to BSON `int`, so they all read back as `Integer`. `Float` and `Double` both map to BSON `double` and read back as `Double`. `Date` and `Timestamp` both map to BSON `date` and read back as `Timestamp.withoutTimeZone`.
:::

### Table Column Nullability

Non-nullable columns are stored in the `required` array of the `$jsonSchema` validator. Nullable columns are omitted from `required`.

### Table Column Comments

Column comments are stored as the `description` field in each property of the `$jsonSchema` validator.

### Table Properties

- Doesn't support table properties.

### Table Operations

Refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#table-operations) for more details.

#### Alter Table Operations

Alter table is limited for MongoDB. The catalog accepts alter table calls but returns the existing table without modification, since MongoDB collections do not support structured schema alterations through DDL.
