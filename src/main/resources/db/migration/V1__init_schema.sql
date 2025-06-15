CREATE TABLE trades (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(20)  NOT NULL,
    price       DECIMAL(18,8) NOT NULL,
    volume      DECIMAL(18,8) NOT NULL,
    direction   VARCHAR(5)   NOT NULL CHECK (direction IN ('LONG','SHORT')),
    status      VARCHAR(15)  NOT NULL CHECK (status IN ('OPEN','CLOSED','CANCELLED')),
    profit      DECIMAL(18,8),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at   TIMESTAMP
);