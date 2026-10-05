CREATE SEQUENCE users_seq;

CREATE TABLE users (
  id         NUMBER PRIMARY KEY,
  name       VARCHAR2(50) NOT NULL,
  email      VARCHAR2(100),
  created_at DATE
);
