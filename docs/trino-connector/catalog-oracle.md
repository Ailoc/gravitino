---
title: "Trino Connector: Oracle Catalog"
slug: "/trino-connector/catalog-oracle"
keyword: "gravitino connector trino"
license: "This software is licensed under the Apache License version 2."
---

## Introduction

The Oracle catalog allows querying and creating tables in an external Oracle database.
This can be used to join data between different systems like Oracle and Hive, or between different Oracle instances.

## Requirements

To connect to Oracle, you need:
- Oracle 23 or higher, which is what the Trino Oracle connector requires. The Gravitino Oracle catalog itself supports Oracle 12.2 and later.
- Network access from the Trino coordinator and workers to Oracle. Port 1521 is the default port.
- The Trino Oracle connector plugin installed on every Trino node, which registers the catalog under the name `oracle`.
- The Oracle JDBC driver in the Gravitino server's `catalogs/jdbc-oracle/libs` directory. The driver is not distributed with Gravitino.

## Case Sensitivity

Oracle folds an unquoted identifier to upper case, but every statement the Gravitino Oracle catalog
generates quotes its identifiers, so a name keeps the exact case it was created with.

Trino folds an unquoted identifier to lower case before it reaches the connector. A table created as
`MyTable` therefore does not match the `mytable` that an unquoted Trino query looks for. Create schemas,
tables and columns with lower-case names, or set the catalog property `trino.bypass.case-insensitive-name-matching`
to `true`.

## Create Table

At present, the Apache Gravitino Trino connector only supports basic Oracle table creation statements, which involve fields, null allowances, and comments. However, it does not support advanced features like primary keys, indexes, default values, and auto-increment.
The Gravitino Trino connector supports `CREATE TABLE AS SELECT`.

:::note
`CREATE OR REPLACE TABLE AS SELECT` is not supported. Use `DROP TABLE` followed by `CREATE TABLE AS SELECT` as an alternative.
:::

A new column is always appended at the end of the table. Oracle cannot place a column at a given position.

## Alter Table

Gravitino Trino connector supports the following alter table operations:
- Rename table
- Add a column
- Drop a column
- Rename a column
- Change a column type
- Set a table comment
- Set a column comment

:::note
Setting a table property is not supported. Oracle tables carry no properties that Gravitino can alter, so
`ALTER TABLE ... SET PROPERTIES` is rejected by the Oracle catalog.
:::

## Select

The Gravitino Trino connector supports most SELECT statements, allowing the execution of queries successfully.
It doesn't support certain query optimizations, such as indexes and pushdowns.

## Update

Only `UPDATE` statements with constant assignments and predicates are supported. See also [UPDATE limitation](https://trino.io/docs/current/connector/oracle.html#update-limitation).

## Delete

If the `WHERE` clause is specified, only the matching rows are deleted. Otherwise, all rows from the table are deleted. See also [DELETE limitation](https://trino.io/docs/current/connector/oracle.html#delete-limitation).

## Merge

Not support.

## Table and Schema Properties

Oracle's tables and schemas cannot support properties.

Oracle has no `COMMENT ON SCHEMA` statement, so a schema created through Trino carries no comment either.

:::caution
Oracle treats an empty string as `NULL`, so a column written with `''` reads back as `NULL`.
:::

## Data Types

Oracle's `DATE` carries a time of day and surfaces in Trino as `timestamp(0)`, not as `date`. Oracle has no
time-of-day type, so creating a Trino `TIME` column in an Oracle catalog is rejected. The fractional-second
precision of `TIMESTAMP` and `TIMESTAMP WITH TIME ZONE` is capped at 9, which is lower than the 12 Trino allows.
A Gravitino string type maps to an unbounded Trino `VARCHAR`, which Oracle stores as a `CLOB`.

For the full mapping, refer to the [Oracle catalog](../jdbc-oracle-catalog.md#table-column-types).

## Examples

Complete the following steps before you can use the Oracle catalog in Trino through Gravitino:

- Create a metalake and catalog in Gravitino. Assuming that the metalake name is `test` and the catalog name is `oracle_test`, then you can use the following code to create them in Gravitino:

```bash
curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "test",
  "comment": "comment",
  "properties": {}
}' http://gravitino-host:8090/api/metalakes

curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "oracle_test",
  "type": "RELATIONAL",
  "comment": "comment",
  "provider": "jdbc-oracle",
  "properties": {
    "jdbc-url": "jdbc:oracle:thin:@//oracle-host:1521/ORCLPDB1",
    "jdbc-user": "<user>",
    "jdbc-password": "<password>",
    "jdbc-driver": "oracle.jdbc.OracleDriver"
  }
}' http://gravitino-host:8090/api/metalakes/test/catalogs
```

Oracle has no catalog level between the database and the schema, so the `jdbc-database` property is not used.
The service or PDB is part of the `jdbc-url` instead.

For more information about the Oracle catalog, refer to [Oracle catalog](../jdbc-oracle-catalog.md).

- Set the value of configuration `gravitino.metalake` to the metalake you have created, named 'test', and start the Trino container.

Use the Trino CLI to connect to the Trino container and run a query.

Listing all Gravitino managed catalogs:

```sql
SHOW CATALOGS;
```

The results are similar to:

```text
    Catalog
----------------
 gravitino
 jmx
 system
 oracle_test
(4 rows)

Query 20231017_082503_00018_6nt3n, FINISHED, 1 node
```

The `gravitino` catalog is a catalog defined By Trino catalog configuration.
The `oracle_test` catalog is the catalog created by you in Gravitino.
Other catalogs are regular user-configured Trino catalogs.

### Create Tables and Schemas

Create a new schema named `schema_01` in `oracle_test` catalog.

```sql
CREATE SCHEMA oracle_test.schema_01;
```

Creating a schema issues `CREATE USER "schema_01" NO AUTHENTICATION`, followed by a grant of an unlimited
quota on the default tablespace. Both statements commit implicitly, so a failure between them can leave a
schema without a quota, and every `CREATE TABLE` in it then fails with `ORA-01950`. Grant the quota manually
if that happens.

Create a new table named `table_01` in schema `oracle_test.schema_01`.

```sql
CREATE TABLE oracle_test.schema_01.table_01
(
name varchar,
salary int
);
```

### Write Data

Insert data into the table `table_01`:

```sql
INSERT INTO oracle_test.schema_01.table_01 (name, salary) VALUES ('ice', 12);
```

Insert data into the table `table_01` from select:

```sql
INSERT INTO oracle_test.schema_01.table_01 (name, salary) SELECT * FROM oracle_test.schema_01.table_01;
```

Update data into the table `table_01`:

```sql
UPDATE oracle_test.schema_01.table_01 SET name = 'ice_update' WHERE salary = 12;
```

Delete data from the table `table_01`:

```sql
DELETE FROM oracle_test.schema_01.table_01 WHERE salary = 12;
DELETE FROM oracle_test.schema_01.table_01;
```

### Query Data

Query the `table_01` table:

```sql
SELECT * FROM oracle_test.schema_01.table_01;
```

### Modify a Table

Add a new column `age` to the `table_01` table:

```sql
ALTER TABLE oracle_test.schema_01.table_01 ADD COLUMN age int;
```

Drop a column `age` from the `table_01` table:

```sql
ALTER TABLE oracle_test.schema_01.table_01 DROP COLUMN age;
```

Rename the `table_01` table to `table_02`:

```sql
ALTER TABLE oracle_test.schema_01.table_01 RENAME TO oracle_test.schema_01.table_02;
```

### Drop

Drop a schema:

```sql
DROP SCHEMA oracle_test.schema_01;
```

Drop a table:

```sql
DROP TABLE oracle_test.schema_01.table_01;
```
