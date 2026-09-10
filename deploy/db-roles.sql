-- Splits the one all-powerful database account into the two jobs that actually exist.
--
-- Until now the service connected as ssadmin, the server's administrator: it could drop any
-- table, read every database on the server, and create more roles. None of that is anything
-- the application does. The cost of that gap is not theoretical — it is the difference
-- between a SQL injection that reads a row and one that drops the schema.
--
--   ss_migrate  owns the schema and may change it. Flyway alone uses it, at startup.
--   ss_app      may only SELECT/INSERT/UPDATE/DELETE the rows. It cannot create, alter or
--               drop anything, and cannot touch flyway_schema_history at all.
--
-- Applied by deploy/db-roles.sh, which supplies the two passwords. Idempotent: re-running
-- resets the passwords and re-applies the grants, and never drops anything.

\set ON_ERROR_STOP on

-- ── roles ────────────────────────────────────────────────────────────────────
-- Created without a password, then given one, so the CREATE stays conditional while the
-- password is always refreshed to whatever the caller passed in.
SELECT 'CREATE ROLE ss_migrate LOGIN'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ss_migrate')
\gexec
SELECT 'CREATE ROLE ss_app LOGIN'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ss_app')
\gexec

ALTER ROLE ss_migrate LOGIN PASSWORD :'migrate_pw';
ALTER ROLE ss_app     LOGIN PASSWORD :'app_pw';

-- Neither may create databases or roles. Stated rather than assumed: these are off by
-- default, and this file is also the record of what was intended.
--
-- NOSUPERUSER is deliberately absent. Saying it requires being a superuser, and on Azure
-- Flexible Server the administrator account is not one — the statement fails rather than
-- reassures. Neither role is a superuser regardless: only a superuser can hand that out.
ALTER ROLE ss_migrate NOCREATEDB NOCREATEROLE;
ALTER ROLE ss_app     NOCREATEDB NOCREATEROLE;

-- ── schema access ────────────────────────────────────────────────────────────
-- Before ownership, not after: handing a table to ss_migrate requires ss_migrate to already
-- hold CREATE on the schema it lives in, or the ALTER fails with "permission denied for
-- schema public" and the reason is nowhere near the statement that failed.
GRANT CONNECT ON DATABASE spacesurvivors TO ss_migrate, ss_app;

-- Only ss_migrate may add objects to the schema. ss_app may see into it, nothing more.
GRANT USAGE, CREATE ON SCHEMA public TO ss_migrate;
GRANT USAGE ON SCHEMA public TO ss_app;

-- ── ownership ────────────────────────────────────────────────────────────────
-- Handing an object over requires being a member of the receiving role.
GRANT ss_migrate TO CURRENT_USER;

-- Everything the earlier migrations created still belongs to ssadmin. Tables made by future
-- migrations arrive owned by ss_migrate already, because ss_migrate will be the one running
-- them — this is only the catch-up.
SELECT format('ALTER TABLE public.%I OWNER TO ss_migrate', tablename)
  FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'ss_migrate'
\gexec

SELECT format('ALTER FUNCTION public.%I(%s) OWNER TO ss_migrate',
              p.proname, pg_get_function_identity_arguments(p.oid))
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public' AND pg_get_userbyid(p.proowner) <> 'ss_migrate'
\gexec

-- ── privileges ───────────────────────────────────────────────────────────────
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ss_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ss_app;

-- Grants apply to the tables that exist right now, so without this every future migration
-- would silently produce a table the application cannot read — and the failure would arrive
-- later, in production, as a permission error on a query that had never been run before.
ALTER DEFAULT PRIVILEGES FOR ROLE ss_migrate IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ss_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ss_migrate IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ss_app;

-- The migration ledger is Flyway's business. The application never reads it, and an account
-- that can rewrite it is an account that can convince Flyway a migration already ran.
REVOKE ALL ON TABLE public.flyway_schema_history FROM ss_app;

-- ── what came out ────────────────────────────────────────────────────────────
\echo ''
\echo 'Table privileges for ss_app (flyway_schema_history must be absent):'
SELECT table_name, string_agg(privilege_type, ', ' ORDER BY privilege_type) AS privileges
  FROM information_schema.table_privileges
 WHERE grantee = 'ss_app' AND table_schema = 'public'
 GROUP BY table_name
 ORDER BY table_name;

\echo 'Owners:'
SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;
