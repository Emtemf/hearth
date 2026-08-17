ALTER TABLE hearth_exchange
    ADD COLUMN status_code INTEGER,
    ADD COLUMN stop_reason TEXT,
    ADD COLUMN streamed BOOLEAN;
