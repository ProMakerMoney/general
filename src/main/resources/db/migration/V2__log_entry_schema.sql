CREATE TABLE log_entries (
    id        BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP    NOT NULL,
    level     VARCHAR(10)  NOT NULL,
    message   TEXT         NOT NULL,
    context   VARCHAR(50)
);
