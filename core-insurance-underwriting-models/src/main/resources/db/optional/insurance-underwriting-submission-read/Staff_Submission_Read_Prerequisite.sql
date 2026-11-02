-- Explicit offline owner prerequisite for a new dedicated submission reader. Never an application migration.
BEGIN;
SET LOCAL ROLE quote_owner;
DO $owner$
DECLARE reader pg_catalog.pg_roles%ROWTYPE; relation RECORD;
BEGIN
    SELECT * INTO STRICT reader FROM pg_catalog.pg_roles WHERE rolname = 'underwriting_submission_read';
    IF NOT reader.rolcanlogin OR reader.rolsuper OR reader.rolinherit OR reader.rolcreaterole
       OR reader.rolcreatedb OR reader.rolreplication OR reader.rolbypassrls
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member = reader.oid OR roleid = reader.oid) THEN
        RAISE EXCEPTION 'Separate restricted submission reader without role memberships required';
    END IF;
    IF NOT pg_catalog.has_schema_privilege(reader.oid, 'public', 'USAGE') THEN
        RAISE EXCEPTION 'Existing public schema usage required';
    END IF;
    IF (SELECT pg_catalog.pg_get_userbyid(relowner) FROM pg_catalog.pg_class WHERE oid = 'public.submission'::regclass) <> current_user THEN
        RAISE EXCEPTION 'Exact existing submission owner required';
    END IF;
    FOR relation IN SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname IN ('public', 'insurance_quote', 'insurance_quote_private') AND c.relkind IN ('r','p','v','m')
    LOOP
        IF pg_catalog.has_table_privilege(reader.oid,relation.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
           OR pg_catalog.has_any_column_privilege(reader.oid,relation.oid,'SELECT,INSERT,UPDATE,REFERENCES') THEN
            RAISE EXCEPTION 'New unprivileged submission reader required; existing or partial custody cannot be adopted';
        END IF;
    END LOOP;
END;
$owner$;
GRANT SELECT (submission_id,tenant_id,submission_number,party_id,product_id,distributor_id,contract_id,
              submission_status,line_of_business,received_at,currency,created_at,updated_at)
ON public.submission TO underwriting_submission_read;
COMMIT;
