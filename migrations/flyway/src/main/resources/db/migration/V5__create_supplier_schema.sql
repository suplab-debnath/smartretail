-- V5: Supplier schema — owned by SUP (stub in prototype)
-- contact_email_enc / contact_phone_enc are KMS-encrypted at application layer.
-- po_id in supplier_pos is a logical (non-FK) reference to replenishment.purchase_orders.

CREATE SCHEMA IF NOT EXISTS supplier;

CREATE TABLE IF NOT EXISTS supplier.supplier_records (
    supplier_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    supplier_name     VARCHAR(200) NOT NULL,
    contact_email_enc BYTEA,
    contact_phone_enc BYTEA,
    address           VARCHAR(500),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                                   CHECK (status IN ('ACTIVE', 'INACTIVE')),
    onboarded_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT supplier_records_pk PRIMARY KEY (supplier_id)
);

CREATE TABLE IF NOT EXISTS supplier.supplier_pos (
    supplier_po_id UUID         NOT NULL DEFAULT gen_random_uuid(),
    supplier_id    UUID         NOT NULL REFERENCES supplier.supplier_records(supplier_id),
    po_id          VARCHAR(50)  NOT NULL,
    po_status      VARCHAR(30)  NOT NULL,
    confirmed_at   TIMESTAMPTZ,
    dispatched_at  TIMESTAMPTZ,
    eta            DATE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT supplier_pos_pk PRIMARY KEY (supplier_po_id)
);

CREATE TABLE IF NOT EXISTS supplier.shipment_updates (
    update_id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    supplier_po_id     UUID        NOT NULL REFERENCES supplier.supplier_pos(supplier_po_id),
    update_type        VARCHAR(20) NOT NULL
                       CHECK (update_type IN ('ACKNOWLEDGED', 'SHIPPED', 'ETA_UPDATE', 'EXCEPTION')),
    shipment_reference VARCHAR(200),
    actual_qty_shipped INTEGER,
    revised_eta        DATE,
    exception_type     VARCHAR(50),
    notes              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT shipment_updates_pk PRIMARY KEY (update_id)
);

COMMENT ON COLUMN supplier.supplier_records.contact_email_enc IS
    'KMS-encrypted at application layer. Decrypted only within SUP task boundary.';

COMMENT ON COLUMN supplier.supplier_pos.po_id IS
    'Logical reference to replenishment.purchase_orders.po_id.
     NOT a foreign key — cross-schema FK constraints are forbidden.
     Relationship resolved via purchase order domain event.';
