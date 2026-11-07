#!/bin/sh
# Database-per-service: one database and one least-privilege login per service.
# Runs once, on first start of an empty data volume. Passwords come from the environment (.env).
set -eu

create_service_db() {
  user="$1"; password="$2"; database="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<EOSQL
CREATE USER $user WITH PASSWORD '$password';
CREATE DATABASE $database OWNER $user;
REVOKE ALL ON DATABASE $database FROM PUBLIC;
EOSQL
}

create_service_db account_svc     "$ACCOUNT_DB_PASSWORD"     account_db
create_service_db transaction_svc "$TRANSACTION_DB_PASSWORD" transaction_db
create_service_db transfer_svc    "$TRANSFER_DB_PASSWORD"    transfer_db
