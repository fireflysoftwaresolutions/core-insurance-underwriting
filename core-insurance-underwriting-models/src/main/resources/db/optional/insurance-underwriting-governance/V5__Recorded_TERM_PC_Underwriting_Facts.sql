-- Optional recorded TERM_PC evidence over existing owner tables. No approval or referral is created here.
DO $$
DECLARE name_value TEXT; role_value RECORD; object_owner TEXT;
BEGIN
 IF current_setting('server_encoding')<>'UTF8' OR current_user<>session_user OR session_user<>'quote_migrator'
  OR current_setting('role') NOT IN('none',session_user::text)
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
 FOREACH name_value IN ARRAY ARRAY['submission','submission_risk','underwriting_decision','referral'] LOOP
  SELECT pg_get_userbyid(c.relowner) INTO object_owner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname='public' AND c.relname=name_value AND c.relkind='r';
  IF NOT FOUND OR object_owner IS DISTINCT FROM 'quote_owner' THEN
   RAISE EXCEPTION 'Exact protected recorded source owner required' USING ERRCODE='42501';
  END IF;
 END LOOP;
 FOREACH name_value IN ARRAY ARRAY['insurance_quote','insurance_quote_private'] LOOP
  IF (SELECT pg_get_userbyid(n.nspowner) FROM pg_namespace n WHERE n.nspname=name_value) IS DISTINCT FROM 'quote_owner'
   OR has_schema_privilege('quote_guard',name_value,'CREATE') THEN
   RAISE EXCEPTION 'Prepared source schema and absent guard CREATE required' USING ERRCODE='42501';
  END IF;
 END LOOP;
 IF NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname='insurance_quote_private' AND p.proname='context' AND p.proargtypes='16'::oidvector AND pg_get_userbyid(p.proowner)='quote_guard')
  OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='insurance_quote' AND c.relname='term_pc_underwriting_source')
  OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE (n.nspname='insurance_quote' AND p.proname='term_pc_source_scope') OR (n.nspname='insurance_quote_private' AND p.proname='term_pc_child_lock')) THEN
  RAISE EXCEPTION 'Exact V4 protocol and fresh recorded TERM_PC addition required' USING ERRCODE='42501';
 END IF;
END $$;
SET ROLE quote_owner;
GRANT SELECT(updated_at) ON public.submission_risk TO quote_guard;
GRANT SELECT(underwriting_decision_id,submission_id,decision_outcome,conditions,authority_reference,bound_policy_id,decided_at,updated_at) ON public.underwriting_decision TO quote_guard;
GRANT SELECT(referral_id,submission_id,referral_status,authority_level,referred_at,updated_at) ON public.referral TO quote_guard;
GRANT CREATE ON SCHEMA insurance_quote,insurance_quote_private TO quote_guard;
SET ROLE quote_guard;
CREATE FUNCTION insurance_quote.term_pc_source_scope(p_tenant UUID,p_party UUID,p_submission UUID,p_product UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE value JSONB;
BEGIN
 IF NULLIF(current_setting('insurance_quote.context',true),'') IS NULL THEN RETURN false;END IF;
 value:=insurance_quote_private.context();
 RETURN value->>'mode' IN('OFFER_PREPARE','OFFER','ACCEPT','READ')
  AND p_tenant IS NOT DISTINCT FROM (value->>'tenant')::uuid AND p_party IS NOT DISTINCT FROM (value->>'party')::uuid
  AND p_submission IS NOT DISTINCT FROM (value->>'submission')::uuid AND p_product IS NOT DISTINCT FROM (value->>'product')::uuid;
END $$;
REVOKE ALL ON FUNCTION insurance_quote.term_pc_source_scope(UUID,UUID,UUID,UUID) FROM PUBLIC,quote_command,quote_read;
GRANT EXECUTE ON FUNCTION insurance_quote.term_pc_source_scope(UUID,UUID,UUID,UUID) TO quote_command,quote_read;
CREATE VIEW insurance_quote.term_pc_underwriting_source WITH(security_barrier=true) AS
 SELECT s.tenant_id,s.submission_id,s.party_id,s.product_id,r.submission_risk_id,r.party_id AS risk_party_id,
  r.insured_object_type::text AS insured_object_type,r.updated_at AS risk_updated_at,
  d.underwriting_decision_id,d.decision_outcome::text AS decision_outcome,d.conditions,d.authority_reference,d.bound_policy_id,d.decided_at,d.updated_at AS decision_updated_at,
  f.referral_id,f.referral_status::text AS referral_status,f.authority_level,f.referred_at,f.updated_at AS referral_updated_at
 FROM public.submission s JOIN public.submission_risk r ON r.submission_id=s.submission_id
 JOIN public.underwriting_decision d ON d.submission_id=s.submission_id JOIN public.referral f ON f.submission_id=s.submission_id
 WHERE insurance_quote.term_pc_source_scope(s.tenant_id,s.party_id,s.submission_id,s.product_id);
REVOKE ALL ON insurance_quote.term_pc_underwriting_source FROM PUBLIC,quote_command,quote_read;
GRANT SELECT ON insurance_quote.term_pc_underwriting_source TO quote_command,quote_read;
CREATE FUNCTION insurance_quote_private.term_pc_child_lock() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE old_parent UUID;new_parent UUID;parent UUID;
BEGIN
 IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN('underwriting_decision','referral') THEN
  RAISE EXCEPTION 'Recorded source trigger binding required' USING ERRCODE='42501';
 END IF;
 IF TG_OP<>'INSERT' THEN old_parent:=OLD.submission_id;END IF;
 IF TG_OP<>'DELETE' THEN new_parent:=NEW.submission_id;END IF;
 FOR parent IN SELECT DISTINCT id FROM unnest(ARRAY[old_parent,new_parent]) id WHERE id IS NOT NULL ORDER BY id LOOP
  PERFORM 1 FROM public.submission s WHERE s.submission_id=parent FOR UPDATE;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION insurance_quote_private.term_pc_child_lock() FROM PUBLIC,quote_command,quote_read;
RESET ROLE;
CREATE TRIGGER quote_term_pc_decision_parent_lock BEFORE INSERT OR UPDATE OR DELETE ON public.underwriting_decision
 FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.term_pc_child_lock();
CREATE TRIGGER quote_term_pc_referral_parent_lock BEFORE INSERT OR UPDATE OR DELETE ON public.referral
 FOR EACH ROW EXECUTE FUNCTION insurance_quote_private.term_pc_child_lock();
SET ROLE quote_owner;
REVOKE CREATE ON SCHEMA insurance_quote,insurance_quote_private FROM quote_guard;
DO $$
BEGIN
 IF has_schema_privilege('quote_guard','insurance_quote','CREATE') OR has_schema_privilege('quote_guard','insurance_quote_private','CREATE')
  OR has_schema_privilege('quote_command','insurance_quote_private','USAGE') OR has_schema_privilege('quote_read','insurance_quote_private','USAGE') THEN
  RAISE EXCEPTION 'Guard CREATE and runtime private-schema USAGE must remain absent' USING ERRCODE='42501';
 END IF;
END $$;
RESET ROLE;
