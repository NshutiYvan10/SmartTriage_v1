-- V12: Add ECG ST-segment deviation column to vital_streams
ALTER TABLE vital_streams ADD COLUMN IF NOT EXISTS ecg_st_deviation DOUBLE PRECISION;

COMMENT ON COLUMN vital_streams.ecg_st_deviation IS 'ST-segment deviation in mV (positive = elevation, negative = depression)';
