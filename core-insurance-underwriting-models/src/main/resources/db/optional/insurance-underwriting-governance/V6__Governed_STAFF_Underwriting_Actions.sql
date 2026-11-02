-- Optional manual owner successor after original V4/V5 and released canonical audit SQL.
-- No roles, memberships, prior approvals or unknown custody rows are created/adopted.
BEGIN;
SET LOCAL statement_timeout='5s';
SET LOCAL lock_timeout='5s';
DO $$ DECLARE role_name TEXT;relation TEXT;owner_oid OID;BEGIN
 IF current_user<>session_user OR session_user<>'quote_migrator' OR current_setting('role') NOT IN('none',session_user::text) OR pg_catalog.getdatabaseencoding()<>'UTF8' THEN RAISE EXCEPTION 'Original restricted quote installer required' USING ERRCODE='42501';END IF;
 FOREACH role_name IN ARRAY ARRAY['quote_owner','quote_guard','firefly_audit_guard'] LOOP
  IF NOT pg_catalog.pg_has_role(session_user,role_name,'SET') OR NOT pg_catalog.pg_has_role(session_user,role_name,'USAGE') THEN RAISE EXCEPTION 'Explicit owner delegation required' USING ERRCODE='42501';END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_catalog.pg_roles WHERE rolname IN('quote_migrator','quote_owner','quote_guard','firefly_audit_guard','firefly_audit_writer') AND(rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication))
  OR EXISTS(SELECT 1 FROM pg_catalog.pg_auth_members WHERE member IN(SELECT oid FROM pg_catalog.pg_roles WHERE rolname IN('quote_owner','quote_guard','firefly_audit_guard','firefly_audit_writer')))
  OR(SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname IN('quote_owner','quote_guard','firefly_audit_guard') AND NOT rolinherit AND NOT rolcanlogin)<>3
  OR(SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname='firefly_audit_writer' AND NOT rolinherit AND rolcanlogin)<>1 THEN RAISE EXCEPTION 'Exact separate custody roles required' USING ERRCODE='42501';END IF;
 SELECT oid INTO STRICT owner_oid FROM pg_catalog.pg_roles WHERE rolname='quote_owner';
 FOREACH relation IN ARRAY ARRAY['submission','submission_risk','statement_of_value','referral','underwriting_decision'] LOOP
  IF (SELECT c.relowner FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname=relation AND c.relkind='r') IS DISTINCT FROM owner_oid THEN RAISE EXCEPTION 'Original protected underwriting source owner required' USING ERRCODE='42501';END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='insurance_quote' AND c.relname IN('governed_referral','governed_underwriting_decision','underwriting_action')) OR EXISTS(SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='insurance_quote' AND p.proname LIKE 'underwriting_%') THEN RAISE EXCEPTION 'Fresh unadopted STAFF custody required' USING ERRCODE='42501';END IF;
END $$;
SET LOCAL ROLE firefly_audit_guard;
DO $$ DECLARE expected RECORD;p pg_catalog.pg_proc%ROWTYPE;BEGIN
 FOR expected IN SELECT * FROM (VALUES
  ('firefly_audit_private.context_for(uuid)','8616221035b02a30dc60e2eed36ed0bd4c19c1b6984440a392e5df5e4b1024db',true,'v'),
  ('firefly_audit_private.require_graph(firefly_audit_private.context)','483fd3cf7bad4e224f3e4198c800005f251808bb9825e3075e4c8b05ef5143ec',true,'v'),
  ('firefly_audit_private.canonical_text(text,text)','684092d8d911493f91da50f41988d0b80fe2579107ad0648e30641b70572b5b6',false,'i'),
  ('firefly_audit_private.canonical_record_bytes(firefly_audit.audit_event)','6257272eb2ebe26119e64a0af407d458f9dbd4050ad72681cc7e5f3ec90dc123',false,'i'),
  ('firefly_audit_private.require_record_hash(firefly_audit.audit_event)','9c29ebde6b5a400a93ce044df0b8eb353753cd25afdf3fc4e572aa6240d4834f',false,'i')) v(signature,body_hash,definer,volatility)
 LOOP
  SELECT * INTO p FROM pg_catalog.pg_proc WHERE oid=pg_catalog.to_regprocedure(expected.signature);
  IF NOT FOUND OR (p.proowner=current_user::regrole::oid AND p.prosecdef=expected.definer AND p.provolatile::text=expected.volatility
   AND NOT p.proisstrict AND NOT p.proleakproof AND p.proparallel='u' AND p.pronargdefaults=0
   AND(SELECT lanname FROM pg_catalog.pg_language WHERE oid=p.prolang)='plpgsql'
   AND p.prokind='f' AND p.proconfig=ARRAY['search_path=pg_catalog']::text[]
   AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex')=expected.body_hash) IS NOT TRUE
   OR EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a
    WHERE a.grantee<>p.proowner OR a.privilege_type<>'EXECUTE') THEN
   RAISE EXCEPTION 'Exact released canonical audit helper required' USING ERRCODE='42501';END IF;
 END LOOP;
END $$;
GRANT USAGE ON SCHEMA firefly_audit TO quote_guard;
GRANT REFERENCES(resource_tenant_id,event_id) ON firefly_audit.audit_event TO quote_guard;
CREATE FUNCTION firefly_audit.underwriting_write_scope(p_submission UUID,p_event UUID,p_action TEXT) RETURNS VOID
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c firefly_audit_private.context;e firefly_audit.audit_event;
BEGIN
 SELECT * INTO STRICT c FROM firefly_audit_private.context t WHERE t.xid=pg_current_xact_id_if_assigned() AND t.backend_pid=pg_backend_pid() AND t.db_session_user=session_user AND t.service='core-insurance-underwriting' AND t.resource_type='governed-underwriting' AND t.resource_id=p_submission;
 c:=firefly_audit_private.context_for(c.nonce);PERFORM firefly_audit_private.require_graph(c);
 SELECT * INTO STRICT e FROM firefly_audit.audit_event t WHERE t.resource_tenant_id=c.resource_tenant_id AND t.event_id=c.event_id;PERFORM firefly_audit_private.require_record_hash(e);
 IF(c.new_event AND EXISTS(SELECT 1 FROM firefly_audit_private.completion d WHERE d.nonce=c.nonce) AND e.outcome='COMPLETED' AND e.actor_staff_realm IS NOT NULL AND e.actor_staff_id IS NOT NULL
  AND e.action IN('insurance.underwriting.referral.create','insurance.underwriting.referral.approve','insurance.underwriting.decision.record') AND(p_event IS NULL OR e.event_id=p_event) AND(p_action IS NULL OR e.action=p_action)) IS NOT TRUE THEN RAISE EXCEPTION 'Original completed STAFF append required' USING ERRCODE='23514';END IF;
 PERFORM firefly_audit_private.context_for(c.nonce);
END $$;
CREATE FUNCTION firefly_audit.underwriting_event(p_tenant UUID,p_submission UUID,p_event UUID,p_action TEXT,p_digest TEXT,p_issuer TEXT,p_subject TEXT,p_client TEXT,p_platform TEXT,p_staff_realm TEXT,p_staff_id UUID,p_revision TEXT,p_process UUID,p_correlation UUID,p_causation UUID,p_hash TEXT)
RETURNS TIMESTAMPTZ LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE e firefly_audit.audit_event;previous firefly_audit.audit_event;expected_sequence INTEGER;
BEGIN
 SELECT * INTO STRICT e FROM firefly_audit.audit_event a WHERE a.resource_tenant_id=p_tenant AND a.event_id=p_event;PERFORM firefly_audit_private.require_record_hash(e);
 expected_sequence:=CASE p_action WHEN 'insurance.underwriting.referral.create' THEN 1 WHEN 'insurance.underwriting.referral.approve' THEN 2 WHEN 'insurance.underwriting.decision.record' THEN 3 ELSE NULL END;
 IF expected_sequence IS NULL OR p_staff_realm IS NULL OR p_staff_id IS NULL OR e.sequence<>expected_sequence THEN RAISE EXCEPTION 'Exact STAFF phase required' USING ERRCODE='23514';END IF;
 IF expected_sequence=1 THEN
  IF e.previous_hash<>repeat('0',64) OR e.causation_id IS NOT NULL THEN RAISE EXCEPTION 'Original STAFF stream genesis required' USING ERRCODE='23514';END IF;
 ELSE
  SELECT * INTO STRICT previous FROM firefly_audit.audit_event a WHERE a.resource_tenant_id=p_tenant AND a.event_id=p_causation;PERFORM firefly_audit_private.require_record_hash(previous);
  IF previous.sequence<>expected_sequence-1 OR e.previous_hash IS DISTINCT FROM previous.record_hash
   OR ROW(previous.service,previous.resource_type,previous.resource_id,previous.action,previous.outcome) IS DISTINCT FROM ROW('core-insurance-underwriting'::text,'governed-underwriting'::text,p_submission,(CASE expected_sequence WHEN 2 THEN 'insurance.underwriting.referral.create' ELSE 'insurance.underwriting.referral.approve' END)::text,'COMPLETED'::text)
   OR previous.actor_staff_realm IS NULL OR previous.actor_staff_id IS NULL OR(expected_sequence=2 AND ROW(previous.actor_staff_realm,previous.actor_staff_id) IS NOT DISTINCT FROM ROW(p_staff_realm,p_staff_id)) THEN RAISE EXCEPTION 'Exact distinct STAFF phase predecessor required' USING ERRCODE='23514';END IF;
 END IF;
 IF ROW(e.service,e.resource_type,e.resource_id,e.action,e.outcome,e.actor_issuer,e.actor_subject,e.actor_client_id,e.actor_platform_tenant_id,e.actor_staff_realm,e.actor_staff_id,e.policy_revision,e.evidence_digest,e.process_id,e.correlation_id,e.causation_id,e.record_hash)
  IS DISTINCT FROM ROW('core-insurance-underwriting'::text,'governed-underwriting'::text,p_submission,p_action,'COMPLETED'::text,p_issuer,p_subject,p_client,p_platform,p_staff_realm,p_staff_id,p_revision,p_digest,p_process,p_correlation,p_causation,p_hash)
  OR NOT EXISTS(SELECT 1 FROM firefly_audit.audit_outbox o WHERE o.resource_tenant_id=e.resource_tenant_id AND o.event_id=e.event_id AND o.service=e.service AND o.resource_type=e.resource_type AND o.resource_id=e.resource_id AND o.sequence=e.sequence AND o.record_hash=e.record_hash AND o.intent='AUDIT_EXPORT')
  OR NOT EXISTS(SELECT 1 FROM firefly_audit.audit_head h WHERE h.resource_tenant_id=e.resource_tenant_id AND h.service=e.service AND h.resource_type=e.resource_type AND h.resource_id=e.resource_id AND h.last_sequence>=e.sequence AND(h.last_sequence<>e.sequence OR h.last_hash=e.record_hash)) OR e.recorded_nanos%1000<>0 THEN RAISE EXCEPTION 'Exact canonical permanent STAFF event required' USING ERRCODE='23514';END IF;
 RETURN TIMESTAMPTZ 'epoch'+e.recorded_seconds*INTERVAL '1 second'+(e.recorded_nanos/1000)*INTERVAL '1 microsecond';
END $$;
REVOKE ALL ON FUNCTION firefly_audit.underwriting_write_scope(UUID,UUID,TEXT),firefly_audit.underwriting_event(UUID,UUID,UUID,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,UUID,TEXT,UUID,UUID,UUID,TEXT) FROM PUBLIC,firefly_audit_writer,firefly_audit_reader;
GRANT EXECUTE ON FUNCTION firefly_audit.underwriting_write_scope(UUID,UUID,TEXT),firefly_audit.underwriting_event(UUID,UUID,UUID,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,UUID,TEXT,UUID,UUID,UUID,TEXT) TO quote_guard;
SET LOCAL ROLE quote_owner;
GRANT SELECT(updated_at) ON public.submission_risk,public.statement_of_value TO quote_guard;
GRANT REFERENCES(referral_id) ON public.referral TO quote_guard;
GRANT REFERENCES(underwriting_decision_id) ON public.underwriting_decision TO quote_guard;
GRANT CREATE ON SCHEMA insurance_quote,insurance_quote_private TO quote_guard;
SET LOCAL ROLE quote_guard;
CREATE TABLE insurance_quote.governed_referral(
 referral_id UUID PRIMARY KEY REFERENCES public.referral(referral_id) DEFERRABLE INITIALLY DEFERRED,tenant_id UUID NOT NULL,submission_id UUID NOT NULL,party_id UUID NOT NULL,product_id UUID NOT NULL,risk_id UUID NOT NULL,
 version BIGINT NOT NULL CHECK(version IN(0,1)),state TEXT NOT NULL CHECK(state IN('OPEN','APPROVED')),authority_level TEXT NOT NULL CHECK(octet_length(authority_level) BETWEEN 1 AND 100),
 creator_issuer TEXT NOT NULL,creator_subject TEXT NOT NULL,creator_staff_realm TEXT NOT NULL,creator_staff_id UUID NOT NULL,approver_issuer TEXT,approver_subject TEXT,approver_staff_realm TEXT,approver_staff_id UUID,
 created_action_id UUID NOT NULL,current_action_id UUID NOT NULL,current_event_id UUID NOT NULL,current_receipt_digest TEXT NOT NULL CHECK(current_receipt_digest~'^[0-9a-f]{64}$'),approved_source_digest TEXT,current_updated_at TIMESTAMPTZ(6) NOT NULL,
 UNIQUE(tenant_id,submission_id,risk_id),UNIQUE(tenant_id,referral_id),
 CHECK((version=0 AND state='OPEN' AND approver_issuer IS NULL AND approver_subject IS NULL AND approver_staff_realm IS NULL AND approver_staff_id IS NULL AND approved_source_digest IS NULL)
 OR(version=1 AND state='APPROVED' AND approver_issuer IS NOT NULL AND approver_subject IS NOT NULL AND approver_staff_realm IS NOT NULL AND approver_staff_id IS NOT NULL AND approved_source_digest~'^[0-9a-f]{64}$' AND ROW(creator_staff_realm,creator_staff_id) IS DISTINCT FROM ROW(approver_staff_realm,approver_staff_id))),
 FOREIGN KEY(tenant_id,current_event_id) REFERENCES firefly_audit.audit_event(resource_tenant_id,event_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE insurance_quote.governed_underwriting_decision(
 decision_id UUID PRIMARY KEY REFERENCES public.underwriting_decision(underwriting_decision_id) DEFERRABLE INITIALLY DEFERRED,tenant_id UUID NOT NULL,submission_id UUID NOT NULL,party_id UUID NOT NULL,product_id UUID NOT NULL,risk_id UUID NOT NULL,referral_id UUID NOT NULL,
 approval_action_id UUID NOT NULL,approval_receipt_digest TEXT NOT NULL CHECK(approval_receipt_digest~'^[0-9a-f]{64}$'),action_id UUID NOT NULL,event_id UUID NOT NULL,receipt_digest TEXT NOT NULL CHECK(receipt_digest~'^[0-9a-f]{64}$'),UNIQUE(tenant_id,referral_id),
 FOREIGN KEY(tenant_id,referral_id) REFERENCES insurance_quote.governed_referral(tenant_id,referral_id) DEFERRABLE INITIALLY DEFERRED,FOREIGN KEY(tenant_id,event_id) REFERENCES firefly_audit.audit_event(resource_tenant_id,event_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE insurance_quote.underwriting_action(
 operation_id UUID NOT NULL PRIMARY KEY,
 tenant_id UUID NOT NULL,
 owner_issuer TEXT NOT NULL,
 owner_subject TEXT NOT NULL,
 client_id TEXT NOT NULL,
 platform_tenant TEXT NOT NULL,
 staff_realm TEXT NOT NULL,
 staff_id UUID NOT NULL,
 action_id UUID NOT NULL,
 operation TEXT NOT NULL CHECK(operation IN('REFERRAL_CREATE','REFERRAL_APPROVE','DECISION_RECORD')),
 intent_digest TEXT NOT NULL CHECK(intent_digest~'^[0-9a-f]{64}$'),
 intent_json TEXT NOT NULL CHECK(octet_length(intent_json)<=16384),
 submission_id UUID NOT NULL,
 party_id UUID NOT NULL,
 product_id UUID NOT NULL,
 risk_id UUID NOT NULL,
 source_json TEXT NOT NULL CHECK(octet_length(source_json)<=16384),
 source_digest TEXT NOT NULL CHECK(source_digest~'^[0-9a-f]{64}$'),
 authority_level TEXT NOT NULL,
 referral_id UUID NOT NULL,
 decision_id UUID,
 result_json TEXT NOT NULL CHECK(octet_length(result_json)<=16384),
 receipt_digest TEXT NOT NULL CHECK(receipt_digest~'^[0-9a-f]{64}$'),
 event_id UUID NOT NULL,
 event_record_hash TEXT NOT NULL CHECK(event_record_hash~'^[0-9a-f]{64}$'),
 evidence_digest TEXT NOT NULL CHECK(evidence_digest~'^[0-9a-f]{64}$'),
 binding_id UUID NOT NULL,
 binding_version BIGINT NOT NULL CHECK(binding_version>0),
 binding_digest TEXT NOT NULL CHECK(binding_digest~'^[0-9a-f]{64}$'),
 authorization_policy_revision TEXT NOT NULL,
 authorization_decision_id TEXT NOT NULL,
 admission_policy_revision TEXT NOT NULL,
 admission_decision_id TEXT NOT NULL,
 decision_evidence_digest TEXT NOT NULL CHECK(decision_evidence_digest~'^[0-9a-f]{64}$'),
 process_id UUID NOT NULL,
 correlation_id UUID NOT NULL,
 UNIQUE(tenant_id,action_id),UNIQUE(tenant_id,submission_id,operation),UNIQUE(tenant_id,event_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES firefly_audit.audit_event(resource_tenant_id,event_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,referral_id) REFERENCES insurance_quote.governed_referral(tenant_id,referral_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE insurance_quote.governed_referral ADD CONSTRAINT governed_referral_created_action FOREIGN KEY(tenant_id,created_action_id) REFERENCES insurance_quote.underwriting_action(tenant_id,action_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE insurance_quote.governed_referral ADD CONSTRAINT governed_referral_current_action FOREIGN KEY(tenant_id,current_action_id) REFERENCES insurance_quote.underwriting_action(tenant_id,action_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE insurance_quote.governed_underwriting_decision ADD CONSTRAINT governed_decision_approval_action FOREIGN KEY(tenant_id,approval_action_id) REFERENCES insurance_quote.underwriting_action(tenant_id,action_id) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION insurance_quote_private.underwriting_money_text(value NUMERIC) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
BEGIN
 IF(value>0 AND value=trunc(value,2) AND value<=99999999999999999.99) IS NOT TRUE THEN RAISE EXCEPTION 'Exact supported insured value required' USING ERRCODE='23514';END IF;RETURN trunc(value)::text||'.'||lpad((mod(value,1)*100)::integer::text,2,'0');
END $$;
CREATE FUNCTION insurance_quote_private.underwriting_hash(VARIADIC fields TEXT[]) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE field TEXT;encoded BYTEA;bytes BYTEA;
BEGIN
 IF fields IS NULL OR cardinality(fields) NOT BETWEEN 1 AND 128 THEN RAISE EXCEPTION 'Bounded original owner encoding required' USING ERRCODE='23514';END IF;
 encoded:=int4send(cardinality(fields));FOREACH field IN ARRAY fields LOOP
  IF field IS NULL OR octet_length(field)>32768 THEN RAISE EXCEPTION 'Bounded original owner field required' USING ERRCODE='23514';END IF;
  bytes:=convert_to(field,'UTF8');encoded:=encoded||int4send(octet_length(bytes))||bytes;
 END LOOP;
 IF octet_length(encoded)>65536 THEN RAISE EXCEPTION 'Bounded original owner encoding required' USING ERRCODE='23514';END IF;RETURN encode(sha256(encoded),'hex');
END $$;
CREATE FUNCTION insurance_quote_private.underwriting_context() RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE value JSONB;now_ms BIGINT;
BEGIN
 IF current_user<>'quote_guard' OR session_user<>'firefly_audit_writer' THEN RAISE EXCEPTION 'Dedicated original writer required' USING ERRCODE='42501';END IF;
 value:=NULLIF(current_setting('insurance_quote.underwriting_context',true),'')::jsonb;now_ms:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
 IF(value IS NOT NULL AND jsonb_typeof(value)='object' AND value->>'mode' IN('READ','WRITE') AND value->>'xid'=pg_current_xact_id_if_assigned()::text AND(value->>'pid')::integer=pg_backend_pid() AND(value->>'until')::bigint>now_ms AND(value->>'until')::bigint<=now_ms+5000) IS NOT TRUE THEN RAISE EXCEPTION 'Current original underwriting root required' USING ERRCODE='42501';END IF;RETURN value;
END $$;
CREATE FUNCTION insurance_quote.underwriting_open(p_tenant UUID,p_submission UUID,p_risk UUID,p_action UUID,p_operation TEXT,p_digest TEXT,p_issuer TEXT,p_subject TEXT,p_client TEXT,p_platform TEXT,p_staff_realm TEXT,p_staff_id UUID,p_until BIGINT)
RETURNS TABLE(tenant_id UUID,submission_id UUID,party_id UUID,product_id UUID,submission_risk_id UUID,risk_party_id UUID,insured_object_type TEXT,submission_status TEXT,line_of_business TEXT,currency TEXT,statement_of_value_id UUID,line_number INTEGER,declared_value NUMERIC,value_currency TEXT,risk_updated_at TIMESTAMPTZ,value_updated_at TIMESTAMPTZ)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE parent RECORD;r RECORD;v RECORD;now_ms BIGINT;
BEGIN
 now_ms:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
 IF(session_user='firefly_audit_writer' AND NULLIF(current_setting('insurance_quote.underwriting_context',true),'') IS NULL AND p_tenant IS NOT NULL AND p_submission IS NOT NULL AND p_risk IS NOT NULL AND p_action IS NOT NULL
  AND p_tenant<>'00000000-0000-0000-0000-000000000000'::uuid AND p_submission<>'00000000-0000-0000-0000-000000000000'::uuid AND p_risk<>'00000000-0000-0000-0000-000000000000'::uuid AND p_action<>'00000000-0000-0000-0000-000000000000'::uuid
  AND p_operation IN('REFERRAL_CREATE','REFERRAL_APPROVE','DECISION_RECORD') AND p_digest~'^[0-9a-f]{64}$' AND octet_length(p_issuer) BETWEEN 1 AND 512 AND octet_length(p_subject) BETWEEN 1 AND 255 AND octet_length(p_client) BETWEEN 1 AND 255 AND octet_length(p_platform) BETWEEN 1 AND 1024 AND octet_length(p_staff_realm) BETWEEN 1 AND 255 AND p_staff_id IS NOT NULL AND p_staff_id<>'00000000-0000-0000-0000-000000000000'::uuid AND p_until>now_ms AND p_until<=now_ms+5000) IS NOT TRUE THEN RAISE EXCEPTION 'Fresh bounded owner underwriting root required' USING ERRCODE='42501';END IF;
 IF EXISTS(SELECT 1 FROM insurance_quote.underwriting_action a WHERE a.tenant_id=p_tenant AND a.action_id=p_action AND ROW(a.owner_issuer,a.owner_subject,a.client_id,a.platform_tenant,a.staff_realm,a.staff_id) IS DISTINCT FROM ROW(p_issuer,p_subject,p_client,p_platform,p_staff_realm,p_staff_id)) THEN RAISE EXCEPTION 'Original complete STAFF action owner required' USING ERRCODE='42501';END IF;
 SELECT x.submission_id,x.tenant_id,x.party_id,x.product_id,x.submission_status,x.line_of_business,x.currency INTO STRICT parent FROM public.submission x WHERE x.submission_id=p_submission AND x.tenant_id=p_tenant FOR UPDATE;
 IF(parent.submission_status::text IN('RECEIVED','QUOTED','REFERRED') AND parent.line_of_business::text='PROPERTY' AND parent.currency='USD'
  AND(SELECT count(*) FROM public.submission_risk x WHERE x.submission_id=p_submission)=1 AND(SELECT count(*) FROM public.statement_of_value x WHERE x.submission_id=p_submission)=1) IS NOT TRUE THEN RAISE EXCEPTION 'One current exact PROPERTY risk/value required' USING ERRCODE='23514';END IF;
 SELECT x.submission_risk_id,x.submission_id,x.insured_object_type,x.party_id,x.updated_at INTO STRICT r FROM public.submission_risk x WHERE x.submission_risk_id=p_risk AND x.submission_id=p_submission;
 SELECT x.statement_of_value_id,x.submission_id,x.line_number,x.declared_value,x.currency,x.updated_at INTO STRICT v FROM public.statement_of_value x WHERE x.submission_id=p_submission;
 IF(r.insured_object_type::text='PROPERTY' AND r.party_id IS NOT DISTINCT FROM parent.party_id AND v.currency='USD' AND v.declared_value>0 AND v.declared_value=trunc(v.declared_value,2) AND r.updated_at<=clock_timestamp() AND v.updated_at<=clock_timestamp()) IS NOT TRUE THEN RAISE EXCEPTION 'Exact current supported property value required' USING ERRCODE='23514';END IF;
 PERFORM set_config('insurance_quote.underwriting_context',jsonb_build_object('mode','WRITE','tenant',p_tenant,'submission',p_submission,'risk',p_risk,'action',p_action,'operation',p_operation,'digest',p_digest,'issuer',p_issuer,'subject',p_subject,'client',p_client,'platform',p_platform,'staffRealm',p_staff_realm,'staffId',p_staff_id,'until',p_until,'pid',pg_backend_pid(),'xid',pg_current_xact_id()::text)::text,true);
 RETURN QUERY SELECT parent.tenant_id,parent.submission_id,parent.party_id,parent.product_id,r.submission_risk_id,r.party_id,r.insured_object_type::text,parent.submission_status::text,parent.line_of_business::text,parent.currency::text,v.statement_of_value_id,v.line_number,v.declared_value,v.currency::text,r.updated_at,v.updated_at;
END $$;
CREATE FUNCTION insurance_quote.underwriting_read(p_tenant UUID,p_action UUID,p_digest TEXT,p_issuer TEXT,p_subject TEXT,p_client TEXT,p_platform TEXT,p_staff_realm TEXT,p_staff_id UUID,p_until BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE now_ms BIGINT;
BEGIN
 now_ms:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
 IF(session_user='firefly_audit_writer' AND NULLIF(current_setting('insurance_quote.underwriting_context',true),'') IS NULL AND p_tenant IS NOT NULL AND p_action IS NOT NULL AND p_digest~'^[0-9a-f]{64}$' AND octet_length(p_issuer) BETWEEN 1 AND 512 AND octet_length(p_subject) BETWEEN 1 AND 255 AND octet_length(p_client) BETWEEN 1 AND 255 AND octet_length(p_platform) BETWEEN 1 AND 1024 AND octet_length(p_staff_realm) BETWEEN 1 AND 255 AND p_staff_id IS NOT NULL AND p_staff_id<>'00000000-0000-0000-0000-000000000000'::uuid AND p_until>now_ms AND p_until<=now_ms+5000) IS NOT TRUE THEN RAISE EXCEPTION 'Fresh bounded read-only underwriting observation required' USING ERRCODE='42501';END IF;
 IF EXISTS(SELECT 1 FROM insurance_quote.underwriting_action a WHERE a.tenant_id=p_tenant AND a.action_id=p_action AND ROW(a.owner_issuer,a.owner_subject,a.client_id,a.platform_tenant,a.staff_realm,a.staff_id) IS DISTINCT FROM ROW(p_issuer,p_subject,p_client,p_platform,p_staff_realm,p_staff_id)) THEN RAISE EXCEPTION 'Original complete STAFF action owner required' USING ERRCODE='42501';END IF;
 PERFORM set_config('insurance_quote.underwriting_context',jsonb_build_object('mode','READ','tenant',p_tenant,'action',p_action,'digest',p_digest,'issuer',p_issuer,'subject',p_subject,'client',p_client,'platform',p_platform,'staffRealm',p_staff_realm,'staffId',p_staff_id,'until',p_until,'pid',pg_backend_pid(),'xid',pg_current_xact_id()::text)::text,true);RETURN true;
END $$;
CREATE FUNCTION insurance_quote.underwriting_row_scope(p_tenant UUID,p_submission UUID,p_risk UUID,p_action UUID DEFAULT NULL,p_issuer TEXT DEFAULT NULL,p_subject TEXT DEFAULT NULL) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE value JSONB;
BEGIN
 IF NULLIF(current_setting('insurance_quote.underwriting_context',true),'') IS NULL THEN RETURN false;END IF;value:=insurance_quote_private.underwriting_context();
 RETURN p_tenant IS NOT DISTINCT FROM(value->>'tenant')::uuid AND((value->>'mode'='READ' AND(p_action IS NOT NULL OR EXISTS(SELECT 1 FROM insurance_quote.underwriting_action a WHERE a.tenant_id=p_tenant AND a.action_id=(value->>'action')::uuid AND a.submission_id=p_submission AND a.risk_id=p_risk AND ROW(a.owner_issuer,a.owner_subject,a.client_id,a.platform_tenant,a.staff_realm,a.staff_id) IS NOT DISTINCT FROM ROW(value->>'issuer',value->>'subject',value->>'client',value->>'platform',value->>'staffRealm',(value->>'staffId')::uuid)))) OR(value->>'mode'='WRITE' AND p_submission IS NOT DISTINCT FROM(value->>'submission')::uuid AND p_risk IS NOT DISTINCT FROM(value->>'risk')::uuid))
  AND(p_action IS NULL OR p_action IS NOT DISTINCT FROM(value->>'action')::uuid) AND(p_issuer IS NULL OR p_issuer IS NOT DISTINCT FROM value->>'issuer') AND(p_subject IS NULL OR p_subject IS NOT DISTINCT FROM value->>'subject');
END $$;
CREATE FUNCTION insurance_quote.underwriting_public_scope(p_submission UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE value JSONB;
BEGIN
 IF NULLIF(current_setting('insurance_quote.underwriting_context',true),'') IS NULL THEN RETURN false;END IF;value:=insurance_quote_private.underwriting_context();RETURN value->>'mode'='WRITE' AND p_submission IS NOT DISTINCT FROM(value->>'submission')::uuid;
END $$;
CREATE FUNCTION insurance_quote_private.underwriting_source_digest(source JSONB) RETURNS TEXT
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
BEGIN
 IF(jsonb_typeof(source)='object' AND(SELECT count(*) FROM jsonb_object_keys(source))=16 AND source ?& ARRAY['tenantId','submissionId','partyId','productId','riskId','riskPartyId','objectType','status','lineOfBusiness','currency','valueId','lineNumber','declaredValue','valueCurrency','riskUpdatedAt','valueUpdatedAt']
  AND jsonb_typeof(source->'lineNumber')='number' AND jsonb_typeof(source->'declaredValue')='number'
  AND NOT EXISTS(SELECT 1 FROM jsonb_each(source) e WHERE e.key NOT IN('lineNumber','declaredValue') AND jsonb_typeof(e.value)<>'string')) IS NOT TRUE THEN RAISE EXCEPTION 'Exact recorded source snapshot required' USING ERRCODE='23514';END IF;
 RETURN insurance_quote_private.underwriting_hash('CORE_UNDERWRITING_SOURCE_V1',source->>'tenantId',source->>'submissionId',source->>'partyId',source->>'productId',source->>'riskId',source->>'riskPartyId',source->>'objectType',source->>'status',source->>'lineOfBusiness',source->>'currency',source->>'valueId',source->>'lineNumber',insurance_quote_private.underwriting_money_text((source->>'declaredValue')::numeric),source->>'valueCurrency',insurance_quote_private.instant_text((source->>'riskUpdatedAt')::timestamptz),insurance_quote_private.instant_text((source->>'valueUpdatedAt')::timestamptz));
END $$;
CREATE FUNCTION insurance_quote_private.underwriting_verify_action(p_operation UUID,p_live BOOLEAN DEFAULT false) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE a insurance_quote.underwriting_action;c insurance_quote.governed_referral;previous insurance_quote.underwriting_action;created insurance_quote.underwriting_action;
 d insurance_quote.governed_underwriting_decision;r RECORD;decision RECORD;intent JSONB;source JSONB;result JSONB;source_digest TEXT;intent_digest TEXT;decisions TEXT;business TEXT;paired TEXT;receipt TEXT;phase_action TEXT;causation UUID;recorded TIMESTAMPTZ;created_at TIMESTAMPTZ;approval_at TIMESTAMPTZ;ctx JSONB;
BEGIN
 SELECT * INTO STRICT a FROM insurance_quote.underwriting_action x WHERE x.operation_id=p_operation;
 SELECT * INTO STRICT c FROM insurance_quote.governed_referral x WHERE x.referral_id=a.referral_id AND x.tenant_id=a.tenant_id;
 intent:=a.intent_json::jsonb;source:=a.source_json::jsonb;result:=a.result_json::jsonb;
 IF(jsonb_typeof(intent)='object' AND(SELECT count(*) FROM jsonb_object_keys(intent))=8 AND intent ?& ARRAY['operation','actionId','submissionId','riskId','referralId','expectedReferralVersion','expectedReceiptDigest','reason']
  AND jsonb_typeof(result)='object' AND(SELECT count(*) FROM jsonb_object_keys(result))=7 AND result ?& ARRAY['actionId','intentDigest','operation','referralId','referralVersion','decisionId','receiptDigest']
  AND jsonb_typeof(intent->'expectedReferralVersion')='number' AND jsonb_typeof(result->'referralVersion')='number'
  AND NOT EXISTS(SELECT 1 FROM jsonb_each(intent) e WHERE e.key IN('operation','actionId','submissionId','riskId') AND jsonb_typeof(e.value)<>'string')
  AND NOT EXISTS(SELECT 1 FROM jsonb_each(result) e WHERE e.key NOT IN('referralVersion','decisionId') AND jsonb_typeof(e.value)<>'string')
  AND intent->>'operation'=a.operation AND intent->>'actionId'=a.action_id::text AND intent->>'submissionId'=a.submission_id::text AND intent->>'riskId'=a.risk_id::text
  AND ROW(source->>'tenantId',source->>'submissionId',source->>'partyId',source->>'productId',source->>'riskId',source->>'riskPartyId',source->>'objectType',source->>'lineOfBusiness',source->>'currency',source->>'valueCurrency')
   IS NOT DISTINCT FROM ROW(a.tenant_id::text,a.submission_id::text,a.party_id::text,a.product_id::text,a.risk_id::text,a.party_id::text,'PROPERTY'::text,'PROPERTY'::text,'USD'::text,'USD'::text)
  AND source->>'status' IN('RECEIVED','QUOTED','REFERRED') AND ROW(c.submission_id,c.party_id,c.product_id,c.risk_id,c.authority_level) IS NOT DISTINCT FROM ROW(a.submission_id,a.party_id,a.product_id,a.risk_id,a.authority_level)) IS NOT TRUE THEN RAISE EXCEPTION 'Exact closed original STAFF graph required' USING ERRCODE='23514';END IF;
 intent_digest:=insurance_quote_private.underwriting_hash('CORE_UNDERWRITING_ORIGINAL_INTENT_V1',a.tenant_id::text,a.operation,a.action_id::text,a.submission_id::text,a.risk_id::text,coalesce(intent->>'referralId',''),intent->>'expectedReferralVersion',coalesce(intent->>'expectedReceiptDigest',''),coalesce(intent->>'reason',''));
 source_digest:=insurance_quote_private.underwriting_source_digest(source);
 decisions:=insurance_quote_private.underwriting_hash('CORE_UNDERWRITING_PDP_DECISIONS_V1',a.authorization_policy_revision,a.authorization_decision_id,a.admission_policy_revision,a.admission_decision_id);
 business:=insurance_quote_private.underwriting_hash('CORE_UNDERWRITING_BUSINESS_EFFECT_V1',intent_digest,source_digest,a.authority_level,coalesce(intent->>'expectedReceiptDigest',''));
 paired:=insurance_quote_private.underwriting_hash('CORE_UNDERWRITING_PAIRED_EFFECT_V1',business,a.binding_id::text,a.binding_version::text,a.binding_digest,decisions);
 IF ROW(a.intent_digest,a.source_digest,a.decision_evidence_digest,a.evidence_digest) IS DISTINCT FROM ROW(intent_digest,source_digest,decisions,paired) THEN RAISE EXCEPTION 'Actual source and same-response PDP evidence must be paired' USING ERRCODE='23514';END IF;
 IF a.operation='REFERRAL_CREATE' THEN
  IF(intent->'referralId'='null'::jsonb AND intent->>'expectedReferralVersion'='0' AND intent->'expectedReceiptDigest'='null'::jsonb AND jsonb_typeof(intent->'reason')='string' AND octet_length(intent->>'reason') BETWEEN 1 AND 500 AND a.decision_id IS NULL AND c.created_action_id=a.action_id
   AND ROW(c.creator_issuer,c.creator_subject,c.creator_staff_realm,c.creator_staff_id) IS NOT DISTINCT FROM ROW(a.owner_issuer,a.owner_subject,a.staff_realm,a.staff_id)) IS NOT TRUE THEN RAISE EXCEPTION 'Exact original referral creation required' USING ERRCODE='23514';END IF;
  phase_action:='insurance.underwriting.referral.create';causation:=NULL;
 ELSE
  IF(intent->>'referralId'=a.referral_id::text AND jsonb_typeof(intent->'referralId')='string' AND jsonb_typeof(intent->'expectedReceiptDigest')='string' AND intent->'reason'='null'::jsonb) IS NOT TRUE THEN RAISE EXCEPTION 'Exact original existing referral required' USING ERRCODE='23514';END IF;
  SELECT * INTO STRICT previous FROM insurance_quote.underwriting_action x WHERE x.tenant_id=a.tenant_id AND x.submission_id=a.submission_id AND x.referral_id=a.referral_id AND x.operation=(CASE WHEN a.operation='REFERRAL_APPROVE' THEN 'REFERRAL_CREATE' ELSE 'REFERRAL_APPROVE' END);
  IF intent->>'expectedReceiptDigest' IS DISTINCT FROM previous.receipt_digest THEN RAISE EXCEPTION 'Exact original governed predecessor receipt required' USING ERRCODE='23514';END IF;causation:=previous.event_id;
  IF a.operation='REFERRAL_APPROVE' THEN
   IF intent->>'expectedReferralVersion'<>'0' OR a.decision_id IS NOT NULL OR c.version<>1 OR c.state<>'APPROVED' OR c.current_action_id<>a.action_id OR c.current_event_id<>a.event_id OR c.current_receipt_digest<>a.receipt_digest OR c.approved_source_digest<>a.source_digest
    OR ROW(c.approver_issuer,c.approver_subject,c.approver_staff_realm,c.approver_staff_id) IS DISTINCT FROM ROW(a.owner_issuer,a.owner_subject,a.staff_realm,a.staff_id)
    OR ROW(c.creator_staff_realm,c.creator_staff_id) IS NOT DISTINCT FROM ROW(a.staff_realm,a.staff_id) THEN RAISE EXCEPTION 'Distinct canonical STAFF approval required' USING ERRCODE='23514';END IF;
   phase_action:='insurance.underwriting.referral.approve';
  ELSE
   SELECT * INTO STRICT d FROM insurance_quote.governed_underwriting_decision x WHERE x.decision_id=a.decision_id AND x.tenant_id=a.tenant_id;
   IF a.operation<>'DECISION_RECORD' OR intent->>'expectedReferralVersion'<>'1' OR c.version<>1 OR c.state<>'APPROVED' OR c.approved_source_digest<>a.source_digest
    OR ROW(d.submission_id,d.party_id,d.product_id,d.risk_id,d.referral_id,d.approval_action_id,d.approval_receipt_digest,d.action_id,d.event_id,d.receipt_digest)
     IS DISTINCT FROM ROW(a.submission_id,a.party_id,a.product_id,a.risk_id,a.referral_id,previous.action_id,previous.receipt_digest,a.action_id,a.event_id,a.receipt_digest) THEN RAISE EXCEPTION 'Current exact governed approval must precede ACCEPT' USING ERRCODE='23514';END IF;
   phase_action:='insurance.underwriting.decision.record';
  END IF;
 END IF;
 recorded:=firefly_audit.underwriting_event(a.tenant_id,a.submission_id,a.event_id,phase_action,paired,a.owner_issuer,a.owner_subject,a.client_id,a.platform_tenant,a.staff_realm,a.staff_id,a.admission_policy_revision,a.process_id,a.correlation_id,causation,a.event_record_hash);
 receipt:=insurance_quote_private.underwriting_hash('CORE_UNDERWRITING_RECEIPT_V1',a.tenant_id::text,a.owner_issuer,a.owner_subject,a.action_id::text,a.intent_digest,a.operation,a.referral_id::text,(CASE WHEN a.operation='REFERRAL_CREATE' THEN '0' ELSE '1' END),coalesce(a.decision_id::text,''),a.event_record_hash);
 IF a.receipt_digest IS DISTINCT FROM receipt OR ROW(result->>'actionId',result->>'intentDigest',result->>'operation',result->>'referralId',result->>'referralVersion',result->>'decisionId',result->>'receiptDigest')
  IS DISTINCT FROM ROW(a.action_id::text,a.intent_digest,a.operation,a.referral_id::text,(CASE WHEN a.operation='REFERRAL_CREATE' THEN '0' ELSE '1' END),a.decision_id::text,receipt) THEN RAISE EXCEPTION 'Exact original durable STAFF receipt required' USING ERRCODE='23514';END IF;
 SELECT * INTO STRICT created FROM insurance_quote.underwriting_action x WHERE x.tenant_id=a.tenant_id AND x.action_id=c.created_action_id AND x.operation='REFERRAL_CREATE';
 created_at:=firefly_audit.underwriting_event(created.tenant_id,created.submission_id,created.event_id,'insurance.underwriting.referral.create',created.evidence_digest,created.owner_issuer,created.owner_subject,created.client_id,created.platform_tenant,created.staff_realm,created.staff_id,created.admission_policy_revision,created.process_id,created.correlation_id,NULL,created.event_record_hash);
 IF c.version=0 THEN
  IF ROW(c.state,c.current_action_id,c.current_event_id,c.current_receipt_digest,c.current_updated_at) IS DISTINCT FROM ROW('OPEN'::text,created.action_id,created.event_id,created.receipt_digest,created_at) THEN RAISE EXCEPTION 'Exact retained referral genesis metadata required' USING ERRCODE='23514';END IF;
 ELSE
  SELECT * INTO STRICT previous FROM insurance_quote.underwriting_action x WHERE x.tenant_id=a.tenant_id AND x.submission_id=a.submission_id AND x.referral_id=a.referral_id AND x.operation='REFERRAL_APPROVE';
  approval_at:=firefly_audit.underwriting_event(previous.tenant_id,previous.submission_id,previous.event_id,'insurance.underwriting.referral.approve',previous.evidence_digest,previous.owner_issuer,previous.owner_subject,previous.client_id,previous.platform_tenant,previous.staff_realm,previous.staff_id,previous.admission_policy_revision,previous.process_id,previous.correlation_id,created.event_id,previous.event_record_hash);
  IF ROW(c.state,c.current_action_id,c.current_event_id,c.current_receipt_digest,c.current_updated_at,c.approved_source_digest,c.approver_issuer,c.approver_subject,c.approver_staff_realm,c.approver_staff_id)
   IS DISTINCT FROM ROW('APPROVED'::text,previous.action_id,previous.event_id,previous.receipt_digest,approval_at,previous.source_digest,previous.owner_issuer,previous.owner_subject,previous.staff_realm,previous.staff_id) THEN RAISE EXCEPTION 'Exact retained referral approval metadata required' USING ERRCODE='23514';END IF;
 END IF;
 SELECT x.submission_id,x.reason,x.authority_level,x.referral_status,x.referred_at,x.created_at,x.updated_at INTO STRICT r FROM public.referral x WHERE x.referral_id=a.referral_id;
 IF ROW(r.submission_id,r.reason,r.authority_level,r.referred_at,r.created_at,r.updated_at,r.referral_status::text)
  IS DISTINCT FROM ROW(a.submission_id,created.intent_json::jsonb->>'reason',a.authority_level,created_at,created_at,c.current_updated_at,c.state) THEN RAISE EXCEPTION 'Actual canonical public referral differs from governed record' USING ERRCODE='23514';END IF;
 IF a.operation='DECISION_RECORD' THEN
  SELECT x.submission_id,x.decision_outcome,x.conditions,x.authority_reference,x.bound_policy_id,x.decided_at,x.created_at,x.updated_at INTO STRICT decision FROM public.underwriting_decision x WHERE x.underwriting_decision_id=a.decision_id;
  IF ROW(decision.submission_id,decision.decision_outcome::text,decision.conditions,decision.authority_reference,decision.bound_policy_id,decision.decided_at,decision.created_at,decision.updated_at)
   IS DISTINCT FROM ROW(a.submission_id,'ACCEPT'::text,NULL::text,'governed:'||previous.action_id::text||':'||previous.receipt_digest,NULL::uuid,recorded,recorded,recorded) THEN RAISE EXCEPTION 'Actual canonical ACCEPT decision differs from governed approval' USING ERRCODE='23514';END IF;
 END IF;
 IF p_live THEN
  ctx:=insurance_quote_private.underwriting_context();
  IF ctx->>'mode'<>'WRITE' OR ctx->>'action'<>a.action_id::text OR ctx->>'digest'<>a.intent_digest OR ctx->>'issuer'<>a.owner_issuer OR ctx->>'subject'<>a.owner_subject OR ROW(ctx->>'client',ctx->>'platform',ctx->>'staffRealm',(ctx->>'staffId')::uuid) IS DISTINCT FROM ROW(a.client_id,a.platform_tenant,a.staff_realm,a.staff_id) THEN RAISE EXCEPTION 'Current original STAFF write must close once' USING ERRCODE='42501';END IF;
  PERFORM firefly_audit.underwriting_write_scope(a.submission_id,a.event_id,phase_action);
  IF NOT EXISTS(SELECT 1 FROM public.submission s JOIN public.submission_risk x ON x.submission_id=s.submission_id JOIN public.statement_of_value v ON v.submission_id=s.submission_id
   WHERE ROW(s.tenant_id,s.submission_id,s.party_id,s.product_id,x.submission_risk_id,x.party_id,x.insured_object_type::text,s.submission_status::text,s.line_of_business::text,s.currency,v.statement_of_value_id,v.line_number,v.declared_value,v.currency,x.updated_at,v.updated_at)
    IS NOT DISTINCT FROM ROW(a.tenant_id,a.submission_id,a.party_id,a.product_id,a.risk_id,a.party_id,source->>'objectType',source->>'status',source->>'lineOfBusiness',source->>'currency',(source->>'valueId')::uuid,(source->>'lineNumber')::integer,(source->>'declaredValue')::numeric,source->>'valueCurrency',(source->>'riskUpdatedAt')::timestamptz,(source->>'valueUpdatedAt')::timestamptz)) THEN RAISE EXCEPTION 'Current locked property/value differs from admitted source' USING ERRCODE='23514';END IF;
 END IF;RETURN true;
END $$;
CREATE FUNCTION insurance_quote.underwriting_receipt(p_operation UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE ctx JSONB;a insurance_quote.underwriting_action;
BEGIN
 ctx:=insurance_quote_private.underwriting_context();SELECT * INTO STRICT a FROM insurance_quote.underwriting_action x WHERE x.operation_id=p_operation;
 IF ROW(a.tenant_id,a.action_id,a.intent_digest,a.owner_issuer,a.owner_subject,a.client_id,a.platform_tenant,a.staff_realm,a.staff_id) IS DISTINCT FROM ROW((ctx->>'tenant')::uuid,(ctx->>'action')::uuid,ctx->>'digest',ctx->>'issuer',ctx->>'subject',ctx->>'client',ctx->>'platform',ctx->>'staffRealm',(ctx->>'staffId')::uuid) THEN RAISE EXCEPTION 'Original owner action read required' USING ERRCODE='42501';END IF;
 RETURN insurance_quote_private.underwriting_verify_action(p_operation,false);
END $$;
CREATE FUNCTION insurance_quote_private.underwriting_guard_write() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE ctx JSONB;submission UUID;phase_action TEXT;selected UUID;has_control BOOLEAN;
BEGIN
 IF TG_TABLE_SCHEMA='public' THEN
  IF TG_TABLE_NAME='referral' THEN selected:=CASE WHEN TG_OP='DELETE' THEN OLD.referral_id ELSE NEW.referral_id END;SELECT EXISTS(SELECT 1 FROM insurance_quote.governed_referral g WHERE g.referral_id=selected) INTO has_control;
  ELSIF TG_TABLE_NAME='underwriting_decision' THEN selected:=CASE WHEN TG_OP='DELETE' THEN OLD.underwriting_decision_id ELSE NEW.underwriting_decision_id END;SELECT EXISTS(SELECT 1 FROM insurance_quote.governed_underwriting_decision g WHERE g.decision_id=selected) INTO has_control;
  ELSE RAISE EXCEPTION 'Exact governed public effect trigger required' USING ERRCODE='42501';END IF;
  IF NULLIF(current_setting('insurance_quote.underwriting_context',true),'') IS NULL AND NOT has_control AND session_user<>'firefly_audit_writer' THEN IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;END IF;
 END IF;
 ctx:=insurance_quote_private.underwriting_context();
 IF ctx->>'mode'<>'WRITE' OR TG_OP='DELETE' THEN RAISE EXCEPTION 'Original immutable STAFF effect required' USING ERRCODE='42501';END IF;
 phase_action:=CASE ctx->>'operation' WHEN 'REFERRAL_CREATE' THEN 'insurance.underwriting.referral.create' WHEN 'REFERRAL_APPROVE' THEN 'insurance.underwriting.referral.approve' WHEN 'DECISION_RECORD' THEN 'insurance.underwriting.decision.record' END;
 submission:=(ctx->>'submission')::uuid;PERFORM firefly_audit.underwriting_write_scope(submission,NULL,phase_action);
 IF TG_TABLE_SCHEMA='insurance_quote' THEN
  IF TG_TABLE_NAME='underwriting_action' THEN
   IF TG_OP<>'INSERT' OR ROW(NEW.tenant_id,NEW.submission_id,NEW.risk_id,NEW.action_id,NEW.operation,NEW.intent_digest,NEW.owner_issuer,NEW.owner_subject,NEW.client_id,NEW.platform_tenant,NEW.staff_realm,NEW.staff_id) IS DISTINCT FROM ROW((ctx->>'tenant')::uuid,submission,(ctx->>'risk')::uuid,(ctx->>'action')::uuid,ctx->>'operation',ctx->>'digest',ctx->>'issuer',ctx->>'subject',ctx->>'client',ctx->>'platform',ctx->>'staffRealm',(ctx->>'staffId')::uuid) THEN RAISE EXCEPTION 'Exact one immutable original action required' USING ERRCODE='42501';END IF;
   PERFORM firefly_audit.underwriting_write_scope(submission,NEW.event_id,phase_action);
  ELSIF TG_TABLE_NAME='governed_referral' THEN
   IF TG_OP='INSERT' THEN
    IF ctx->>'operation'<>'REFERRAL_CREATE' OR NEW.version<>0 OR NEW.state<>'OPEN' OR NEW.created_action_id IS DISTINCT FROM(ctx->>'action')::uuid OR NEW.current_action_id IS DISTINCT FROM(ctx->>'action')::uuid THEN RAISE EXCEPTION 'Exact governed referral genesis required' USING ERRCODE='23514';END IF;
   ELSE
    IF ctx->>'operation'<>'REFERRAL_APPROVE' OR OLD.version<>0 OR NEW.version<>1 OR OLD.state<>'OPEN' OR NEW.state<>'APPROVED'
     OR ROW(NEW.referral_id,NEW.tenant_id,NEW.submission_id,NEW.party_id,NEW.product_id,NEW.risk_id,NEW.authority_level,NEW.creator_issuer,NEW.creator_subject,NEW.creator_staff_realm,NEW.creator_staff_id,NEW.created_action_id)
      IS DISTINCT FROM ROW(OLD.referral_id,OLD.tenant_id,OLD.submission_id,OLD.party_id,OLD.product_id,OLD.risk_id,OLD.authority_level,OLD.creator_issuer,OLD.creator_subject,OLD.creator_staff_realm,OLD.creator_staff_id,OLD.created_action_id)
     OR NEW.current_action_id IS DISTINCT FROM(ctx->>'action')::uuid THEN RAISE EXCEPTION 'Exact immutable-scope referral CAS required' USING ERRCODE='23514';END IF;
   END IF;
   IF ROW(NEW.tenant_id,NEW.submission_id,NEW.risk_id) IS DISTINCT FROM ROW((ctx->>'tenant')::uuid,submission,(ctx->>'risk')::uuid) THEN RAISE EXCEPTION 'Current original referral scope required' USING ERRCODE='42501';END IF;
  ELSIF TG_TABLE_NAME='governed_underwriting_decision' THEN
   IF TG_OP<>'INSERT' OR ctx->>'operation'<>'DECISION_RECORD' OR ROW(NEW.tenant_id,NEW.submission_id,NEW.risk_id,NEW.action_id) IS DISTINCT FROM ROW((ctx->>'tenant')::uuid,submission,(ctx->>'risk')::uuid,(ctx->>'action')::uuid) THEN RAISE EXCEPTION 'One exact immutable ACCEPT control required' USING ERRCODE='42501';END IF;
  ELSE RAISE EXCEPTION 'Exact governed custody trigger required' USING ERRCODE='42501';END IF;
 ELSE
  IF NEW.submission_id IS DISTINCT FROM submission THEN RAISE EXCEPTION 'Original public submission scope required' USING ERRCODE='42501';END IF;
  IF TG_TABLE_NAME='referral' THEN
   IF(TG_OP='INSERT' AND ctx->>'operation'='REFERRAL_CREATE' AND NEW.referral_status::text='OPEN') IS NOT TRUE AND(TG_OP='UPDATE' AND ctx->>'operation'='REFERRAL_APPROVE' AND OLD.referral_status::text='OPEN' AND NEW.referral_status::text='APPROVED'
    AND ROW(NEW.referral_id,NEW.submission_id,NEW.reason,NEW.authority_level,NEW.referred_at,NEW.created_at) IS NOT DISTINCT FROM ROW(OLD.referral_id,OLD.submission_id,OLD.reason,OLD.authority_level,OLD.referred_at,OLD.created_at)) IS NOT TRUE THEN RAISE EXCEPTION 'Exact canonical public referral transition required' USING ERRCODE='23514';END IF;
  ELSE
   IF(TG_OP='INSERT' AND ctx->>'operation'='DECISION_RECORD' AND NEW.decision_outcome::text='ACCEPT' AND NEW.conditions IS NULL AND NEW.bound_policy_id IS NULL) IS NOT TRUE THEN RAISE EXCEPTION 'Only exact canonical unconditional ACCEPT is supported' USING ERRCODE='23514';END IF;
  END IF;
 END IF;RETURN NEW;
END $$;
CREATE FUNCTION insurance_quote_private.underwriting_deferred_complete() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE ctx JSONB;operation UUID;
BEGIN
 IF NULLIF(current_setting('insurance_quote.underwriting_context',true),'') IS NULL THEN
  IF TG_TABLE_SCHEMA='public' AND session_user<>'firefly_audit_writer' THEN RETURN NULL;END IF;
  RAISE EXCEPTION 'Original STAFF scope cannot be cleared before deferred completion' USING ERRCODE='42501';
 END IF;ctx:=insurance_quote_private.underwriting_context();
 IF ctx->>'mode'<>'WRITE' THEN RAISE EXCEPTION 'Original write completion required' USING ERRCODE='42501';END IF;
 SELECT a.operation_id INTO STRICT operation FROM insurance_quote.underwriting_action a WHERE a.tenant_id=(ctx->>'tenant')::uuid AND a.action_id=(ctx->>'action')::uuid AND a.owner_issuer=ctx->>'issuer' AND a.owner_subject=ctx->>'subject';
 PERFORM insurance_quote_private.underwriting_verify_action(operation,true);RETURN NULL;
END $$;
CREATE FUNCTION insurance_quote_private.underwriting_parent_guard() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'submission' THEN RAISE EXCEPTION 'Exact governed source trigger required' USING ERRCODE='42501';END IF;
 IF EXISTS(SELECT 1 FROM insurance_quote.governed_referral g WHERE g.submission_id=OLD.submission_id) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retained underwriting source cannot be removed' USING ERRCODE='23503';END IF;
  IF ROW(NEW.submission_id,NEW.tenant_id,NEW.party_id,NEW.product_id) IS DISTINCT FROM ROW(OLD.submission_id,OLD.tenant_id,OLD.party_id,OLD.product_id) THEN RAISE EXCEPTION 'Retained underwriting source scope cannot change' USING ERRCODE='23503';END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE TRIGGER underwriting_referral_guard BEFORE INSERT OR UPDATE OR DELETE ON insurance_quote.governed_referral FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_guard_write();
CREATE TRIGGER underwriting_decision_guard BEFORE INSERT OR UPDATE OR DELETE ON insurance_quote.governed_underwriting_decision FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_guard_write();
CREATE TRIGGER underwriting_action_guard BEFORE INSERT OR UPDATE OR DELETE ON insurance_quote.underwriting_action FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_guard_write();
CREATE CONSTRAINT TRIGGER underwriting_referral_complete AFTER INSERT OR UPDATE ON insurance_quote.governed_referral DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_deferred_complete();
CREATE CONSTRAINT TRIGGER underwriting_decision_complete AFTER INSERT ON insurance_quote.governed_underwriting_decision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_deferred_complete();
CREATE CONSTRAINT TRIGGER underwriting_action_complete AFTER INSERT ON insurance_quote.underwriting_action DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_deferred_complete();
RESET ROLE;
CREATE TRIGGER underwriting_public_referral_guard BEFORE INSERT OR UPDATE OR DELETE ON public.referral FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_guard_write();
CREATE TRIGGER underwriting_public_decision_guard BEFORE INSERT OR UPDATE OR DELETE ON public.underwriting_decision FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_guard_write();
CREATE CONSTRAINT TRIGGER underwriting_public_referral_complete AFTER INSERT OR UPDATE ON public.referral DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_deferred_complete();
CREATE CONSTRAINT TRIGGER underwriting_public_decision_complete AFTER INSERT OR UPDATE ON public.underwriting_decision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_deferred_complete();
CREATE TRIGGER underwriting_retained_source_guard BEFORE UPDATE OR DELETE ON public.submission FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.underwriting_parent_guard();
SET LOCAL ROLE quote_owner;
GRANT SELECT(reason,created_at) ON public.referral TO quote_guard;
GRANT SELECT(created_at) ON public.underwriting_decision TO quote_guard;
ALTER TABLE public.referral ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.referral FORCE ROW LEVEL SECURITY;
ALTER TABLE public.underwriting_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.underwriting_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY underwriting_referral_owner ON public.referral TO quote_guard USING(true) WITH CHECK(true);
CREATE POLICY underwriting_decision_owner ON public.underwriting_decision TO quote_guard USING(true) WITH CHECK(true);
CREATE POLICY underwriting_referral_runtime ON public.referral TO firefly_audit_writer USING(insurance_quote.underwriting_public_scope(submission_id)) WITH CHECK(insurance_quote.underwriting_public_scope(submission_id));
CREATE POLICY underwriting_decision_runtime ON public.underwriting_decision TO firefly_audit_writer USING(insurance_quote.underwriting_public_scope(submission_id)) WITH CHECK(insurance_quote.underwriting_public_scope(submission_id));
GRANT INSERT(referral_id,submission_id,reason,authority_level,referral_status,referred_at,created_at,updated_at) ON public.referral TO firefly_audit_writer;
GRANT SELECT(referral_id,submission_id,authority_level,referral_status,updated_at),UPDATE(referral_status,updated_at) ON public.referral TO firefly_audit_writer;
GRANT INSERT(underwriting_decision_id,submission_id,decision_outcome,conditions,authority_reference,bound_policy_id,decided_at,created_at,updated_at) ON public.underwriting_decision TO firefly_audit_writer;
SET LOCAL ROLE quote_guard;
ALTER TABLE insurance_quote.governed_referral ENABLE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.governed_referral FORCE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.governed_underwriting_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.governed_underwriting_decision FORCE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.underwriting_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE insurance_quote.underwriting_action FORCE ROW LEVEL SECURITY;
CREATE POLICY governed_referral_owner ON insurance_quote.governed_referral TO quote_guard USING(true) WITH CHECK(true);
CREATE POLICY governed_decision_owner ON insurance_quote.governed_underwriting_decision TO quote_guard USING(true) WITH CHECK(true);
CREATE POLICY governed_action_owner ON insurance_quote.underwriting_action TO quote_guard USING(true) WITH CHECK(true);
CREATE POLICY governed_referral_runtime ON insurance_quote.governed_referral TO firefly_audit_writer USING(insurance_quote.underwriting_row_scope(tenant_id,submission_id,risk_id)) WITH CHECK(insurance_quote.underwriting_row_scope(tenant_id,submission_id,risk_id));
CREATE POLICY governed_decision_runtime ON insurance_quote.governed_underwriting_decision TO firefly_audit_writer USING(insurance_quote.underwriting_row_scope(tenant_id,submission_id,risk_id)) WITH CHECK(insurance_quote.underwriting_row_scope(tenant_id,submission_id,risk_id));
CREATE POLICY governed_action_runtime ON insurance_quote.underwriting_action TO firefly_audit_writer USING(insurance_quote.underwriting_row_scope(tenant_id,submission_id,risk_id,action_id,owner_issuer,owner_subject)) WITH CHECK(insurance_quote.underwriting_row_scope(tenant_id,submission_id,risk_id,action_id,owner_issuer,owner_subject));
REVOKE ALL ON insurance_quote.governed_referral,insurance_quote.governed_underwriting_decision,insurance_quote.underwriting_action FROM PUBLIC,quote_command,quote_read,firefly_audit_writer,firefly_audit_reader;
GRANT SELECT,INSERT ON insurance_quote.governed_referral,insurance_quote.governed_underwriting_decision,insurance_quote.underwriting_action TO firefly_audit_writer;
GRANT UPDATE(version,state,approved_source_digest,approver_issuer,approver_subject,approver_staff_realm,approver_staff_id,current_action_id,current_event_id,current_receipt_digest,current_updated_at) ON insurance_quote.governed_referral TO firefly_audit_writer;

-- Only this successor's functions are sealed; original quote functions retain their ACLs.
REVOKE ALL ON FUNCTION insurance_quote_private.underwriting_money_text(numeric),insurance_quote_private.underwriting_hash(text[]),insurance_quote_private.underwriting_context(),insurance_quote_private.underwriting_source_digest(jsonb),insurance_quote_private.underwriting_verify_action(uuid,boolean),insurance_quote_private.underwriting_guard_write(),insurance_quote_private.underwriting_deferred_complete(),insurance_quote_private.underwriting_parent_guard(),insurance_quote.underwriting_open(uuid,uuid,uuid,uuid,text,text,text,text,text,text,text,uuid,bigint),insurance_quote.underwriting_read(uuid,uuid,text,text,text,text,text,text,uuid,bigint),insurance_quote.underwriting_row_scope(uuid,uuid,uuid,uuid,text,text),insurance_quote.underwriting_public_scope(uuid),insurance_quote.underwriting_receipt(uuid) FROM PUBLIC,quote_owner,quote_command,quote_read,firefly_audit_writer,firefly_audit_reader;
GRANT EXECUTE ON FUNCTION insurance_quote.underwriting_open(uuid,uuid,uuid,uuid,text,text,text,text,text,text,text,uuid,bigint),insurance_quote.underwriting_read(uuid,uuid,text,text,text,text,text,text,uuid,bigint),insurance_quote.underwriting_row_scope(uuid,uuid,uuid,uuid,text,text),insurance_quote.underwriting_public_scope(uuid),insurance_quote.underwriting_receipt(uuid) TO firefly_audit_writer;
CREATE FUNCTION insurance_quote.underwriting_protocol() RETURNS BOOLEAN
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE expected RECORD;p pg_catalog.pg_proc%ROWTYPE;c pg_catalog.pg_class%ROWTYPE;writer OID;guard OID;
BEGIN
 IF current_user<>'quote_guard' OR session_user<>'firefly_audit_writer' OR current_setting('role') NOT IN('none',session_user::text) THEN RAISE EXCEPTION 'Dedicated current underwriting writer required' USING ERRCODE='42501';END IF;
 SELECT oid INTO STRICT writer FROM pg_catalog.pg_roles WHERE rolname='firefly_audit_writer' AND rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication;
 SELECT oid INTO STRICT guard FROM pg_catalog.pg_roles WHERE rolname='quote_guard' AND NOT rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication;
 IF EXISTS(SELECT 1 FROM pg_catalog.pg_auth_members WHERE member IN(writer,guard)) OR pg_catalog.has_schema_privilege(writer,'insurance_quote_private','USAGE') OR pg_catalog.has_schema_privilege(writer,'firefly_audit_private','USAGE')
  OR pg_catalog.has_schema_privilege(writer,'insurance_quote','CREATE') OR pg_catalog.has_schema_privilege(writer,'public','CREATE') OR pg_catalog.has_schema_privilege(guard,'insurance_quote','CREATE') OR pg_catalog.has_schema_privilege(guard,'insurance_quote_private','CREATE') THEN RAISE EXCEPTION 'Separate unprivileged underwriting custody required' USING ERRCODE='42501';END IF;
 FOR expected IN SELECT * FROM(VALUES
  ('firefly_audit.underwriting_write_scope(uuid,uuid,text)','13094e8f4337f465479f72e5d0887a27821d69747ea5b3efcff470460d9c6ecd',true,'v',0,'firefly_audit_guard'),
  ('firefly_audit.underwriting_event(uuid,uuid,uuid,text,text,text,text,text,text,text,uuid,text,uuid,uuid,uuid,text)','718ae96fa66f932cd401aee92a1877bc9fff119e351bdd01849156d9a68ebde1',true,'v',0,'firefly_audit_guard'),
  ('insurance_quote_private.underwriting_money_text(numeric)','95e8d28762606c16301e43ae433f1bf01dd3dcf6967afc9bd66f1b6d02f1e90b',false,'i',0,'quote_guard'),
  ('insurance_quote_private.underwriting_hash(text[])','1fd45bf0ec5c8394f50b340e131bc0ac872eee58a620ea9865d54911ce2082f0',false,'i',0,'quote_guard'),
  ('insurance_quote_private.underwriting_context()','aca835e757d34054bb10cc37a8199b7589e5b024cc1390fe6a614a443a19fc08',true,'v',0,'quote_guard'),
  ('insurance_quote.underwriting_open(uuid,uuid,uuid,uuid,text,text,text,text,text,text,text,uuid,bigint)','240bd10228de9b506ec0b4cd1911dd5873457414504baae1043060c3bc7df1f9',true,'v',0,'quote_guard'),
  ('insurance_quote.underwriting_read(uuid,uuid,text,text,text,text,text,text,uuid,bigint)','8e9fbab4738604fffa5fd565ca0a766b4fd3f5ac43b414c9ff7fa7889144e6df',true,'v',0,'quote_guard'),
  ('insurance_quote.underwriting_row_scope(uuid,uuid,uuid,uuid,text,text)','026bd542b852bbd2f9c2dafdce07bfe53d3950d291558c8e6c9f7127d653b4f4',true,'v',3,'quote_guard'),
  ('insurance_quote.underwriting_public_scope(uuid)','8a0b6052521b6ed2b82ec09cfe78e8b24edfbddfcfa3ec5a0125f66751680ce2',true,'v',0,'quote_guard'),
  ('insurance_quote_private.underwriting_source_digest(jsonb)','3d9e1a5ce734d9436722a9e69fe621bcde4902c7089de20c8113d73795852656',false,'s',0,'quote_guard'),
  ('insurance_quote_private.underwriting_verify_action(uuid,boolean)','b45d4008343e1407868bca41122ad183a86042f763c3e496888c9eed42eff4af',true,'v',1,'quote_guard'),
  ('insurance_quote.underwriting_receipt(uuid)','b94932fe0c1c14ec8cc96512471f8f90f90d41f661cedd379d7b465273ec189a',true,'v',0,'quote_guard'),
  ('insurance_quote_private.underwriting_guard_write()','ef72e805aaad8de1ba737721dc3a5dceea1acbb52cfd08e967d54bf1bf2e1bf7',true,'v',0,'quote_guard'),
  ('insurance_quote_private.underwriting_deferred_complete()','330c7d53044335c5c459d624f2d9ff6b95c38339f88d3c5a6589379ff4c8c110',true,'v',0,'quote_guard'),
  ('insurance_quote_private.underwriting_parent_guard()','7e45af235d59c321324b02c8e41088f8366a94984ddd23b73715ae20a3b9a0ed',true,'v',0,'quote_guard')
 )v(signature,body_hash,definer,volatility,defaults,owner_name) LOOP
  SELECT * INTO p FROM pg_catalog.pg_proc WHERE oid=pg_catalog.to_regprocedure(expected.signature);
  IF NOT FOUND OR(p.proowner=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=expected.owner_name) AND p.prosecdef=expected.definer AND p.provolatile::text=expected.volatility
   AND p.pronargdefaults=expected.defaults AND NOT p.proisstrict AND NOT p.proleakproof AND p.proparallel='u' AND p.prokind='f' AND p.proconfig=ARRAY['search_path=pg_catalog']::text[]
   AND(SELECT lanname FROM pg_catalog.pg_language WHERE oid=p.prolang)='plpgsql' AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex')=expected.body_hash) IS NOT TRUE
   OR EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner)))a WHERE a.privilege_type<>'EXECUTE' OR(a.grantee<>p.proowner AND(a.is_grantable OR NOT(a.grantee=writer AND expected.signature LIKE 'insurance_quote.%') AND NOT(a.grantee=guard AND expected.owner_name='firefly_audit_guard')))) THEN RAISE EXCEPTION 'Exact installed STAFF underwriting helper required' USING ERRCODE='42501';END IF;
  IF expected.signature LIKE 'insurance_quote.%' AND NOT pg_catalog.has_function_privilege(writer,p.oid,'EXECUTE') THEN RAISE EXCEPTION 'Exact public scoped writer entry required' USING ERRCODE='42501';END IF;
 END LOOP;
 FOR expected IN SELECT * FROM(VALUES('insurance_quote','governed_referral','quote_guard'),('insurance_quote','governed_underwriting_decision','quote_guard'),('insurance_quote','underwriting_action','quote_guard'),('public','referral','quote_owner'),('public','underwriting_decision','quote_owner'))v(schema_name,relation_name,owner_name) LOOP
  SELECT t.* INTO c FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=expected.schema_name AND t.relname=expected.relation_name AND t.relkind='r';
  IF NOT FOUND OR(c.relowner=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=expected.owner_name) AND c.relrowsecurity AND c.relforcerowsecurity) IS NOT TRUE
   OR pg_catalog.has_table_privilege(writer,c.oid,'DELETE') OR pg_catalog.has_table_privilege(writer,c.oid,'TRUNCATE') OR pg_catalog.has_table_privilege(writer,c.oid,'TRIGGER') OR pg_catalog.has_table_privilege(writer,c.oid,'REFERENCES')
   OR NOT EXISTS(SELECT 1 FROM pg_catalog.pg_policy v WHERE v.polrelid=c.oid AND v.polroles=ARRAY[guard]::oid[] AND v.polcmd='*' AND v.polpermissive)
   OR NOT EXISTS(SELECT 1 FROM pg_catalog.pg_policy v WHERE v.polrelid=c.oid AND v.polroles=ARRAY[writer]::oid[] AND v.polcmd='*' AND v.polpermissive) THEN RAISE EXCEPTION 'Exact forced scoped underwriting relation required' USING ERRCODE='42501';END IF;
 END LOOP;
 FOR expected IN SELECT * FROM(VALUES('insurance_quote','governed_referral','underwriting_referral_guard',false),('insurance_quote','governed_referral','underwriting_referral_complete',true),('insurance_quote','governed_underwriting_decision','underwriting_decision_guard',false),('insurance_quote','governed_underwriting_decision','underwriting_decision_complete',true),('insurance_quote','underwriting_action','underwriting_action_guard',false),('insurance_quote','underwriting_action','underwriting_action_complete',true),('public','referral','underwriting_public_referral_guard',false),('public','referral','underwriting_public_referral_complete',true),('public','underwriting_decision','underwriting_public_decision_guard',false),('public','underwriting_decision','underwriting_public_decision_complete',true),('public','submission','underwriting_retained_source_guard',false))v(schema_name,relation_name,trigger_name,deferred) LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class relation_row ON relation_row.oid=t.tgrelid JOIN pg_catalog.pg_namespace n ON n.oid=relation_row.relnamespace WHERE n.nspname=expected.schema_name AND relation_row.relname=expected.relation_name AND t.tgname=expected.trigger_name AND t.tgenabled='O' AND t.tgdeferrable=expected.deferred AND t.tginitdeferred=expected.deferred AND NOT t.tgisinternal
   AND t.tgfoid=pg_catalog.to_regprocedure(CASE WHEN expected.deferred THEN 'insurance_quote_private.underwriting_deferred_complete()' WHEN expected.trigger_name='underwriting_retained_source_guard' THEN 'insurance_quote_private.underwriting_parent_guard()' ELSE 'insurance_quote_private.underwriting_guard_write()' END)) THEN RAISE EXCEPTION 'Exact active underwriting closure trigger required' USING ERRCODE='42501';END IF;
 END LOOP;RETURN true;
END $$;
REVOKE ALL ON FUNCTION insurance_quote.underwriting_protocol() FROM PUBLIC,quote_owner,quote_command,quote_read,firefly_audit_writer,firefly_audit_reader;
GRANT EXECUTE ON FUNCTION insurance_quote.underwriting_protocol() TO firefly_audit_writer;
SET LOCAL ROLE quote_owner;
GRANT USAGE ON SCHEMA insurance_quote TO firefly_audit_writer;
REVOKE CREATE ON SCHEMA insurance_quote,insurance_quote_private FROM quote_guard;
RESET ROLE;
COMMIT;
