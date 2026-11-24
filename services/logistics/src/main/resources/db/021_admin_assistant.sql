CREATE TABLE assistant_documents (
 id bigserial PRIMARY KEY,
 title text NOT NULL CHECK(length(trim(title)) BETWEEN 3 AND 120),
 path text NOT NULL CHECK(path IN ('/','/orders','/orders/new','/distribution','/live','/routing','/warehouses','/vehicles','/drivers','/maintenance','/reports','/pricing','/notifications','/settings')),
 content text NOT NULL CHECK(length(trim(content)) BETWEEN 20 AND 12000),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by bigint NOT NULL, updated_by bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),deleted_at timestamptz
);
