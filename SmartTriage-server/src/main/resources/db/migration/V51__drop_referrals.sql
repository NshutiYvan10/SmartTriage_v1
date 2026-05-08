-- V51 — Remove the outbound referral feature.
--
-- The referrals table modelled OUTBOUND inter-hospital transfers
-- (this hospital sending a patient elsewhere). That workflow is out
-- of scope for SmartTriage's MVP — patients referred TO our hospital
-- still register normally with ArrivalMode.REFERRAL and an optional
-- referring_facility name on the visit.
--
-- The audit found 10 stabilisation-checklist columns on `referrals`
-- that no UI form ever filled, so the data was unsafe to act on
-- anyway (always NULL). Cleaner to drop the table than to leave
-- ghost columns around.
--
-- Indexes are dropped automatically with the table. ArrivalMode and
-- Visit.referring_facility are intentionally untouched.

DROP TABLE IF EXISTS referrals;
