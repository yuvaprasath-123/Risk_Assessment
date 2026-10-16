-- Developer 2 schema setup proposal - REVIEW ONLY; not executed.
-- Target: Oracle AI Database 26ai Free (reported version 23.26.2.0.0).
-- Endpoint: localhost:1521/FREEPDB1; local PDB: FREEPDB1; tablespace: USERS.
-- Intended future executor: SYSTEM connected directly to FREEPDB1 for setup.
-- This file creates no business tables, sequences, procedures, or packages.
-- No existing user is dropped, altered, or granted additional privileges.
-- Client: SQL*Plus / SQLcl, or SQL Developer Run Script mode. Execute Statement
-- mode does not apply the script-wide error/exit safeguards.
-- Oracle user DDL commits implicitly; ROLLBACK cannot undo completed CREATE USER
-- or GRANT statements. Stop on failure and inspect partial setup; do not blindly
-- rerun or modify existing accounts.

-- Password handling for a future, separately authorized setup:
-- 1. Keep this repository copy unchanged with placeholders and the review stop.
-- 2. Copy it into a private local directory OUTSIDE the Git working tree.
-- 3. In that private copy only, replace each complete password placeholder
--    inside the double quotes with a different strong local password. Keep the
--    double quotes; choose passwords without double-quote characters.
-- 4. Remove only the marked REVIEW-ONLY STOP from the private copy after review.
--    Retain the container, account-existence, and error checks.
-- 5. Never commit, upload, paste, or spool the private password-bearing copy.
--    Do not put database login passwords in command-line arguments or the plan.
--    Protect and dispose of that private copy according to local secret policy.
-- SET DEFINE OFF prevents ampersands in substituted passwords from becoming
-- SQL*Plus substitution variables. ECHO/VERIFY OFF reduce displayed SQL output;
-- they do not protect a file containing secrets or disable external logging.

SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

PROMPT Checking current container; expected FREEPDB1.
SELECT SYS_CONTEXT('USERENV', 'CON_NAME') AS current_container
FROM dual;

-- Fail closed in any container other than FREEPDB1; do not switch containers.
BEGIN
    IF NVL(SYS_CONTEXT('USERENV', 'CON_NAME'), 'UNKNOWN') <> 'FREEPDB1' THEN
        RAISE_APPLICATION_ERROR(
            -20001,
            'Wrong container. Connect directly to FREEPDB1 before setup.'
        );
    END IF;
END;
/

PROMPT Read-only account check; no rows means the proposed names are unused.
SELECT username, account_status, default_tablespace
FROM dba_users
WHERE username IN ('D2_CONFIGURATION', 'D2_VALIDATION', 'D2_RISK')
ORDER BY username;

-- Stop before any account creation if any proposed name already exists.
-- This check reads the dictionary only. It does not change or reuse accounts.
DECLARE
    existing_account_count PLS_INTEGER;
BEGIN
    SELECT COUNT(*)
    INTO existing_account_count
    FROM dba_users
    WHERE username IN ('D2_CONFIGURATION', 'D2_VALIDATION', 'D2_RISK');

    IF existing_account_count > 0 THEN
        RAISE_APPLICATION_ERROR(
            -20002,
            'One or more proposed accounts already exist. Stop and review; no existing users will be changed.'
        );
    END IF;
END;
/

-- BEGIN REVIEW-ONLY STOP
-- This deliberate stop prevents creation with placeholder passwords.
-- Remove these marked lines ONLY in a private reviewed execution copy later.
PROMPT REVIEW ONLY: stopping before account creation. No accounts were created.
EXIT SUCCESS ROLLBACK
-- END REVIEW-ONLY STOP

-- Proposed account creation below is unreachable with the review stop retained.
-- 100M is a bounded per-owner quota for tables/indexes in USERS, not an
-- UNLIMITED TABLESPACE grant. Each owner is a local user of FREEPDB1.

CREATE USER D2_CONFIGURATION
    IDENTIFIED BY "__REPLACE_LOCALLY_D2_CONFIGURATION_PASSWORD__"
    DEFAULT TABLESPACE USERS
    QUOTA 100M ON USERS
    CONTAINER = CURRENT;

CREATE USER D2_VALIDATION
    IDENTIFIED BY "__REPLACE_LOCALLY_D2_VALIDATION_PASSWORD__"
    DEFAULT TABLESPACE USERS
    QUOTA 100M ON USERS
    CONTAINER = CURRENT;

CREATE USER D2_RISK
    IDENTIFIED BY "__REPLACE_LOCALLY_D2_RISK_PASSWORD__"
    DEFAULT TABLESPACE USERS
    QUOTA 100M ON USERS
    CONTAINER = CURRENT;

-- Proposed privileges for schema owners / future migrations:
-- CREATE SESSION: connect to the database.
-- CREATE TABLE: create planned tables in the account's own schema.
-- CREATE SEQUENCE: create explicit ID sequences in the account's own schema.
-- CREATE PROCEDURE: create own-schema PL/SQL procedures, functions, package
--                  specifications, and package bodies; no separate CREATE
--                  PACKAGE system privilege is required.
-- These owner privileges are identical for all three service schemas.
-- No DBA/RESOURCE roles, broad ANY privileges, UNLIMITED TABLESPACE, ADMIN
-- OPTION, cross-schema object grants, or user-administration grants are given.
-- Runtime users with narrower object grants are a separate future decision;
-- these are schema-owner accounts, not a claim of enforced immutable runtime
-- access. Owners inherently control their own objects.

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE
    TO D2_CONFIGURATION;

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE
    TO D2_VALIDATION;

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE
    TO D2_RISK;

-- Read-only confirmation for a future execution copy; does not log passwords.
SELECT username, account_status, default_tablespace
FROM dba_users
WHERE username IN ('D2_CONFIGURATION', 'D2_VALIDATION', 'D2_RISK')
ORDER BY username;

SELECT username, tablespace_name, max_bytes
FROM dba_ts_quotas
WHERE username IN ('D2_CONFIGURATION', 'D2_VALIDATION', 'D2_RISK')
  AND tablespace_name = 'USERS'
ORDER BY username;

SELECT grantee, privilege, admin_option
FROM dba_sys_privs
WHERE grantee IN ('D2_CONFIGURATION', 'D2_VALIDATION', 'D2_RISK')
ORDER BY grantee, privilege;

EXIT SUCCESS

-- Oracle references used to review the proposed syntax/privileges:
-- https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-USER.html
-- https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-TABLE.html
-- https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-SEQUENCE.html
-- https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-PACKAGE.html
