-- Rôles PostgreSQL de production (exécuté une fois par le DBA / l'initialisation du conteneur).
--  * pharmacore_owner     : propriétaire du schéma, utilisé UNIQUEMENT par Flyway
--  * pharmacore_app       : rôle d'exécution soumis à la Row-Level Security (NOLOGIN)
--  * pharmacore_app_login : compte de connexion du pool applicatif, membre de pharmacore_app
--  * pharmacore_network_reader : propriétaire des fonctions SECURITY DEFINER du réseau
-- Les mots de passe sont injectés par des variables psql (-v), jamais versionnés.
\set ON_ERROR_STOP on

CREATE ROLE pharmacore_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE pharmacore_app NOLOGIN;
CREATE ROLE pharmacore_network_reader NOLOGIN;
CREATE ROLE pharmacore_app_login LOGIN PASSWORD :'app_password' IN ROLE pharmacore_app;

-- Le propriétaire doit pouvoir transférer la propriété des fonctions au lecteur réseau.
GRANT pharmacore_network_reader TO pharmacore_owner;

CREATE DATABASE pharmacore OWNER pharmacore_owner;
\connect pharmacore
ALTER SCHEMA public OWNER TO pharmacore_owner;
