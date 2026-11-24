-- Only Logistics owns this migration; Auth keeps its own account tables.
DO $$
DECLARE legacy text; occupied boolean;
BEGIN
 IF to_regclass('public.orders') IS NULL OR to_regclass('public.trips') IS NULL THEN
  RAISE EXCEPTION 'Legacy cleanup must run against the Logistics database';
 END IF;
 -- Refuse to erase accounts or optimization history in an unmigrated installation.
 FOREACH legacy IN ARRAY ARRAY['auth_sessions','app_users','optimization_runs'] LOOP
  IF to_regclass('public.' || legacy) IS NOT NULL THEN
   EXECUTE format('LOCK TABLE public.%I IN ACCESS EXCLUSIVE MODE',legacy);
   EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I)',legacy) INTO occupied;
   IF occupied THEN
    RAISE EXCEPTION 'Legacy table % contains data; migrate/archive it before cleanup',legacy;
   END IF;
  END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='trips' AND column_name='run_id') THEN
  LOCK TABLE public.trips IN ACCESS EXCLUSIVE MODE;
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.trips WHERE run_id IS NOT NULL)' INTO occupied;
  IF occupied THEN RAISE EXCEPTION 'Legacy trip run references must be migrated before cleanup';END IF;
 END IF;
END $$;
-- RESTRICT prevents removing unexpected dependents. All three tables are empty.
DROP TABLE IF EXISTS public.auth_sessions;
DROP TABLE IF EXISTS public.app_users;
ALTER TABLE public.trips DROP COLUMN IF EXISTS run_id;
DROP TABLE IF EXISTS public.optimization_runs;
-- Preserve all historical benchmark and ETA rows outside the active schema.
DO $$
DECLARE legacy text;
BEGIN
 FOREACH legacy IN ARRAY ARRAY['benchmark_results','eta_factors'] LOOP
  IF to_regclass('public.' || legacy) IS NOT NULL THEN
   CREATE SCHEMA IF NOT EXISTS gk_legacy_archive;
   EXECUTE format('ALTER TABLE public.%I SET SCHEMA gk_legacy_archive',legacy);
  END IF;
 END LOOP;
END $$;
