-- Governed quote storage. Offline protected-role/owner preparation is required first.
-- Application routing remains default-off. GUC admission trusts the backend; it is not DB JWT/PDP proof.
DO $$
DECLARE name_value TEXT; role_value RECORD; schema_value TEXT;
BEGIN
 IF current_setting('server_encoding')<>'UTF8' OR current_user<>session_user OR session_user<>'quote_migrator' OR current_setting('role') NOT IN ('none',session_user::text)
   OR NOT pg_has_role(current_user,'quote_owner','USAGE') OR NOT pg_has_role(current_user,'quote_guard','USAGE')
   OR NOT pg_has_role(current_user,'quote_owner','SET') OR NOT pg_has_role(current_user,'quote_guard','SET') THEN
  RAISE EXCEPTION 'Separately prepared restricted quote installer required' USING ERRCODE='42501';
 END IF;
 SELECT * INTO role_value FROM pg_roles WHERE rolname=session_user;
 IF role_value.rolsuper OR role_value.rolbypassrls OR role_value.rolcreaterole OR role_value.rolcreatedb OR role_value.rolreplication THEN
  RAISE EXCEPTION 'Restricted quote installer required' USING ERRCODE='42501';
 END IF;
 FOREACH name_value IN ARRAY ARRAY['quote_owner','quote_guard','quote_command','quote_read'] LOOP
  SELECT * INTO role_value FROM pg_roles WHERE rolname=name_value;
  IF NOT FOUND OR role_value.rolcanlogin OR role_value.rolinherit OR role_value.rolsuper OR role_value.rolbypassrls
   OR role_value.rolcreatedb OR role_value.rolcreaterole OR role_value.rolreplication
   OR EXISTS(SELECT 1 FROM pg_auth_members WHERE member=role_value.oid) THEN
   RAISE EXCEPTION 'Exact protected quote roles required' USING ERRCODE='42501';
  END IF;
 END LOOP;
 FOREACH name_value IN ARRAY ARRAY['submission','submission_risk','statement_of_value'] LOOP
  IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c WHERE c.oid=('public.'||name_value)::regclass) IS DISTINCT FROM 'quote_owner' THEN
   RAISE EXCEPTION 'Exact protected legacy source owner required' USING ERRCODE='42501';
  END IF;
 END LOOP;
 FOREACH schema_value IN ARRAY ARRAY['insurance_quote','insurance_quote_private'] LOOP
  IF (SELECT pg_get_userbyid(n.nspowner) FROM pg_namespace n WHERE n.nspname=schema_value) IS DISTINCT FROM 'quote_owner'
    OR has_schema_privilege('quote_guard',schema_value,'CREATE') THEN
   RAISE EXCEPTION 'Prepared schema and absent guard CREATE required' USING ERRCODE='42501';
  END IF;
  IF EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=schema_value)
   OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=schema_value) THEN
   RAISE EXCEPTION 'Empty new quote schemas required before V4' USING ERRCODE='42501';
  END IF;
  EXECUTE format('GRANT CREATE ON SCHEMA %I TO quote_guard',schema_value);
 END LOOP;
 PERFORM set_config('insurance_quote.migration_guard_create','insurance_quote,insurance_quote_private',true);
END $$;
ALTER TABLE public.submission ADD CONSTRAINT quote_submission_scope_key UNIQUE(tenant_id,submission_id,party_id,product_id);

CREATE TABLE insurance_quote.offer_control (
 offer_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,party_id UUID NOT NULL,submission_id UUID NOT NULL,product_id UUID NOT NULL,
 owner_issuer TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_issuer) BETWEEN 1 AND 512),
 owner_subject TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_subject) BETWEEN 1 AND 255),
 created_action_id UUID NOT NULL,current_offer_version BIGINT NOT NULL CHECK(current_offer_version=1),
 control_generation BIGINT NOT NULL CHECK(control_generation IN(0,1)),state TEXT NOT NULL CHECK(state IN('OFFERED','ACCEPTED')),
 acceptance_id UUID,created_at TIMESTAMPTZ(6) NOT NULL,updated_at TIMESTAMPTZ(6) NOT NULL,
 CHECK((state='OFFERED' AND control_generation=0 AND acceptance_id IS NULL) OR (state='ACCEPTED' AND control_generation=1 AND acceptance_id IS NOT NULL)),
 CHECK(updated_at>=created_at)
);
CREATE TABLE insurance_quote.offer_version (
 offer_version_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,party_id UUID NOT NULL,submission_id UUID NOT NULL,product_id UUID NOT NULL,
 owner_issuer TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_issuer) BETWEEN 1 AND 512),
 owner_subject TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_subject) BETWEEN 1 AND 255),offer_id UUID NOT NULL,offer_version BIGINT NOT NULL CHECK(offer_version=1),
 canonical_format TEXT NOT NULL CHECK(canonical_format='FIREFLY_INSURANCE_QUOTE_TERMS_1'),
 canonical_terms BYTEA NOT NULL CHECK(octet_length(canonical_terms) BETWEEN 1 AND 65536),
 terms_digest TEXT NOT NULL CHECK(terms_digest ~ '^[0-9a-f]{64}$'),
 risk_snapshot BYTEA NOT NULL CHECK(octet_length(risk_snapshot) BETWEEN 1 AND 32768),
 risk_digest TEXT NOT NULL CHECK(risk_digest ~ '^[0-9a-f]{64}$'),
 product_revision_id UUID NOT NULL,product_digest TEXT NOT NULL CHECK(product_digest ~ '^[0-9a-f]{64}$'),
 provider_version TEXT NOT NULL CHECK(provider_version ~ '^[A-Za-z0-9._:-]{1,100}$'),
 rule_set_version TEXT NOT NULL CHECK(rule_set_version ~ '^[A-Za-z0-9._:-]{1,100}$'),
 currency TEXT NOT NULL CHECK(currency='USD'),base_premium NUMERIC(23,4) NOT NULL,tax NUMERIC(23,4) NOT NULL,fees NUMERIC(23,4) NOT NULL,
 issued_at TIMESTAMPTZ(6) NOT NULL,expires_at TIMESTAMPTZ(6) NOT NULL,
 CHECK(base_premium>0 AND tax>=0 AND fees>=0 AND base_premium=round(base_premium,2) AND tax=round(tax,2) AND fees=round(fees,2)),
 CHECK(base_premium+tax+fees<=99999999999999999.99),
 CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '24 hours')
);
CREATE TABLE insurance_quote.acceptance (
 acceptance_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,party_id UUID NOT NULL,submission_id UUID NOT NULL,product_id UUID NOT NULL,
 owner_issuer TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_issuer) BETWEEN 1 AND 512),
 owner_subject TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_subject) BETWEEN 1 AND 255),offer_id UUID NOT NULL,offer_version_id UUID NOT NULL,offer_version BIGINT NOT NULL CHECK(offer_version=1),
 action_id UUID NOT NULL,expected_control_generation BIGINT NOT NULL CHECK(expected_control_generation=0),
 resulting_control_generation BIGINT NOT NULL CHECK(resulting_control_generation=1),
 terms_digest TEXT NOT NULL CHECK(terms_digest ~ '^[0-9a-f]{64}$'),intent_digest TEXT NOT NULL CHECK(intent_digest ~ '^[0-9a-f]{64}$'),
 actor_issuer TEXT COLLATE "C" NOT NULL,actor_subject TEXT COLLATE "C" NOT NULL,authority_evidence_digest TEXT NOT NULL CHECK(authority_evidence_digest ~ '^[0-9a-f]{64}$'),
 accepted_at TIMESTAMPTZ(6) NOT NULL,result_canonical BYTEA NOT NULL CHECK(octet_length(result_canonical) BETWEEN 1 AND 65536),
 result_digest TEXT NOT NULL CHECK(result_digest ~ '^[0-9a-f]{64}$'),CHECK(actor_issuer=owner_issuer AND actor_subject=owner_subject)
);
CREATE TABLE insurance_quote.action_result (
 action_result_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,party_id UUID NOT NULL,submission_id UUID NOT NULL,product_id UUID NOT NULL,
 owner_issuer TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_issuer) BETWEEN 1 AND 512),
 owner_subject TEXT COLLATE "C" NOT NULL CHECK(octet_length(owner_subject) BETWEEN 1 AND 255),offer_id UUID NOT NULL,offer_version_id UUID NOT NULL,offer_version BIGINT NOT NULL CHECK(offer_version=1),
 action_id UUID NOT NULL,operation TEXT NOT NULL CHECK(operation IN('OFFER','ACCEPT')),actor_issuer TEXT COLLATE "C" NOT NULL,actor_subject TEXT COLLATE "C" NOT NULL,
 acceptance_id UUID,recorded_at TIMESTAMPTZ(6) NOT NULL,
 request_canonical BYTEA NOT NULL CHECK(octet_length(request_canonical) BETWEEN 1 AND 65536),request_digest TEXT NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 result_canonical BYTEA NOT NULL CHECK(octet_length(result_canonical) BETWEEN 1 AND 65536),result_digest TEXT NOT NULL CHECK(result_digest ~ '^[0-9a-f]{64}$'),
 authority_evidence_digest TEXT NOT NULL CHECK(authority_evidence_digest ~ '^[0-9a-f]{64}$'),
 CHECK(actor_issuer=owner_issuer AND actor_subject=owner_subject),CHECK((operation='OFFER')=(acceptance_id IS NULL))
);

ALTER TABLE insurance_quote.offer_control ADD CONSTRAINT quote_offer_control_key_0 UNIQUE(tenant_id,party_id,submission_id,product_id,offer_id);

ALTER TABLE insurance_quote.offer_version ADD CONSTRAINT quote_offer_version_key_0 UNIQUE(tenant_id,party_id,submission_id,product_id,offer_id,offer_version);

ALTER TABLE insurance_quote.offer_version ADD CONSTRAINT quote_offer_version_key_1 UNIQUE(tenant_id,party_id,submission_id,product_id,offer_id,offer_version,offer_version_id);

ALTER TABLE insurance_quote.acceptance ADD CONSTRAINT quote_acceptance_key_0 UNIQUE(tenant_id,offer_id);

ALTER TABLE insurance_quote.acceptance ADD CONSTRAINT quote_acceptance_key_1 UNIQUE(tenant_id,party_id,action_id);

ALTER TABLE insurance_quote.acceptance ADD CONSTRAINT quote_acceptance_key_2 UNIQUE(tenant_id,party_id,submission_id,product_id,offer_id,acceptance_id);

ALTER TABLE insurance_quote.action_result ADD CONSTRAINT quote_action_result_key_0 UNIQUE(tenant_id,party_id,action_id);

ALTER TABLE insurance_quote.action_result ADD CONSTRAINT quote_action_result_key_1 UNIQUE(tenant_id,party_id,submission_id,product_id,offer_id,action_id);

ALTER TABLE insurance_quote.offer_control ADD CONSTRAINT quote_graph_0 FOREIGN KEY (tenant_id,submission_id,party_id,product_id) REFERENCES public.submission (tenant_id,submission_id,party_id,product_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.offer_control ADD CONSTRAINT quote_graph_1 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id,current_offer_version) REFERENCES insurance_quote.offer_version (tenant_id,party_id,submission_id,product_id,offer_id,offer_version) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.offer_control ADD CONSTRAINT quote_graph_2 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id,created_action_id) REFERENCES insurance_quote.action_result (tenant_id,party_id,submission_id,product_id,offer_id,action_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.offer_control ADD CONSTRAINT quote_graph_3 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id,acceptance_id) REFERENCES insurance_quote.acceptance (tenant_id,party_id,submission_id,product_id,offer_id,acceptance_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.offer_version ADD CONSTRAINT quote_graph_4 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id) REFERENCES insurance_quote.offer_control (tenant_id,party_id,submission_id,product_id,offer_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.acceptance ADD CONSTRAINT quote_graph_5 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id) REFERENCES insurance_quote.offer_control (tenant_id,party_id,submission_id,product_id,offer_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.acceptance ADD CONSTRAINT quote_graph_6 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id,offer_version,offer_version_id) REFERENCES insurance_quote.offer_version (tenant_id,party_id,submission_id,product_id,offer_id,offer_version,offer_version_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.action_result ADD CONSTRAINT quote_graph_7 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id) REFERENCES insurance_quote.offer_control (tenant_id,party_id,submission_id,product_id,offer_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.action_result ADD CONSTRAINT quote_graph_8 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id,offer_version,offer_version_id) REFERENCES insurance_quote.offer_version (tenant_id,party_id,submission_id,product_id,offer_id,offer_version,offer_version_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.acceptance ADD CONSTRAINT quote_graph_9 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id,action_id) REFERENCES insurance_quote.action_result (tenant_id,party_id,submission_id,product_id,offer_id,action_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE insurance_quote.action_result ADD CONSTRAINT quote_graph_10 FOREIGN KEY (tenant_id,party_id,submission_id,product_id,offer_id,acceptance_id) REFERENCES insurance_quote.acceptance (tenant_id,party_id,submission_id,product_id,offer_id,acceptance_id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION insurance_quote_private.invalid() RETURNS VOID
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
BEGIN RAISE EXCEPTION 'Invalid canonical quote data' USING ERRCODE='22023'; END $$;
CREATE FUNCTION insurance_quote_private.fields(p_value BYTEA) RETURNS TEXT[]
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE count_value BIGINT; size_value BIGINT; position_value INTEGER:=4; i INTEGER; result TEXT[]:=ARRAY[]::TEXT[];
BEGIN
 IF p_value IS NULL OR octet_length(p_value) NOT BETWEEN 4 AND 65536 THEN PERFORM insurance_quote_private.invalid(); END IF;
 count_value:=get_byte(p_value,0)::bigint*16777216+get_byte(p_value,1)::bigint*65536+get_byte(p_value,2)::bigint*256+get_byte(p_value,3);
 IF count_value NOT BETWEEN 1 AND 128 OR count_value>(octet_length(p_value)-4)/4 THEN PERFORM insurance_quote_private.invalid(); END IF;
 FOR i IN 1..count_value LOOP
  IF position_value+4>octet_length(p_value) THEN PERFORM insurance_quote_private.invalid(); END IF;
  size_value:=get_byte(p_value,position_value)::bigint*16777216+get_byte(p_value,position_value+1)::bigint*65536+get_byte(p_value,position_value+2)::bigint*256+get_byte(p_value,position_value+3);
  position_value:=position_value+4;
  IF size_value NOT BETWEEN 0 AND 32768 OR position_value+size_value>octet_length(p_value) THEN PERFORM insurance_quote_private.invalid(); END IF;
  result:=array_append(result,convert_from(substring(p_value FROM position_value+1 FOR size_value::integer),'UTF8'));
  position_value:=position_value+size_value::integer;
 END LOOP;
 IF position_value<>octet_length(p_value) THEN PERFORM insurance_quote_private.invalid(); END IF;
 RETURN result;
END $$;
CREATE FUNCTION insurance_quote_private.uuid_value(p_value TEXT) RETURNS UUID
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE result UUID;
BEGIN
 IF p_value IS NULL OR p_value !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN PERFORM insurance_quote_private.invalid(); END IF;
 result:=p_value::uuid;IF result::text<>p_value THEN PERFORM insurance_quote_private.invalid(); END IF;RETURN result;
END $$;
CREATE FUNCTION insurance_quote_private.money_value(p_value TEXT,p_positive BOOLEAN,p_source BOOLEAN DEFAULT false) RETURNS NUMERIC
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE result NUMERIC;
BEGIN
 IF p_value IS NULL OR p_positive IS NULL OR p_source IS NULL OR p_value !~ '^(0|[1-9][0-9]{0,16})[.][0-9]{2}$'
  OR (p_source AND p_value !~ '^(0|[1-9][0-9]{0,14})[.][0-9]{2}$') THEN PERFORM insurance_quote_private.invalid(); END IF;
 result:=p_value::numeric;
 IF result<0 OR (p_positive AND result=0) THEN PERFORM insurance_quote_private.invalid(); END IF;RETURN result;
END $$;
CREATE FUNCTION insurance_quote_private.instant_text(p_value TIMESTAMPTZ) RETURNS TEXT
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pg_temp SET TimeZone='UTC' AS $$
DECLARE base TEXT; micros BIGINT;
BEGIN
 IF p_value IS NULL OR extract(year FROM p_value) NOT BETWEEN 1 AND 9999 THEN PERFORM insurance_quote_private.invalid(); END IF;
 base:=to_char(p_value AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS');
 micros:=extract(microseconds FROM p_value)::bigint%1000000;
 RETURN base||CASE WHEN micros=0 THEN '' WHEN micros%1000=0 THEN '.'||lpad((micros/1000)::text,3,'0') ELSE '.'||lpad(micros::text,6,'0') END||'Z';
END $$;
CREATE FUNCTION insurance_quote_private.instant_value(p_value TEXT) RETURNS TIMESTAMPTZ
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pg_temp SET TimeZone='UTC' AS $$
DECLARE result TIMESTAMPTZ;
BEGIN
 IF p_value IS NULL OR p_value !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-2][0-9]:[0-5][0-9]:[0-5][0-9]([.][0-9]{3}|[.][0-9]{6})?Z$' THEN PERFORM insurance_quote_private.invalid(); END IF;
 result:=p_value::timestamptz;
 IF insurance_quote_private.instant_text(result)<>p_value THEN PERFORM insurance_quote_private.invalid(); END IF;RETURN result;
END $$;
CREATE FUNCTION insurance_quote_private.require_runtime(p_mode TEXT) RETURNS VOID
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE expected_group TEXT; attributes RECORD;
BEGIN
 expected_group:=CASE WHEN p_mode IN('OFFER_PREPARE','OFFER','ACCEPT') THEN 'quote_command' WHEN p_mode IN('READ','HISTORY','RECOVER') THEN 'quote_read' END;
 IF expected_group IS NULL OR current_user<>'quote_guard'
  OR session_user IS DISTINCT FROM CASE WHEN expected_group='quote_command' THEN 'quote_command_runtime' ELSE 'quote_read_runtime' END
  OR current_setting('role') NOT IN('none',session_user::text) THEN RAISE EXCEPTION 'Exact quote runtime required' USING ERRCODE='42501'; END IF;
 SELECT * INTO attributes FROM pg_roles WHERE rolname=session_user;
 IF NOT FOUND OR attributes.rolsuper OR attributes.rolbypassrls OR attributes.rolcreaterole OR attributes.rolcreatedb OR attributes.rolreplication OR NOT attributes.rolcanlogin
  OR has_database_privilege(session_user,current_database(),'TEMP') OR has_schema_privilege(session_user,'public','CREATE')
  OR has_schema_privilege(session_user,'insurance_quote','CREATE') OR has_schema_privilege(session_user,'insurance_quote_private','CREATE')
  OR (SELECT count(*) FROM pg_auth_members WHERE member=attributes.oid)<>1
  OR NOT EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid WHERE m.member=attributes.oid AND r.rolname=expected_group AND m.inherit_option AND NOT m.set_option AND NOT m.admin_option) THEN
  RAISE EXCEPTION 'Restricted quote membership required' USING ERRCODE='42501';
 END IF;
END $$;
CREATE FUNCTION insurance_quote_private.context(p_locked BOOLEAN DEFAULT true) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE value JSONB; now_ms BIGINT; field_name TEXT;
BEGIN
 value:=NULLIF(current_setting('insurance_quote.context',true),'')::jsonb;
 IF value IS NULL OR jsonb_typeof(value) IS DISTINCT FROM 'object' THEN RAISE EXCEPTION 'Quote context required' USING ERRCODE='42501'; END IF;
 IF (SELECT count(*) FROM jsonb_object_keys(value))<>22 THEN RAISE EXCEPTION 'Exact quote context required' USING ERRCODE='42501'; END IF;
 FOREACH field_name IN ARRAY ARRAY['schema','nonce','mode','tenant','submission','party','product','offer','action','issuer','subject','actorUntil','permitUntil','configUntil','evidenceDigest','backend','xid','session','startedAt','effectiveUntil','locked','recordedAt'] LOOP
  IF NOT (value ? field_name) THEN RAISE EXCEPTION 'Complete quote context required' USING ERRCODE='42501'; END IF;
 END LOOP;
 FOREACH field_name IN ARRAY ARRAY['startedAt','effectiveUntil','actorUntil','permitUntil','configUntil'] LOOP
  IF jsonb_typeof(value->field_name) IS DISTINCT FROM 'number' OR value->>field_name !~ '^[1-9][0-9]{0,14}$'
   OR (value->>field_name)::bigint>253402300799999 THEN RAISE EXCEPTION 'Exact quote deadline required' USING ERRCODE='42501'; END IF;
 END LOOP;
 PERFORM insurance_quote_private.uuid_value(value->>'nonce');PERFORM insurance_quote_private.uuid_value(value->>'tenant');
 PERFORM insurance_quote_private.uuid_value(value->>'party');PERFORM insurance_quote_private.uuid_value(value->>'submission');
 IF value->>'product' IS NULL AND (value->>'mode'<>'OFFER_PREPARE' OR p_locked) THEN RAISE EXCEPTION 'Bound quote product required' USING ERRCODE='42501'; END IF;
 IF value->>'product' IS NOT NULL THEN PERFORM insurance_quote_private.uuid_value(value->>'product'); END IF;
 IF value->>'offer' IS NOT NULL THEN PERFORM insurance_quote_private.uuid_value(value->>'offer'); END IF;
 IF value->>'action' IS NOT NULL THEN PERFORM insurance_quote_private.uuid_value(value->>'action'); END IF;
 IF jsonb_typeof(value->'issuer') IS DISTINCT FROM 'string' OR jsonb_typeof(value->'subject') IS DISTINCT FROM 'string'
  OR octet_length(value->>'issuer') NOT BETWEEN 1 AND 512 OR octet_length(value->>'subject') NOT BETWEEN 1 AND 255
  OR value->>'evidenceDigest' IS NULL OR value->>'evidenceDigest' !~ '^[0-9a-f]{64}$'
  OR jsonb_typeof(value->'locked') IS DISTINCT FROM 'boolean'
  OR ((value->>'locked')::boolean AND value->>'recordedAt' IS NULL) THEN RAISE EXCEPTION 'Bound quote owner required' USING ERRCODE='42501'; END IF;
 PERFORM insurance_quote_private.require_runtime(value->>'mode');now_ms:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
 IF (value->>'startedAt')::bigint>now_ms OR (value->>'effectiveUntil')::bigint>LEAST((value->>'actorUntil')::bigint,(value->>'permitUntil')::bigint,(value->>'configUntil')::bigint)
  OR value->>'backend' IS DISTINCT FROM pg_backend_pid()::text OR value->>'xid' IS DISTINCT FROM pg_current_xact_id()::text
  OR value->>'session' IS DISTINCT FROM session_user::text OR value->>'schema' IS DISTINCT FROM 'QUOTE_CONTEXT_1'
  OR (value->>'effectiveUntil')::bigint<=now_ms OR (value->>'effectiveUntil')::bigint>(value->>'startedAt')::bigint+5000
  OR (p_locked AND NOT COALESCE((value->>'locked')::boolean,false)) THEN
  RAISE EXCEPTION 'Current finite quote context required' USING ERRCODE='42501';
 END IF;RETURN value;
END $$;

CREATE FUNCTION insurance_quote_private.open_context(p_nonce UUID,p_mode TEXT,p_tenant UUID,p_submission UUID,p_party UUID,p_product UUID,
 p_offer UUID,p_action UUID,p_issuer TEXT,p_subject TEXT,p_actor_until BIGINT,p_permit_until BIGINT,p_config_until BIGINT,p_evidence_digest TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET TimeZone='UTC' AS $$
DECLARE now_ms BIGINT; until_ms BIGINT; value JSONB;
BEGIN
 PERFORM insurance_quote_private.require_runtime(p_mode);
 IF NULLIF(current_setting('insurance_quote.context',true),'') IS NOT NULL OR p_nonce IS NULL OR p_tenant IS NULL OR p_submission IS NULL OR p_party IS NULL
  OR (p_product IS NULL AND p_mode<>'OFFER_PREPARE') OR p_issuer IS NULL OR octet_length(p_issuer) NOT BETWEEN 1 AND 512
  OR p_subject IS NULL OR octet_length(p_subject) NOT BETWEEN 1 AND 255 OR p_evidence_digest IS NULL OR p_evidence_digest !~ '^[0-9a-f]{64}$'
  OR p_actor_until IS NULL OR p_permit_until IS NULL OR p_config_until IS NULL
  OR p_actor_until NOT BETWEEN 1 AND 253402300799999 OR p_permit_until NOT BETWEEN 1 AND 253402300799999 OR p_config_until NOT BETWEEN 1 AND 253402300799999
  OR (p_mode IN('OFFER','ACCEPT','READ') AND p_offer IS NULL)
  OR (p_mode IN('OFFER_PREPARE','OFFER','ACCEPT','RECOVER') AND p_action IS NULL)
  OR (p_mode='OFFER_PREPARE' AND p_offer IS NOT NULL)
  OR (p_mode='HISTORY' AND (p_offer IS NOT NULL OR p_action IS NOT NULL)) THEN
  RAISE EXCEPTION 'Exact bound quote admission required' USING ERRCODE='42501';
 END IF;
 IF (p_mode IN('READ','HISTORY','RECOVER') AND (current_setting('transaction_read_only')<>'on' OR current_setting('transaction_isolation')<>'repeatable read'))
  OR (p_mode IN('OFFER_PREPARE','OFFER','ACCEPT') AND (current_setting('transaction_read_only')<>'off' OR current_setting('transaction_isolation')<>'read committed')) THEN
  RAISE EXCEPTION 'Qualified quote transaction required' USING ERRCODE='42501';
 END IF;
 now_ms:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
 until_ms:=LEAST(p_actor_until,p_permit_until,p_config_until,now_ms+5000);
 IF until_ms<=now_ms THEN RAISE EXCEPTION 'Live quote admission required' USING ERRCODE='42501'; END IF;
 value:=jsonb_build_object('schema','QUOTE_CONTEXT_1','nonce',p_nonce,'mode',p_mode,'tenant',p_tenant,'submission',p_submission,'party',p_party,'product',p_product,
  'offer',p_offer,'action',p_action,'issuer',p_issuer,'subject',p_subject,'actorUntil',p_actor_until,'permitUntil',p_permit_until,'configUntil',p_config_until,
  'evidenceDigest',p_evidence_digest,'backend',pg_backend_pid(),'xid',pg_current_xact_id()::text,'session',session_user,
  'startedAt',now_ms,'effectiveUntil',until_ms,'locked',false,'recordedAt',NULL);
 PERFORM set_config('insurance_quote.context',value::text,true);RETURN value;
END $$;
CREATE FUNCTION insurance_quote.begin_command(p_nonce UUID,p_mode TEXT,p_tenant UUID,p_submission UUID,p_party UUID,p_product UUID,
 p_offer UUID,p_action UUID,p_issuer TEXT,p_subject TEXT,p_actor_until BIGINT,p_permit_until BIGINT,p_config_until BIGINT,p_evidence_digest TEXT) RETURNS UUID
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF p_mode NOT IN('OFFER_PREPARE','OFFER','ACCEPT') THEN RAISE EXCEPTION 'Quote command mode required' USING ERRCODE='42501'; END IF;
 PERFORM insurance_quote_private.open_context(p_nonce,p_mode,p_tenant,p_submission,p_party,p_product,p_offer,p_action,p_issuer,p_subject,p_actor_until,p_permit_until,p_config_until,p_evidence_digest);
 RETURN p_nonce;
END $$;
CREATE FUNCTION insurance_quote.lock_submission(p_nonce UUID)
RETURNS TABLE(context_nonce UUID,db_recorded_at TIMESTAMPTZ,db_now_ms BIGINT,effective_until_ms BIGINT,product_id UUID,offer_id UUID)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET TimeZone='UTC' AS $$
DECLARE value JSONB; parent RECORD; until_ms BIGINT; recorded TIMESTAMPTZ; expiry TIMESTAMPTZ; now_ms BIGINT;
BEGIN
 value:=insurance_quote_private.context(false);
 IF value->>'mode' NOT IN('OFFER_PREPARE','OFFER','ACCEPT') OR (value->>'nonce')::uuid IS DISTINCT FROM p_nonce OR (value->>'locked')::boolean THEN
  RAISE EXCEPTION 'Fresh scoped command context required' USING ERRCODE='42501';
 END IF;
 SELECT s.submission_id,s.tenant_id,s.party_id,s.product_id,s.submission_status,s.line_of_business,s.currency INTO parent FROM public.submission s WHERE s.submission_id=(value->>'submission')::uuid
  AND s.tenant_id=(value->>'tenant')::uuid AND s.party_id=(value->>'party')::uuid FOR UPDATE;
 IF NOT FOUND OR (value->>'product' IS NOT NULL AND parent.product_id IS DISTINCT FROM (value->>'product')::uuid)
  OR parent.submission_status::text NOT IN('RECEIVED','QUOTED') OR parent.line_of_business::text<>'PROPERTY' OR parent.currency<>'USD' THEN
  RAISE EXCEPTION 'Supported scoped submission required' USING ERRCODE='P0001';
 END IF;
 recorded:=clock_timestamp();now_ms:=floor(extract(epoch FROM recorded)*1000)::bigint;until_ms:=(value->>'effectiveUntil')::bigint;
 IF value->>'mode'='ACCEPT' THEN
  SELECT v.expires_at INTO expiry FROM insurance_quote.offer_version v WHERE v.offer_id=(value->>'offer')::uuid
   AND v.tenant_id=parent.tenant_id AND v.party_id=parent.party_id AND v.submission_id=parent.submission_id AND v.product_id=parent.product_id
   AND v.owner_issuer=value->>'issuer' AND v.owner_subject=value->>'subject';
  IF NOT FOUND THEN RAISE EXCEPTION 'Exact retained offer required' USING ERRCODE='P0001'; END IF;
  until_ms:=LEAST(until_ms,floor(extract(epoch FROM expiry)*1000)::bigint);
 END IF;
 IF until_ms<=now_ms THEN RAISE EXCEPTION 'Quote admission expired after lock' USING ERRCODE='42501'; END IF;
 value:=value||jsonb_build_object('product',parent.product_id,'locked',true,'recordedAt',insurance_quote_private.instant_text(recorded),'effectiveUntil',until_ms);
 PERFORM set_config('insurance_quote.context',value::text,true);
 RETURN QUERY SELECT p_nonce,recorded,now_ms,until_ms,parent.product_id,(value->>'offer')::uuid;
END $$;
CREATE FUNCTION insurance_quote.begin_read(p_nonce UUID,p_mode TEXT,p_tenant UUID,p_submission UUID,p_party UUID,p_product UUID,
 p_offer UUID,p_action UUID,p_issuer TEXT,p_subject TEXT,p_actor_until BIGINT,p_permit_until BIGINT,p_config_until BIGINT,p_evidence_digest TEXT)
RETURNS TABLE(context_nonce UUID,db_recorded_at TIMESTAMPTZ,db_now_ms BIGINT,effective_until_ms BIGINT,product_id UUID,offer_id UUID)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET TimeZone='UTC' AS $$
DECLARE value JSONB; resolved UUID:=p_offer; recorded TIMESTAMPTZ;
BEGIN
 IF p_mode NOT IN('READ','HISTORY','RECOVER') THEN RAISE EXCEPTION 'Quote read mode required' USING ERRCODE='42501'; END IF;
 value:=insurance_quote_private.open_context(p_nonce,p_mode,p_tenant,p_submission,p_party,p_product,p_offer,p_action,p_issuer,p_subject,p_actor_until,p_permit_until,p_config_until,p_evidence_digest);
 IF p_mode='RECOVER' THEN
  SELECT r.offer_id INTO resolved FROM insurance_quote.action_result r WHERE r.tenant_id=p_tenant AND r.party_id=p_party
   AND r.submission_id=p_submission AND r.product_id=p_product AND r.action_id=p_action AND r.owner_issuer=p_issuer AND r.owner_subject=p_subject;
  IF p_offer IS NOT NULL AND resolved IS DISTINCT FROM p_offer THEN RAISE EXCEPTION 'Recovery scope changed' USING ERRCODE='42501'; END IF;
 END IF;
 recorded:=clock_timestamp();
 IF (value->>'effectiveUntil')::bigint<=floor(extract(epoch FROM recorded)*1000)::bigint THEN RAISE EXCEPTION 'Read context expired' USING ERRCODE='42501'; END IF;
 value:=value||jsonb_build_object('offer',resolved,'locked',true,'recordedAt',insurance_quote_private.instant_text(recorded));
 PERFORM set_config('insurance_quote.context',value::text,true);
 RETURN QUERY SELECT p_nonce,recorded,floor(extract(epoch FROM recorded)*1000)::bigint,(value->>'effectiveUntil')::bigint,p_product,resolved;
END $$;
CREATE FUNCTION insurance_quote.context_current(p_nonce UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE value JSONB;
BEGIN value:=insurance_quote_private.context();IF (value->>'nonce')::uuid IS DISTINCT FROM p_nonce THEN RAISE EXCEPTION 'Quote nonce changed' USING ERRCODE='42501'; END IF;RETURN true;END $$;
CREATE FUNCTION insurance_quote.row_scope(p_tenant UUID,p_party UUID,p_submission UUID,p_product UUID,p_offer UUID,p_issuer TEXT,p_subject TEXT,p_action UUID DEFAULT NULL) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE value JSONB;
BEGIN
 IF NULLIF(current_setting('insurance_quote.context',true),'') IS NULL THEN RETURN false; END IF;
 value:=insurance_quote_private.context();
 IF value->>'mode'='OFFER_PREPARE' THEN RETURN false; END IF;
 RETURN p_tenant IS NOT DISTINCT FROM (value->>'tenant')::uuid AND p_party IS NOT DISTINCT FROM (value->>'party')::uuid
  AND p_submission IS NOT DISTINCT FROM (value->>'submission')::uuid AND p_product IS NOT DISTINCT FROM (value->>'product')::uuid
  AND p_issuer IS NOT DISTINCT FROM value->>'issuer' AND p_subject IS NOT DISTINCT FROM value->>'subject'
  AND (value->>'mode'='HISTORY' OR p_offer IS NOT DISTINCT FROM (value->>'offer')::uuid);
END $$;
CREATE FUNCTION insurance_quote.source_scope(p_tenant UUID,p_party UUID,p_submission UUID,p_product UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE value JSONB;
BEGIN
 IF NULLIF(current_setting('insurance_quote.context',true),'') IS NULL THEN RETURN false; END IF;
 value:=insurance_quote_private.context();
 RETURN value->>'mode' IN('OFFER_PREPARE','OFFER','ACCEPT') AND p_tenant IS NOT DISTINCT FROM (value->>'tenant')::uuid
  AND p_party IS NOT DISTINCT FROM (value->>'party')::uuid AND p_submission IS NOT DISTINCT FROM (value->>'submission')::uuid
  AND p_product IS NOT DISTINCT FROM (value->>'product')::uuid;
END $$;
CREATE VIEW insurance_quote.submission_source WITH(security_barrier=true) AS
 SELECT s.submission_id,s.tenant_id,s.party_id,s.product_id,s.submission_status,s.line_of_business,s.currency FROM public.submission s
 WHERE insurance_quote.source_scope(s.tenant_id,s.party_id,s.submission_id,s.product_id);
CREATE VIEW insurance_quote.risk_source WITH(security_barrier=true) AS
 SELECT r.submission_risk_id,r.submission_id,r.insured_object_type,r.party_id,s.tenant_id,s.product_id,s.party_id AS submission_party_id
 FROM public.submission_risk r JOIN public.submission s ON s.submission_id=r.submission_id
 WHERE insurance_quote.source_scope(s.tenant_id,s.party_id,s.submission_id,s.product_id);
CREATE VIEW insurance_quote.value_source WITH(security_barrier=true) AS
 SELECT v.statement_of_value_id,v.submission_id,v.line_number,v.declared_value,v.currency,s.tenant_id,s.product_id,s.party_id AS submission_party_id
 FROM public.statement_of_value v JOIN public.submission s ON s.submission_id=v.submission_id
 WHERE insurance_quote.source_scope(s.tenant_id,s.party_id,s.submission_id,s.product_id);
CREATE FUNCTION insurance_quote_private.legacy_parent_guard() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'submission' THEN RAISE EXCEPTION 'Source trigger binding required' USING ERRCODE='42501'; END IF;
 IF EXISTS(SELECT 1 FROM insurance_quote.offer_control q WHERE q.submission_id=OLD.submission_id) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retained quote scope cannot be removed' USING ERRCODE='23503'; END IF;
  IF NEW.submission_id IS DISTINCT FROM OLD.submission_id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
   OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product_id IS DISTINCT FROM OLD.product_id THEN
   RAISE EXCEPTION 'Retained quote scope cannot change' USING ERRCODE='23503';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE FUNCTION insurance_quote_private.legacy_child_lock() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE old_parent UUID; new_parent UUID; parent UUID;
BEGIN
 IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN('submission_risk','statement_of_value') THEN RAISE EXCEPTION 'Source trigger binding required' USING ERRCODE='42501'; END IF;
 IF TG_OP<>'INSERT' THEN old_parent:=OLD.submission_id;END IF;IF TG_OP<>'DELETE' THEN new_parent:=NEW.submission_id;END IF;
 FOR parent IN SELECT DISTINCT id FROM unnest(ARRAY[old_parent,new_parent]) id WHERE id IS NOT NULL ORDER BY id LOOP
  -- Missing parents during an already-locked unreferenced cascade are not a quote-context error.
  PERFORM 1 FROM public.submission s WHERE s.submission_id=parent FOR UPDATE;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE TRIGGER quote_submission_scope_guard BEFORE UPDATE OR DELETE ON public.submission FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.legacy_parent_guard();
CREATE TRIGGER quote_risk_parent_lock BEFORE INSERT OR UPDATE OR DELETE ON public.submission_risk FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.legacy_child_lock();
CREATE TRIGGER quote_value_parent_lock BEFORE INSERT OR UPDATE OR DELETE ON public.statement_of_value FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.legacy_child_lock();

CREATE FUNCTION insurance_quote_private.text_value(p_value TEXT,p_max INTEGER,p_nfc BOOLEAN) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE stripped TEXT:=p_value; code INTEGER;
BEGIN
 IF p_value IS NULL OR p_max IS NULL OR p_max<1 OR p_nfc IS NULL OR octet_length(p_value) NOT BETWEEN 1 AND p_max THEN PERFORM insurance_quote_private.invalid(); END IF;
 FOREACH code IN ARRAY ARRAY[9,10,11,12,13,28,29,30,31,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288] LOOP
  stripped:=replace(stripped,chr(code),'');
 END LOOP;
 IF stripped='' OR (p_nfc AND p_value IS DISTINCT FROM normalize(p_value,NFC)) THEN PERFORM insurance_quote_private.invalid(); END IF;RETURN p_value;
END $$;
CREATE FUNCTION insurance_quote_private.date_value(p_value TEXT) RETURNS DATE
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE result DATE;
BEGIN
 IF p_value IS NULL OR p_value !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN PERFORM insurance_quote_private.invalid(); END IF;
 result:=p_value::date;
 IF extract(year FROM result) NOT BETWEEN 1 AND 9999 OR to_char(result,'YYYY-MM-DD')<>p_value THEN PERFORM insurance_quote_private.invalid(); END IF;RETURN result;
END $$;
CREATE FUNCTION insurance_quote_private.scope_fields(p_fields TEXT[],p_context JSONB,p_offset INTEGER DEFAULT 1) RETURNS VOID
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF p_fields[p_offset+1] IS DISTINCT FROM p_context->>'tenant' OR p_fields[p_offset+2] IS DISTINCT FROM p_context->>'submission'
  OR p_fields[p_offset+3] IS DISTINCT FROM p_context->>'party' OR p_fields[p_offset+4] IS DISTINCT FROM p_context->>'product' THEN PERFORM insurance_quote_private.invalid(); END IF;
 PERFORM insurance_quote_private.uuid_value(p_fields[p_offset+1]);PERFORM insurance_quote_private.uuid_value(p_fields[p_offset+2]);
 PERFORM insurance_quote_private.uuid_value(p_fields[p_offset+3]);PERFORM insurance_quote_private.uuid_value(p_fields[p_offset+4]);
END $$;
CREATE FUNCTION insurance_quote_private.terms_fields(p_value BYTEA,p_context JSONB) RETURNS TEXT[]
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE f TEXT[]; count_value INTEGER; position_value INTEGER; total_bytes INTEGER:=0; previous_key TEXT; issue TIMESTAMPTZ; expiry TIMESTAMPTZ; amount NUMERIC;
BEGIN
 f:=insurance_quote_private.fields(p_value);
 IF cardinality(f)<21 OR f[1]<>'FIREFLY_INSURANCE_QUOTE_TERMS_1' OR f[6] IS DISTINCT FROM p_context->>'offer'
  OR f[7]<>'1' OR f[10]<>'USD' OR f[15] !~ '^[0-9a-f]{64}$' OR f[16] !~ '^[0-9a-f]{64}$'
  OR f[17] !~ '^[A-Za-z0-9._:-]{1,100}$' OR f[18] !~ '^[A-Za-z0-9._:-]{1,100}$'
  OR f[19] !~ '^[1-9][0-9]?$' THEN PERFORM insurance_quote_private.invalid(); END IF;
 PERFORM insurance_quote_private.scope_fields(f,p_context);PERFORM insurance_quote_private.uuid_value(f[6]);PERFORM insurance_quote_private.uuid_value(f[14]);
 count_value:=f[19]::integer;
 IF count_value NOT BETWEEN 1 AND 32 OR cardinality(f)<>19+2*count_value THEN PERFORM insurance_quote_private.invalid(); END IF;
 issue:=insurance_quote_private.instant_value(f[8]);expiry:=insurance_quote_private.instant_value(f[9]);
 IF expiry<=issue OR expiry>issue+interval '24 hours' THEN PERFORM insurance_quote_private.invalid(); END IF;
 amount:=insurance_quote_private.money_value(f[11],true)+insurance_quote_private.money_value(f[12],false)+insurance_quote_private.money_value(f[13],false);
 IF amount>99999999999999999.99 THEN PERFORM insurance_quote_private.invalid(); END IF;
 FOR position_value IN 0..count_value-1 LOOP
  IF f[20+2*position_value] !~ '^[a-z][a-zA-Z0-9._-]{0,63}$'
   OR (previous_key IS NOT NULL AND f[20+2*position_value] COLLATE "C"<=previous_key COLLATE "C") THEN PERFORM insurance_quote_private.invalid(); END IF;
  previous_key:=f[20+2*position_value];PERFORM insurance_quote_private.text_value(f[21+2*position_value],4096,true);
  total_bytes:=total_bytes+octet_length(previous_key)+octet_length(f[21+2*position_value]);
 END LOOP;
 IF total_bytes>32768 THEN PERFORM insurance_quote_private.invalid(); END IF;RETURN f;
END $$;
CREATE FUNCTION insurance_quote_private.require_risk(p_value BYTEA,p_context JSONB) RETURNS VOID
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE f TEXT[]; parent RECORD; risk RECORD; valuation RECORD; amount NUMERIC;
BEGIN
 f:=insurance_quote_private.fields(p_value);
 IF octet_length(p_value)>32768 OR cardinality(f)<>17 OR f[1]<>'FIREFLY_INSURANCE_QUOTE_RISK_1' THEN PERFORM insurance_quote_private.invalid(); END IF;
 PERFORM insurance_quote_private.scope_fields(f,p_context);
 IF f[6] NOT IN('RECEIVED','QUOTED') OR f[7]<>'PROPERTY' OR f[8]<>'USD' OR f[11]<>'PROPERTY' OR f[17]<>'USD'
  OR f[10] IS DISTINCT FROM p_context->>'submission' OR f[12] IS DISTINCT FROM p_context->>'party' OR f[14] IS DISTINCT FROM p_context->>'submission'
  OR f[15] !~ '^-?(0|[1-9][0-9]{0,9})$' OR (f[15]::integer)::text<>f[15] THEN PERFORM insurance_quote_private.invalid(); END IF;
 PERFORM insurance_quote_private.uuid_value(f[9]);PERFORM insurance_quote_private.uuid_value(f[10]);PERFORM insurance_quote_private.uuid_value(f[12]);
 PERFORM insurance_quote_private.uuid_value(f[13]);PERFORM insurance_quote_private.uuid_value(f[14]);amount:=insurance_quote_private.money_value(f[16],true,true);
 SELECT s.submission_id,s.tenant_id,s.party_id,s.product_id,s.submission_status,s.line_of_business,s.currency INTO parent FROM public.submission s WHERE s.submission_id=(p_context->>'submission')::uuid AND s.tenant_id=(p_context->>'tenant')::uuid
  AND s.party_id=(p_context->>'party')::uuid AND s.product_id=(p_context->>'product')::uuid;
 IF NOT FOUND OR parent.submission_status::text<>f[6] OR parent.line_of_business::text<>f[7] OR parent.currency<>f[8]
  OR (SELECT count(*) FROM public.submission_risk r WHERE r.submission_id=parent.submission_id)<>1
  OR (SELECT count(*) FROM public.statement_of_value v WHERE v.submission_id=parent.submission_id)<>1 THEN PERFORM insurance_quote_private.invalid(); END IF;
 SELECT r.submission_risk_id,r.submission_id,r.insured_object_type,r.party_id INTO risk FROM public.submission_risk r WHERE r.submission_id=parent.submission_id;
 SELECT v.statement_of_value_id,v.submission_id,v.line_number,v.declared_value,v.currency INTO valuation FROM public.statement_of_value v WHERE v.submission_id=parent.submission_id;
 IF risk.submission_risk_id IS DISTINCT FROM f[9]::uuid OR risk.insured_object_type::text IS DISTINCT FROM f[11]
  OR risk.party_id IS DISTINCT FROM f[12]::uuid OR valuation.statement_of_value_id IS DISTINCT FROM f[13]::uuid
  OR valuation.line_number IS DISTINCT FROM f[15]::integer OR valuation.declared_value IS DISTINCT FROM amount OR valuation.currency IS DISTINCT FROM f[17] THEN
  RAISE EXCEPTION 'Current locked source differs from offered risk' USING ERRCODE='P0001';
 END IF;
END $$;
CREATE FUNCTION insurance_quote_private.request_fields(p_value BYTEA,p_context JSONB,p_terms TEXT[],p_terms_digest TEXT) RETURNS TEXT[]
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE f TEXT[]; starts DATE; ends DATE; position_value INTEGER; coverage JSONB:='{}'::jsonb;
BEGIN
 f:=insurance_quote_private.fields(p_value);
 IF cardinality(f)<>10 THEN PERFORM insurance_quote_private.invalid(); END IF;PERFORM insurance_quote_private.scope_fields(f,p_context);
 IF p_context->>'mode'='OFFER' THEN
  IF f[1]<>'FIREFLY_INSURANCE_QUOTE_OFFER_REQUEST_1' OR f[6] IS DISTINCT FROM p_context->>'action' OR f[9] IS DISTINCT FROM p_terms[14]
   OR f[10] IS DISTINCT FROM p_terms[15] THEN PERFORM insurance_quote_private.invalid(); END IF;
  PERFORM insurance_quote_private.uuid_value(f[6]);PERFORM insurance_quote_private.uuid_value(f[9]);
  starts:=insurance_quote_private.date_value(f[7]);ends:=insurance_quote_private.date_value(f[8]);
  IF ends-starts NOT BETWEEN 1 AND 366 THEN PERFORM insurance_quote_private.invalid(); END IF;
  FOR position_value IN 0..p_terms[19]::integer-1 LOOP
   coverage:=coverage||jsonb_build_object(p_terms[20+2*position_value],p_terms[21+2*position_value]);
  END LOOP;
  IF coverage->>'coverageStart' IS DISTINCT FROM f[7] OR coverage->>'coverageEnd' IS DISTINCT FROM f[8] THEN PERFORM insurance_quote_private.invalid(); END IF;
 ELSIF p_context->>'mode'='ACCEPT' THEN
  IF f[1]<>'FIREFLY_INSURANCE_QUOTE_ACCEPT_INTENT_1' OR f[6] IS DISTINCT FROM p_context->>'offer' OR f[7]<>'1' OR f[8]<>'0'
   OR f[9] IS DISTINCT FROM p_terms_digest OR f[10] IS DISTINCT FROM p_context->>'action' THEN PERFORM insurance_quote_private.invalid(); END IF;
  PERFORM insurance_quote_private.uuid_value(f[6]);PERFORM insurance_quote_private.uuid_value(f[10]);
 ELSE RAISE EXCEPTION 'Quote write mode required' USING ERRCODE='42501'; END IF;
 RETURN f;
END $$;
CREATE FUNCTION insurance_quote_private.result_fields(p_value BYTEA,p_context JSONB,p_request_digest TEXT,p_result UUID,p_version UUID,p_acceptance UUID,p_generation BIGINT) RETURNS TEXT[]
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE f TEXT[];
BEGIN
 f:=insurance_quote_private.fields(p_value);
 IF cardinality(f)<>19 OR f[1]<>'FIREFLY_INSURANCE_QUOTE_RESULT_1' OR f[2]<>'FIREFLY_INSURANCE_QUOTE_ACTION_1' THEN PERFORM insurance_quote_private.invalid(); END IF;
 PERFORM insurance_quote_private.scope_fields(f,p_context,2);PERFORM insurance_quote_private.text_value(f[7],512,false);PERFORM insurance_quote_private.text_value(f[8],255,false);
 IF f[7] IS DISTINCT FROM p_context->>'issuer' OR f[8] IS DISTINCT FROM p_context->>'subject' OR f[9] IS DISTINCT FROM p_context->>'mode'
  OR f[10] IS DISTINCT FROM p_context->>'offer' OR f[11]<>'1' OR f[12]<>'0' OR f[13] IS DISTINCT FROM p_context->>'action'
  OR f[14] IS DISTINCT FROM p_request_digest OR f[15] IS DISTINCT FROM p_result::text OR f[16] IS DISTINCT FROM p_version::text
  OR f[17] IS DISTINCT FROM COALESCE(p_acceptance::text,'') OR f[19] IS DISTINCT FROM p_generation::text
  OR insurance_quote_private.instant_value(f[18]) IS DISTINCT FROM insurance_quote_private.instant_value(p_context->>'recordedAt') THEN PERFORM insurance_quote_private.invalid(); END IF;
 PERFORM insurance_quote_private.uuid_value(f[10]);PERFORM insurance_quote_private.uuid_value(f[13]);PERFORM insurance_quote_private.uuid_value(f[15]);PERFORM insurance_quote_private.uuid_value(f[16]);
 IF p_acceptance IS NOT NULL THEN PERFORM insurance_quote_private.uuid_value(f[17]); END IF;RETURN f;
END $$;

CREATE FUNCTION insurance_quote_private.row_guard() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET TimeZone='UTC' AS $$
DECLARE c JSONB; value JSONB; previous JSONB; v insurance_quote.offer_version%ROWTYPE; t TEXT[]; f TEXT[]; result_fields TEXT[];
 recorded TIMESTAMPTZ; request_digest TEXT; request_bytes BYTEA; result_bytes BYTEA; generation BIGINT; acceptance_key UUID;
BEGIN
 IF TG_TABLE_SCHEMA<>'insurance_quote' OR TG_TABLE_NAME NOT IN('offer_control','offer_version','acceptance','action_result') OR TG_OP='DELETE' THEN
  RAISE EXCEPTION 'Immutable scoped quote operation required' USING ERRCODE='42501';
 END IF;
 c:=insurance_quote_private.context();recorded:=insurance_quote_private.instant_value(c->>'recordedAt');
 IF c->>'mode' NOT IN('OFFER','ACCEPT') THEN RAISE EXCEPTION 'Quote mutation admission required' USING ERRCODE='42501'; END IF;
 value:=to_jsonb(NEW);
 IF value->>'tenant_id' IS DISTINCT FROM c->>'tenant' OR value->>'submission_id' IS DISTINCT FROM c->>'submission'
  OR value->>'party_id' IS DISTINCT FROM c->>'party' OR value->>'product_id' IS DISTINCT FROM c->>'product'
  OR value->>'offer_id' IS DISTINCT FROM c->>'offer' OR value->>'owner_issuer' IS DISTINCT FROM c->>'issuer'
  OR value->>'owner_subject' IS DISTINCT FROM c->>'subject' THEN RAISE EXCEPTION 'Quote row scope changed' USING ERRCODE='42501'; END IF;
 IF TG_TABLE_NAME='offer_control' THEN
  IF c->>'mode'='OFFER' THEN
   IF TG_OP<>'INSERT' OR value->>'created_action_id' IS DISTINCT FROM c->>'action' OR value->>'state'<>'OFFERED'
    OR value->>'control_generation'<>'0' OR value->>'current_offer_version'<>'1' OR value->>'acceptance_id' IS NOT NULL
    OR (value->>'created_at')::timestamptz IS DISTINCT FROM recorded OR (value->>'updated_at')::timestamptz IS DISTINCT FROM recorded THEN
    RAISE EXCEPTION 'Exact initial quote control required' USING ERRCODE='42501';
   END IF;
  ELSE
   IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Quote control CAS required' USING ERRCODE='42501'; END IF;previous:=to_jsonb(OLD);
   IF previous->>'state'<>'OFFERED' OR previous->>'control_generation'<>'0' OR previous->>'acceptance_id' IS NOT NULL
    OR value->>'state'<>'ACCEPTED' OR value->>'control_generation'<>'1' OR value->>'acceptance_id' IS NULL
    OR (value->>'updated_at')::timestamptz IS DISTINCT FROM recorded
    OR value-ARRAY['state','control_generation','acceptance_id','updated_at'] IS DISTINCT FROM previous-ARRAY['state','control_generation','acceptance_id','updated_at'] THEN
    RAISE EXCEPTION 'Exact four-column quote CAS required' USING ERRCODE='42501';
   END IF;
   SELECT * INTO v FROM insurance_quote.offer_version r WHERE r.offer_id=(c->>'offer')::uuid;
   IF NOT FOUND OR v.expires_at<=recorded THEN RAISE EXCEPTION 'Live exact offer required' USING ERRCODE='P0001'; END IF;
   PERFORM insurance_quote_private.require_risk(v.risk_snapshot,c);
  END IF;
 ELSIF TG_TABLE_NAME='offer_version' THEN
  IF TG_OP<>'INSERT' OR c->>'mode'<>'OFFER' THEN RAISE EXCEPTION 'Immutable quote version required' USING ERRCODE='42501'; END IF;
  t:=insurance_quote_private.terms_fields(NEW.canonical_terms,c);PERFORM insurance_quote_private.require_risk(NEW.risk_snapshot,c);
  IF NEW.offer_version<>1 OR NEW.canonical_format<>'FIREFLY_INSURANCE_QUOTE_TERMS_1'
   OR NEW.terms_digest IS DISTINCT FROM encode(sha256(NEW.canonical_terms),'hex') OR NEW.risk_digest IS DISTINCT FROM encode(sha256(NEW.risk_snapshot),'hex')
   OR t[8] IS DISTINCT FROM insurance_quote_private.instant_text(recorded) OR NEW.issued_at IS DISTINCT FROM recorded
   OR insurance_quote_private.instant_value(t[9]) IS DISTINCT FROM NEW.expires_at OR t[10] IS DISTINCT FROM NEW.currency
   OR insurance_quote_private.money_value(t[11],true) IS DISTINCT FROM NEW.base_premium
   OR insurance_quote_private.money_value(t[12],false) IS DISTINCT FROM NEW.tax OR insurance_quote_private.money_value(t[13],false) IS DISTINCT FROM NEW.fees
   OR t[14] IS DISTINCT FROM NEW.product_revision_id::text OR t[15] IS DISTINCT FROM NEW.product_digest OR t[16] IS DISTINCT FROM NEW.risk_digest
   OR t[17] IS DISTINCT FROM NEW.provider_version OR t[18] IS DISTINCT FROM NEW.rule_set_version THEN PERFORM insurance_quote_private.invalid(); END IF;
 ELSIF TG_TABLE_NAME IN('acceptance','action_result') THEN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Immutable quote action required' USING ERRCODE='42501'; END IF;
  SELECT * INTO v FROM insurance_quote.offer_version r WHERE r.offer_id=(c->>'offer')::uuid;
  IF NOT FOUND OR v.tenant_id::text IS DISTINCT FROM c->>'tenant' OR v.party_id::text IS DISTINCT FROM c->>'party'
   OR v.submission_id::text IS DISTINCT FROM c->>'submission' OR v.product_id::text IS DISTINCT FROM c->>'product'
   OR v.owner_issuer IS DISTINCT FROM c->>'issuer' OR v.owner_subject IS DISTINCT FROM c->>'subject'
   OR value->>'offer_version_id' IS DISTINCT FROM v.offer_version_id::text OR value->>'offer_version'<>'1'
   OR value->>'action_id' IS DISTINCT FROM c->>'action' OR value->>'actor_issuer' IS DISTINCT FROM c->>'issuer'
   OR value->>'actor_subject' IS DISTINCT FROM c->>'subject' OR value->>'authority_evidence_digest' IS DISTINCT FROM c->>'evidenceDigest' THEN
   RAISE EXCEPTION 'Exact retained quote action scope required' USING ERRCODE='42501';
  END IF;
  t:=insurance_quote_private.terms_fields(v.canonical_terms,c);
  IF c->>'mode'='ACCEPT' THEN
   IF v.expires_at<=recorded THEN RAISE EXCEPTION 'Offer has expired' USING ERRCODE='42501'; END IF;
   PERFORM insurance_quote_private.require_risk(v.risk_snapshot,c);
  END IF;
  IF TG_TABLE_NAME='acceptance' THEN
   IF c->>'mode'<>'ACCEPT' OR NEW.expected_control_generation<>0 OR NEW.resulting_control_generation<>1
    OR NEW.accepted_at IS DISTINCT FROM recorded OR NEW.terms_digest IS DISTINCT FROM v.terms_digest THEN PERFORM insurance_quote_private.invalid(); END IF;
   result_fields:=insurance_quote_private.fields(NEW.result_canonical);
   PERFORM insurance_quote_private.result_fields(NEW.result_canonical,c,NEW.intent_digest,insurance_quote_private.uuid_value(result_fields[15]),v.offer_version_id,NEW.acceptance_id,1);
   IF NEW.result_digest IS DISTINCT FROM encode(sha256(NEW.result_canonical),'hex') THEN PERFORM insurance_quote_private.invalid(); END IF;
  ELSE
   IF NEW.operation IS DISTINCT FROM c->>'mode' OR NEW.recorded_at IS DISTINCT FROM recorded THEN PERFORM insurance_quote_private.invalid(); END IF;
   f:=insurance_quote_private.request_fields(NEW.request_canonical,c,t,v.terms_digest);
   IF NEW.request_digest IS DISTINCT FROM encode(sha256(NEW.request_canonical),'hex') THEN PERFORM insurance_quote_private.invalid(); END IF;
   generation:=CASE WHEN NEW.operation='OFFER' THEN 0 ELSE 1 END;
   PERFORM insurance_quote_private.result_fields(NEW.result_canonical,c,NEW.request_digest,NEW.action_result_id,v.offer_version_id,NEW.acceptance_id,generation);
   IF NEW.result_digest IS DISTINCT FROM encode(sha256(NEW.result_canonical),'hex') THEN PERFORM insurance_quote_private.invalid(); END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION insurance_quote_private.validate_graph(p_offer UUID) RETURNS VOID
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE c JSONB; control insurance_quote.offer_control%ROWTYPE; version insurance_quote.offer_version%ROWTYPE;
 creator insurance_quote.action_result%ROWTYPE; accepted insurance_quote.acceptance%ROWTYPE; action insurance_quote.action_result%ROWTYPE;
BEGIN
 c:=insurance_quote_private.context();
 IF c->>'mode' NOT IN('OFFER','ACCEPT') OR (c->>'offer')::uuid IS DISTINCT FROM p_offer THEN RAISE EXCEPTION 'Quote graph context required' USING ERRCODE='42501'; END IF;
 SELECT * INTO control FROM insurance_quote.offer_control q WHERE q.offer_id=p_offer;
 IF NOT FOUND THEN RAISE EXCEPTION 'Complete quote control required' USING ERRCODE='23514'; END IF;
 SELECT * INTO version FROM insurance_quote.offer_version v WHERE v.offer_id=p_offer AND v.offer_version=1;
 IF NOT FOUND OR version.owner_issuer<>control.owner_issuer OR version.owner_subject<>control.owner_subject
  OR ROW(version.tenant_id,version.party_id,version.submission_id,version.product_id) IS DISTINCT FROM ROW(control.tenant_id,control.party_id,control.submission_id,control.product_id)
  OR version.issued_at<>control.created_at OR (SELECT count(*) FROM insurance_quote.offer_version v WHERE v.offer_id=p_offer)<>1 THEN
  RAISE EXCEPTION 'Complete same-scope quote version required' USING ERRCODE='23514';
 END IF;
 SELECT * INTO creator FROM insurance_quote.action_result r WHERE r.tenant_id=control.tenant_id AND r.party_id=control.party_id AND r.action_id=control.created_action_id;
 IF NOT FOUND OR creator.operation<>'OFFER' OR creator.offer_id<>p_offer OR creator.offer_version_id<>version.offer_version_id
  OR creator.recorded_at<>version.issued_at OR creator.owner_issuer<>control.owner_issuer OR creator.owner_subject<>control.owner_subject
  OR creator.acceptance_id IS NOT NULL THEN RAISE EXCEPTION 'Complete original offer action required' USING ERRCODE='23514'; END IF;
 IF control.state='OFFERED' THEN
  IF control.control_generation<>0 OR control.acceptance_id IS NOT NULL OR control.updated_at<>control.created_at
   OR EXISTS(SELECT 1 FROM insurance_quote.acceptance a WHERE a.offer_id=p_offer)
   OR (SELECT count(*) FROM insurance_quote.action_result r WHERE r.offer_id=p_offer)<>1 THEN
   RAISE EXCEPTION 'Exact three-row offer graph required' USING ERRCODE='23514';
  END IF;
 ELSE
  SELECT * INTO accepted FROM insurance_quote.acceptance a WHERE a.acceptance_id=control.acceptance_id;
  IF NOT FOUND OR accepted.offer_id<>p_offer OR accepted.offer_version_id<>version.offer_version_id OR accepted.terms_digest<>version.terms_digest
   OR accepted.owner_issuer<>control.owner_issuer OR accepted.owner_subject<>control.owner_subject
   OR accepted.accepted_at<>control.updated_at OR accepted.expected_control_generation<>0 OR accepted.resulting_control_generation<>1
   OR (SELECT count(*) FROM insurance_quote.acceptance a WHERE a.offer_id=p_offer)<>1 THEN RAISE EXCEPTION 'Complete quote acceptance required' USING ERRCODE='23514'; END IF;
  SELECT * INTO action FROM insurance_quote.action_result r WHERE r.tenant_id=accepted.tenant_id AND r.party_id=accepted.party_id AND r.action_id=accepted.action_id;
  IF NOT FOUND OR action.operation<>'ACCEPT' OR action.offer_id<>p_offer OR action.offer_version_id<>version.offer_version_id
   OR action.acceptance_id<>accepted.acceptance_id OR action.recorded_at<>accepted.accepted_at OR action.request_digest<>accepted.intent_digest
   OR action.owner_issuer<>control.owner_issuer OR action.owner_subject<>control.owner_subject
   OR action.authority_evidence_digest<>accepted.authority_evidence_digest OR action.result_canonical<>accepted.result_canonical OR action.result_digest<>accepted.result_digest
   OR (SELECT count(*) FROM insurance_quote.action_result r WHERE r.offer_id=p_offer)<>2 THEN
   RAISE EXCEPTION 'Complete original acceptance action required' USING ERRCODE='23514';
  END IF;
 END IF;
 PERFORM insurance_quote_private.context();
END $$;
CREATE FUNCTION insurance_quote.validate_complete(p_nonce UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE c JSONB;
BEGIN c:=insurance_quote_private.context();IF (c->>'nonce')::uuid IS DISTINCT FROM p_nonce THEN RAISE EXCEPTION 'Quote nonce changed' USING ERRCODE='42501'; END IF;PERFORM insurance_quote_private.validate_graph((c->>'offer')::uuid);RETURN true;END $$;
CREATE FUNCTION insurance_quote_private.deferred_complete() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'insurance_quote' OR TG_TABLE_NAME NOT IN('offer_control','offer_version','acceptance','action_result') THEN RAISE EXCEPTION 'Quote trigger binding required' USING ERRCODE='42501'; END IF;
 PERFORM insurance_quote_private.validate_graph(NEW.offer_id);RETURN NULL;
END $$;

ALTER TABLE insurance_quote.offer_control OWNER TO quote_owner;
ALTER TABLE insurance_quote.offer_control ENABLE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.offer_control FORCE ROW LEVEL SECURITY;
REVOKE ALL ON insurance_quote.offer_control FROM PUBLIC,quote_command,quote_read;
GRANT SELECT ON insurance_quote.offer_control TO quote_guard,quote_command,quote_read;

GRANT INSERT(created_action_id,current_offer_version,control_generation,state,acceptance_id,created_at,updated_at,tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject) ON insurance_quote.offer_control TO quote_command;

CREATE POLICY quote_guard_read ON insurance_quote.offer_control FOR SELECT TO quote_guard USING(true);
CREATE POLICY quote_runtime_read ON insurance_quote.offer_control FOR SELECT TO quote_command,quote_read USING(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL));
CREATE POLICY quote_runtime_insert ON insurance_quote.offer_control FOR INSERT TO quote_command WITH CHECK(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL));

CREATE TRIGGER quote_immutable_row BEFORE INSERT OR UPDATE OR DELETE ON insurance_quote.offer_control FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.row_guard();
CREATE CONSTRAINT TRIGGER quote_complete AFTER INSERT OR UPDATE ON insurance_quote.offer_control DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.deferred_complete();

ALTER TABLE insurance_quote.offer_version OWNER TO quote_owner;
ALTER TABLE insurance_quote.offer_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.offer_version FORCE ROW LEVEL SECURITY;
REVOKE ALL ON insurance_quote.offer_version FROM PUBLIC,quote_command,quote_read;
GRANT SELECT ON insurance_quote.offer_version TO quote_guard,quote_command,quote_read;

GRANT INSERT(offer_version,canonical_format,canonical_terms,terms_digest,risk_snapshot,risk_digest,product_revision_id,product_digest,provider_version,rule_set_version,currency,tenant_id,party_id,submission_id,product_id,offer_id,base_premium,tax,fees,issued_at,expires_at,offer_version_id,owner_issuer,owner_subject) ON insurance_quote.offer_version TO quote_command;

CREATE POLICY quote_guard_read ON insurance_quote.offer_version FOR SELECT TO quote_guard USING(true);
CREATE POLICY quote_runtime_read ON insurance_quote.offer_version FOR SELECT TO quote_command,quote_read USING(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL));
CREATE POLICY quote_runtime_insert ON insurance_quote.offer_version FOR INSERT TO quote_command WITH CHECK(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL));

CREATE TRIGGER quote_immutable_row BEFORE INSERT OR UPDATE OR DELETE ON insurance_quote.offer_version FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.row_guard();
CREATE CONSTRAINT TRIGGER quote_complete AFTER INSERT OR UPDATE ON insurance_quote.offer_version DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.deferred_complete();

ALTER TABLE insurance_quote.acceptance OWNER TO quote_owner;
ALTER TABLE insurance_quote.acceptance ENABLE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.acceptance FORCE ROW LEVEL SECURITY;
REVOKE ALL ON insurance_quote.acceptance FROM PUBLIC,quote_command,quote_read;
GRANT SELECT ON insurance_quote.acceptance TO quote_guard,quote_command,quote_read;

GRANT INSERT(expected_control_generation,resulting_control_generation,accepted_at,tenant_id,party_id,submission_id,product_id,offer_id,offer_version_id,offer_version,action_id,terms_digest,intent_digest,actor_issuer,actor_subject,authority_evidence_digest,result_canonical,result_digest,acceptance_id,owner_issuer,owner_subject) ON insurance_quote.acceptance TO quote_command;

CREATE POLICY quote_guard_read ON insurance_quote.acceptance FOR SELECT TO quote_guard USING(true);
CREATE POLICY quote_runtime_read ON insurance_quote.acceptance FOR SELECT TO quote_command,quote_read USING(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL));
CREATE POLICY quote_runtime_insert ON insurance_quote.acceptance FOR INSERT TO quote_command WITH CHECK(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL));

CREATE TRIGGER quote_immutable_row BEFORE INSERT OR UPDATE OR DELETE ON insurance_quote.acceptance FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.row_guard();
CREATE CONSTRAINT TRIGGER quote_complete AFTER INSERT OR UPDATE ON insurance_quote.acceptance DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.deferred_complete();

ALTER TABLE insurance_quote.action_result OWNER TO quote_owner;
ALTER TABLE insurance_quote.action_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.action_result FORCE ROW LEVEL SECURITY;
REVOKE ALL ON insurance_quote.action_result FROM PUBLIC,quote_command,quote_read;
GRANT SELECT ON insurance_quote.action_result TO quote_guard,quote_command,quote_read;

GRANT INSERT(operation,actor_issuer,actor_subject,acceptance_id,recorded_at,tenant_id,party_id,submission_id,product_id,offer_id,offer_version_id,offer_version,action_id,request_canonical,request_digest,result_canonical,result_digest,authority_evidence_digest,action_result_id,owner_issuer,owner_subject) ON insurance_quote.action_result TO quote_command;

CREATE POLICY quote_guard_read ON insurance_quote.action_result FOR SELECT TO quote_guard USING(true);
CREATE POLICY quote_runtime_read ON insurance_quote.action_result FOR SELECT TO quote_command,quote_read USING(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,action_id));
CREATE POLICY quote_runtime_insert ON insurance_quote.action_result FOR INSERT TO quote_command WITH CHECK(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,action_id));

CREATE TRIGGER quote_immutable_row BEFORE INSERT OR UPDATE OR DELETE ON insurance_quote.action_result FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.row_guard();
CREATE CONSTRAINT TRIGGER quote_complete AFTER INSERT OR UPDATE ON insurance_quote.action_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.deferred_complete();

GRANT UPDATE(state,control_generation,acceptance_id,updated_at) ON insurance_quote.offer_control TO quote_command;
CREATE POLICY quote_control_cas ON insurance_quote.offer_control FOR UPDATE TO quote_command
 USING(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL))
 WITH CHECK(insurance_quote.row_scope(tenant_id,party_id,submission_id,product_id,offer_id,owner_issuer,owner_subject,NULL));
GRANT SELECT(submission_id,tenant_id,party_id,product_id,submission_status,line_of_business,currency) ON public.submission TO quote_guard;
GRANT UPDATE(submission_id) ON public.submission TO quote_guard;
GRANT SELECT(submission_risk_id,submission_id,insured_object_type,party_id) ON public.submission_risk TO quote_guard;
GRANT SELECT(statement_of_value_id,submission_id,line_number,declared_value,currency) ON public.statement_of_value TO quote_guard;
ALTER VIEW insurance_quote.submission_source OWNER TO quote_guard;
ALTER VIEW insurance_quote.risk_source OWNER TO quote_guard;
ALTER VIEW insurance_quote.value_source OWNER TO quote_guard;
REVOKE ALL ON insurance_quote.submission_source,insurance_quote.risk_source,insurance_quote.value_source FROM PUBLIC,quote_read;
GRANT SELECT ON insurance_quote.submission_source,insurance_quote.risk_source,insurance_quote.value_source TO quote_command;

ALTER FUNCTION insurance_quote_private.invalid() OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.invalid() FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.fields(BYTEA) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.fields(BYTEA) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.uuid_value(TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.uuid_value(TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.money_value(TEXT,BOOLEAN,BOOLEAN) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.money_value(TEXT,BOOLEAN,BOOLEAN) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.instant_text(TIMESTAMPTZ) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.instant_text(TIMESTAMPTZ) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.instant_value(TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.instant_value(TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.require_runtime(TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.require_runtime(TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.context(BOOLEAN) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.context(BOOLEAN) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.open_context(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.open_context(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote.begin_command(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote.begin_command(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote.lock_submission(UUID) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote.lock_submission(UUID) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote.begin_read(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote.begin_read(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote.context_current(UUID) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote.context_current(UUID) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote.row_scope(UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,UUID) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote.row_scope(UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,UUID) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote.source_scope(UUID,UUID,UUID,UUID) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote.source_scope(UUID,UUID,UUID,UUID) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.legacy_parent_guard() OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.legacy_parent_guard() FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.legacy_child_lock() OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.legacy_child_lock() FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.text_value(TEXT,INTEGER,BOOLEAN) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.text_value(TEXT,INTEGER,BOOLEAN) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.date_value(TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.date_value(TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.scope_fields(TEXT[],JSONB,INTEGER) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.scope_fields(TEXT[],JSONB,INTEGER) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.terms_fields(BYTEA,JSONB) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.terms_fields(BYTEA,JSONB) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.require_risk(BYTEA,JSONB) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.require_risk(BYTEA,JSONB) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.request_fields(BYTEA,JSONB,TEXT[],TEXT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.request_fields(BYTEA,JSONB,TEXT[],TEXT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.result_fields(BYTEA,JSONB,TEXT,UUID,UUID,UUID,BIGINT) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.result_fields(BYTEA,JSONB,TEXT,UUID,UUID,UUID,BIGINT) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.row_guard() OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.row_guard() FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.validate_graph(UUID) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.validate_graph(UUID) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote.validate_complete(UUID) OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote.validate_complete(UUID) FROM PUBLIC,quote_command,quote_read;

ALTER FUNCTION insurance_quote_private.deferred_complete() OWNER TO quote_guard;
REVOKE ALL ON FUNCTION insurance_quote_private.deferred_complete() FROM PUBLIC,quote_command,quote_read;

GRANT EXECUTE ON FUNCTION insurance_quote.begin_command(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) TO quote_command;

GRANT EXECUTE ON FUNCTION insurance_quote.lock_submission(UUID) TO quote_command;

GRANT EXECUTE ON FUNCTION insurance_quote.begin_read(UUID,TEXT,UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,BIGINT,BIGINT,TEXT) TO quote_read;

GRANT EXECUTE ON FUNCTION insurance_quote.context_current(UUID) TO quote_command,quote_read;

GRANT EXECUTE ON FUNCTION insurance_quote.row_scope(UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,UUID) TO quote_command,quote_read;

GRANT EXECUTE ON FUNCTION insurance_quote.source_scope(UUID,UUID,UUID,UUID) TO quote_command;

GRANT EXECUTE ON FUNCTION insurance_quote.validate_complete(UUID) TO quote_command;

DO $$
BEGIN
 IF current_setting('insurance_quote.migration_guard_create',true) IS DISTINCT FROM 'insurance_quote,insurance_quote_private' THEN
  RAISE EXCEPTION 'Exact transaction-owned temporary grants required' USING ERRCODE='42501';
 END IF;
 REVOKE CREATE ON SCHEMA insurance_quote FROM quote_guard;
 REVOKE CREATE ON SCHEMA insurance_quote_private FROM quote_guard;
 IF has_schema_privilege('quote_guard','insurance_quote','CREATE') OR has_schema_privilege('quote_guard','insurance_quote_private','CREATE') THEN
  RAISE EXCEPTION 'Guard schema CREATE must be absent before commit' USING ERRCODE='42501';
 END IF;
 PERFORM set_config('insurance_quote.migration_guard_create','',true);
END $$;
