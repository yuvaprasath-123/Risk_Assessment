-- Developer 1 shared schema proposal - REVIEW ONLY; NOT EXECUTED.
-- Oracle AI Database 26ai Free; localhost:1521/FREEPDB1; tablespace USERS.
-- Proposed executor: SYSTEM connected directly to FREEPDB1 for setup only.
-- Creates an account only; no tables, sequences, users' data, or role assignments.
-- Does not drop, alter, or reuse an existing SUPPLIER_PAYMENT_APP account.
-- Use SQL*Plus, SQLcl, or SQL Developer Run Script mode for the safeguards.
-- Execute Statement mode does not honor the script-wide EXIT/error controls.
-- Oracle DDL commits implicitly; ROLLBACK cannot undo completed user/grant DDL.

-- Mapping review (all nine entity IDs use GenerationType.IDENTITY):
-- security-service: APP_USERS, SEC_ROLES, SEC_USER_ROLES,
--                   SEC_APPROVAL_AUTHORITIES.
-- supplier-invoice-service: SUP_SUPPLIERS, SUP_INVOICES,
--                           SUP_SUPPLIER_STATUS_HISTORY.
-- payment-request-service: PAY_PAYMENT_REQUESTS, PAY_PAYMENT_STATUS_HISTORY.
-- No explicit @SequenceGenerator mappings were found. Oracle identity columns
-- use internal sequence generators; they are not manually created here.
-- All three services currently use ddl-auto: update and this shared username.
-- Future startup can create/alter own-schema objects; security-service also
-- inserts missing role records. This proposal does not authorize startup.

-- Password handling for a future, separately authorized execution:
-- Keep this repository copy unchanged. Make a private copy OUTSIDE Git.
-- Replace the complete placeholder inside double quotes in that private copy
-- with a strong local password without double-quote characters. Retain quotes.
-- After review, remove only the marked REVIEW-ONLY STOP in the private copy.
-- Do not commit, share, paste, spool, or log the password-bearing copy.
-- ECHO/VERIFY OFF do not protect saved files or external client logging.

SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

PROMPT Checking current container; expected FREEPDB1.
SELECT SYS_CONTEXT('USERENV', 'CON_NAME') AS current_container FROM dual;

BEGIN
    IF NVL(SYS_CONTEXT('USERENV', 'CON_NAME'), 'UNKNOWN') <> 'FREEPDB1' THEN
        RAISE_APPLICATION_ERROR(-20001,
            'Wrong container. Connect directly to FREEPDB1 before setup.');
    END IF;
END;
/

PROMPT Read-only account check; no rows means the proposed name is unused.
SELECT username, account_status, default_tablespace
FROM dba_users
WHERE username = 'SUPPLIER_PAYMENT_APP';

DECLARE
    existing_account_count PLS_INTEGER;
BEGIN
    SELECT COUNT(*) INTO existing_account_count
    FROM dba_users WHERE username = 'SUPPLIER_PAYMENT_APP';
    IF existing_account_count > 0 THEN
        RAISE_APPLICATION_ERROR(-20002,
            'Account already exists. Stop and review; no existing user will be changed.');
    END IF;
END;
/

-- BEGIN REVIEW-ONLY STOP
PROMPT REVIEW ONLY: stopping before account creation. No account was created.
EXIT SUCCESS ROLLBACK
-- END REVIEW-ONLY STOP

-- Unreachable while the review stop remains. 100M is a proposed demonstration
-- quota shared by all three services, not an approved capacity requirement.
CREATE USER SUPPLIER_PAYMENT_APP
    IDENTIFIED BY "__REPLACE_LOCALLY_SUPPLIER_PAYMENT_APP_PASSWORD__"
    DEFAULT TABLESPACE USERS
    QUOTA 100M ON USERS
    CONTAINER = CURRENT;

-- Corrected grants for the locally verified own-schema identity setup:
-- CREATE SESSION: connect as SUPPLIER_PAYMENT_APP.
-- CREATE TABLE: create own tables, including identity columns and constraints.
-- CREATE SEQUENCE: support the identity-column setup used by Hibernate here.
-- User-reported local verification: the original CREATE SESSION/CREATE TABLE
-- grants caused ORA-01031 during startup. Adding CREATE SEQUENCE allowed startup.
-- This corrects the earlier proposal; this repository script was not executed
-- by the agent, and does not alter an already existing account.
-- The owner inherently controls its own tables/indexes and their data; no
-- ALTER ANY TABLE, CREATE ANY INDEX, or separate own-table DML grants are needed.
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO SUPPLIER_PAYMENT_APP;

-- No DBA/RESOURCE roles, ANY privileges, UNLIMITED TABLESPACE, ADMIN OPTION,
-- CREATE PROCEDURE, CREATE TRIGGER, cross-schema grants, or account-admin grants.
-- This is a schema owner with DDL ability, not a restricted runtime account.
-- It does not bootstrap a FINANCE_MANAGER user. Application-account bootstrap
-- is separate from database schema-account creation.
-- User-confirmed local development first-manager bootstrap (record only; no SQL):
-- 1. POST /api/v1/users created the local test user; the service hashed its
--    password. No password or hash is included in this record.
-- 2. Using SYSTEM connected to FREEPDB1, the user inserted a mapping between
--    that test user's ID and the existing FINANCE_MANAGER role's ID into
--    SUPPLIER_PAYMENT_APP.SEC_USER_ROLES, then committed it.
-- 3. POST /api/v1/auth/login issued the manager token for the test account.
-- No existing-manager role-assignment API was used. This was a local development
-- bootstrap, not a production provisioning policy or authorization to repeat it.
-- Local verification: the FINANCE_MANAGER test account successfully logged in to
-- POST /api/v1/auth/login, and its issuer token was accepted by the configuration
-- version list API (HTTP 200, empty array). Both services were stopped afterward
-- and temporary PowerShell credentials, signing key, token/request variables
-- were cleared. No passwords, hashes, keys, or tokens belong in this file.
-- Bootstrap code contract: POST /api/v1/users creates an ACTIVE account using
-- username, password, fullName and email, with no roles. Role assignment through
-- PUT /api/v1/users/{userId}/roles requires an existing FINANCE_MANAGER and takes
-- a roles array; it replaces existing roles. The local database bootstrap above
-- supplied the first manager for this development check. No bootstrap SQL is
-- generated here, and SYSTEM is not an application runtime identity.

-- Read-only confirmations for a future authorized private execution copy.
SELECT username, account_status, default_tablespace
FROM dba_users WHERE username = 'SUPPLIER_PAYMENT_APP';

SELECT username, tablespace_name, max_bytes
FROM dba_ts_quotas
WHERE username = 'SUPPLIER_PAYMENT_APP' AND tablespace_name = 'USERS';

SELECT grantee, privilege, admin_option
FROM dba_sys_privs
WHERE grantee = 'SUPPLIER_PAYMENT_APP'
ORDER BY privilege;

EXIT SUCCESS

-- References:
-- https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-USER.html
-- https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-TABLE.html
-- https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-SEQUENCE.html
