-- Rôles PharmaCore sur une base PostgreSQL managée (Render, Neon, Supabase, RDS…) où la base
-- existe déjà et où CREATE DATABASE n'est pas permis. À exécuter une fois, connecté à la base
-- cible avec le compte administrateur fourni par l'hébergeur :
--
--   psql "$DATABASE_URL" -v owner_password='...' -v app_password='...' -f deploy/demo/init-roles.sql
--
-- Ensuite : DB_URL = jdbc:postgresql://hôte:5432/base?sslmode=require
--           DB_MIGRATION_USER=pharmacore_owner, DB_MIGRATION_PASSWORD=owner_password
--           DB_APP_USER=pharmacore_app_login,   DB_APP_PASSWORD=app_password
\set ON_ERROR_STOP on

-- Les mots de passe entrent par les variables psql et transitent par des réglages de session,
-- jamais par le fichier.
SELECT set_config('pharmacore.owner_password', :'owner_password', false),
       set_config('pharmacore.app_password', :'app_password', false);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pharmacore_app') THEN
        CREATE ROLE pharmacore_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pharmacore_network_reader') THEN
        CREATE ROLE pharmacore_network_reader NOLOGIN;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pharmacore_owner') THEN
        EXECUTE format('ALTER ROLE pharmacore_owner LOGIN PASSWORD %L', current_setting('pharmacore.owner_password'));
    ELSE
        EXECUTE format('CREATE ROLE pharmacore_owner LOGIN PASSWORD %L', current_setting('pharmacore.owner_password'));
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pharmacore_app_login') THEN
        EXECUTE format('ALTER ROLE pharmacore_app_login LOGIN PASSWORD %L', current_setting('pharmacore.app_password'));
    ELSE
        EXECUTE format('CREATE ROLE pharmacore_app_login LOGIN PASSWORD %L', current_setting('pharmacore.app_password'));
    END IF;
    EXECUTE 'GRANT pharmacore_app TO pharmacore_app_login';
    EXECUTE 'GRANT pharmacore_network_reader TO pharmacore_owner';
    -- Certains hébergeurs exigent que l'administrateur soit membre du rôle avant de lui céder le schéma.
    EXECUTE format('GRANT pharmacore_owner TO %I', current_user);
    EXECUTE 'ALTER SCHEMA public OWNER TO pharmacore_owner';
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO pharmacore_owner, pharmacore_app_login', current_database());
END
$$;
